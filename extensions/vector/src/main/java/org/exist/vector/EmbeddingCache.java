/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.vector;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Vectors a model computed, kept on disk outside the database, so that an instance indexing the same texts with the
 * same model, after a reindex, in a new container or on another machine, reads them rather than computing them again.
 *
 * <p>The cache is a directory, set with the system property {@value #PROPERTY} or the environment variable
 * {@value #ENVIRONMENT}; without either there is no cache. Each model has a subdirectory named by its fingerprint,
 * and in it each process appends to a file of its own, so several instances can share the directory, even while
 * they write. An entry is the SHA-256 hash of the text, then its vector: the text itself isn't kept. The files are
 * read into an index in memory when a model's cache is first used, and read again for what other processes have
 * added since, at most every {@value #RESCAN_MILLIS} ms, when a text isn't found.</p>
 *
 * <p>It is a cache: deleting the directory, or any file in it, only means those vectors are computed again. Entries
 * are not forced to disk; a file that ends in part of an entry, after a crash, is read up to the last whole one.</p>
 *
 * <p>A process can name a segment, with {@link #setSegment}: until it names another, or none, the vectors it adds go
 * to a file of that name, {@code <segment>.vec}, so that a unit of data, such as a volume, gets a file of its own, to
 * be distributed with it. With hits, the vectors it reads from the cache are added to the segment's file too, so that
 * the file has every vector of the unit.</p>
 */
public final class EmbeddingCache {

  private static final Logger LOG = LogManager.getLogger(EmbeddingCache.class);

  /** The system property naming the cache directory. */
  public static final String PROPERTY = "exist.vector.embedding-cache";
  /** The environment variable naming the cache directory, if the system property doesn't. */
  public static final String ENVIRONMENT = "EXIST_VECTOR_EMBEDDING_CACHE";
  /** The shortest time between two reads of the other processes' files. */
  static final long RESCAN_MILLIS = 10_000;
  private static final String FILE_SUFFIX = ".vec";
  private static final int HASH_LENGTH = 32;

  private static final Map<Path, EmbeddingCache> CACHES = new ConcurrentHashMap<>();
  private static final Pattern SEGMENT_NAME = Pattern.compile("[A-Za-z0-9._-]{1,200}");

  /** The segment the vectors added go to, or null for this process's own file. */
  @Nullable private static volatile String segment;
  /** Whether the vectors read from the cache go to the segment too. */
  private static volatile boolean segmentWithHits;

  private final Path directory;
  private final int dimension;
  private final int entryLength;
  /** The entries, by the first 16 bytes of their text's hash, as (file number, offset of the vector). */
  private final Map<Key, Long> index = new HashMap<>();
  /** The files read, in the order of their numbers, and how far each has been read. */
  private final List<Path> files = new ArrayList<>();
  private final List<Long> readUpTo = new ArrayList<>();
  private final Map<Integer, FileChannel> readers = new HashMap<>();
  /** The files this process writes, by segment, "" being its own file, as their numbers. */
  private final Map<String, Integer> writers = new HashMap<>();
  /** The keys in each segment's file, so that a hit is added to it once. */
  private final Map<String, Set<Key>> segmentKeys = new HashMap<>();
  private long lastScan;

  private record Key(long high, long low) {
  }

  private EmbeddingCache(final Path directory, final int dimension) {
    this.directory = directory;
    this.dimension = dimension;
    this.entryLength = HASH_LENGTH + Float.BYTES * dimension;
  }

  /**
   * The cache of a model's vectors, if a cache directory is configured.
   *
   * @param fingerprint the model's fingerprint, see {@link #fingerprint(Path...)}
   * @param dimension the model's vectors' dimension
   * @return the cache, or null if no cache directory is configured or it can't be used
   */
  @Nullable
  public static EmbeddingCache forModel(final String fingerprint, final int dimension) {
    final String configured = configuredDirectory();
    if (configured == null) {
      return null;
    }
    final Path directory = Path.of(configured).resolve(fingerprint + "-" + dimension);
    try {
      Files.createDirectories(directory);
    } catch (final IOException e) {
      LOG.warn("Embedding cache directory {} can't be created, so no vectors are cached: {}", directory, e.getMessage());
      return null;
    }
    return CACHES.computeIfAbsent(directory.toAbsolutePath(), d -> new EmbeddingCache(d, dimension));
  }

  /**
   * @return whether a cache directory is configured
   */
  public static boolean isConfigured() {
    return configuredDirectory() != null;
  }

  @Nullable
  private static String configuredDirectory() {
    final String property = System.getProperty(PROPERTY);
    if (property != null && !property.isBlank()) {
      return property.trim();
    }
    final String environment = System.getenv(ENVIRONMENT);
    return environment != null && !environment.isBlank() ? environment.trim() : null;
  }

  /**
   * A fingerprint of a model: a hash of its files' contents, so that a model's vectors are only reused for the same
   * model, whatever its name, and a model whose files were replaced gets a cache of its own.
   *
   * @param modelFiles the model's files, in a fixed order
   * @return the fingerprint, as hexadecimal digits
   * @throws IOException if a file can't be read
   */
  public static String fingerprint(final Path... modelFiles) throws IOException {
    final MessageDigest digest = sha256();
    final byte[] buffer = new byte[1 << 16];
    for (final Path file : modelFiles) {
      digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
      try (final var in = Files.newInputStream(file)) {
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
          digest.update(buffer, 0, read);
        }
      }
    }
    return hex(digest.digest(), 16);
  }

  /**
   * @param text the text a vector was computed from
   * @return its vector, or null if it isn't in the cache
   */
  @Nullable
  public float[] get(final String text) {
    final byte[] hash = hash(text);
    final Key key = key(hash);
    final Long location;
    synchronized (this) {
      ensureScanned(false);
      Long found = index.get(key);
      if (found == null && System.currentTimeMillis() - lastScan >= RESCAN_MILLIS) {
        ensureScanned(true);
        found = index.get(key);
      }
      location = found;
    }
    return location == null ? null : read(location);
  }

  /**
   * Adds a text's vector, unless the cache has it or its dimension isn't the model's.
   *
   * @param text the text
   * @param vector its vector
   */
  public void put(final String text, @Nullable final float[] vector) {
    if (vector == null || vector.length != dimension) {
      return;
    }
    final byte[] hash = hash(text);
    final Key key = key(hash);
    final String segmentName = segment;
    synchronized (this) {
      ensureScanned(false);
      final boolean known = index.containsKey(key);
      if (known && !segmentWantsHit(segmentName, key)) {
        return;
      }
      try {
        add(hash, key, vector, known, segmentName);
      } catch (final IOException e) {
        LOG.warn("Could not add a vector to the embedding cache {}: {}", directory, e.getMessage());
      }
    }
  }

  /** Whether a vector already in the cache goes to the segment's file, which doesn't have it yet. */
  private boolean segmentWantsHit(@Nullable final String segmentName, final Key key) {
    if (segmentName == null || !segmentWithHits) {
      return false;
    }
    final Set<Key> keys = segmentKeys.get(segmentName);
    return keys == null || !keys.contains(key);
  }

  /** Appends a vector to this process's file for the segment, and indexes it if it is new to the cache. */
  private void add(final byte[] hash, final Key key, final float[] vector, final boolean known,
      @Nullable final String segmentName) throws IOException {
    final int number = writer(segmentName);
    final Set<Key> keys = segmentName == null ? null : segmentKeys.get(segmentName);
    if (keys != null && keys.contains(key)) {
      return;
    }
    final long location = append(number, entry(hash, vector));
    if (!known) {
      index.put(key, location(number, location));
    }
    if (keys != null) {
      keys.add(key);
    }
  }

  private ByteBuffer entry(final byte[] hash, final float[] vector) {
    final ByteBuffer entry = ByteBuffer.allocate(entryLength).order(ByteOrder.LITTLE_ENDIAN);
    entry.put(hash);
    for (final float value : vector) {
      entry.putFloat(value);
    }
    entry.flip();
    return entry;
  }

  /** The number of vectors in the cache, as read so far. */
  synchronized int size() {
    ensureScanned(false);
    return index.size();
  }

  /**
   * Names the segment the vectors added go to, in every model's cache, until another or none is named.
   *
   * @param name the segment's name, letters, digits, '.', '_' and '-', or null for this process's own file
   * @param withHits whether the vectors read from the cache go to the segment too
   * @throws IllegalArgumentException if the name has other characters
   */
  public static void setSegment(@Nullable final String name, final boolean withHits) {
    if (name != null && !SEGMENT_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("A segment's name has only letters, digits, '.', '_' and '-': " + name);
    }
    segment = name;
    segmentWithHits = withHits;
  }

  /** The number of the file this process writes the segment's vectors to, opened the first time. */
  private int writer(@Nullable final String segmentName) throws IOException {
    final String name = segmentName == null ? "" : segmentName;
    final Integer open = writers.get(name);
    if (open != null) {
      return open;
    }
    final Path file = directory.resolve((segmentName == null ? UUID.randomUUID().toString() : segmentName) + FILE_SUFFIX);
    int number = files.indexOf(file);
    if (number < 0) {
      number = files.size();
      files.add(file);
      readUpTo.add(0L);
    }
    final FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
    final FileChannel previous = readers.put(number, channel);
    if (previous != null) {
      previous.close();
    }
    writers.put(name, number);
    if (segmentName != null) {
      // a segment's file may have been written before: read what it has, and remember its keys
      final Set<Key> keys = new HashSet<>();
      segmentKeys.put(segmentName, keys);
      // from its start, though it may have been read already: its keys weren't kept then
      readUpTo.set(number, 0L);
      readFrom(number, keys);
    }
    return number;
  }

  /** Appends an entry to a file this process writes, and returns the offset of its vector. */
  private long append(final int number, final ByteBuffer entry) throws IOException {
    final FileChannel channel = readers.get(number);
    final long offset = channel.size();
    while (entry.hasRemaining()) {
      channel.write(entry, offset + entry.position());
    }
    readUpTo.set(number, offset + entryLength);
    return offset + HASH_LENGTH;
  }

  /** Reads the entries of files not read before, or added to since, at the first use and then when asked to. */
  private void ensureScanned(final boolean again) {
    if (lastScan != 0 && !again) {
      return;
    }
    lastScan = System.currentTimeMillis();
    final int before = index.size();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
      for (final Path file : stream) {
        int number = files.indexOf(file);
        if (number >= 0 && writers.containsValue(number)) {
          continue;
        }
        if (number < 0) {
          number = files.size();
          files.add(file);
          readUpTo.add(0L);
        }
        readFrom(number, null);
      }
    } catch (final IOException e) {
      LOG.warn("Could not read the embedding cache {}: {}", directory, e.getMessage());
    }
    if (index.size() > before) {
      LOG.info("Embedding cache {}: {} vectors", directory, index.size());
    }
  }

  private void readFrom(final int number, @Nullable final Set<Key> keys) throws IOException {
    final FileChannel channel = reader(number);
    final long size = channel.size();
    long offset = readUpTo.get(number);
    final ByteBuffer hash = ByteBuffer.allocate(HASH_LENGTH);
    // only whole entries: a file being written, or cut short by a crash, may end in part of one
    while (offset + entryLength <= size) {
      hash.clear();
      while (hash.hasRemaining()) {
        if (channel.read(hash, offset + hash.position()) < 0) {
          return;
        }
      }
      final Key key = key(hash.array());
      index.putIfAbsent(key, location(number, offset + HASH_LENGTH));
      if (keys != null) {
        keys.add(key);
      }
      offset += entryLength;
    }
    readUpTo.set(number, offset);
  }

  private FileChannel reader(final int number) throws IOException {
    FileChannel channel = readers.get(number);
    if (channel == null) {
      channel = FileChannel.open(files.get(number), StandardOpenOption.READ);
      readers.put(number, channel);
    }
    return channel;
  }

  @Nullable
  private float[] read(final long location) {
    final int number = (int) (location >>> 40);
    final long offset = location & ((1L << 40) - 1);
    final ByteBuffer bytes = ByteBuffer.allocate(Float.BYTES * dimension).order(ByteOrder.LITTLE_ENDIAN);
    try {
      final FileChannel channel;
      synchronized (this) {
        channel = reader(number);
      }
      while (bytes.hasRemaining()) {
        if (channel.read(bytes, offset + bytes.position()) < 0) {
          return null;
        }
      }
    } catch (final IOException e) {
      LOG.warn("Could not read a vector from the embedding cache {}: {}", directory, e.getMessage());
      return null;
    }
    bytes.flip();
    final float[] vector = new float[dimension];
    bytes.asFloatBuffer().get(vector);
    return vector;
  }

  private static long location(final int number, final long offset) {
    return ((long) number << 40) | offset;
  }

  private static Key key(final byte[] hash) {
    final ByteBuffer buffer = ByteBuffer.wrap(hash);
    return new Key(buffer.getLong(), buffer.getLong());
  }

  private static byte[] hash(final String text) {
    return sha256().digest(text.getBytes(StandardCharsets.UTF_8));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static String hex(final byte[] bytes, final int length) {
    final StringBuilder hex = new StringBuilder(length * 2);
    for (int i = 0; i < length; i++) {
      hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16)).append(Character.forDigit(bytes[i] & 0xF, 16));
    }
    return hex.toString();
  }

  /** Forgets the caches opened, for tests that change the configured directory. */
  static void reset() {
    CACHES.values().forEach(EmbeddingCache::close);
    CACHES.clear();
    setSegment(null, false);
  }

  private synchronized void close() {
    for (final FileChannel channel : readers.values()) {
      try {
        channel.close();
      } catch (final IOException e) {
        LOG.debug("Could not close an embedding cache file: {}", e.getMessage());
      }
    }
    readers.clear();
    writers.clear();
    segmentKeys.clear();
  }
}
