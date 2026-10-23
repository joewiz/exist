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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

  private final Path directory;
  private final int dimension;
  private final int entryLength;
  /** The entries, by the first 16 bytes of their text's hash, as (file number, offset of the vector). */
  private final Map<Key, Long> index = new HashMap<>();
  /** The files read, in the order of their numbers, and how far each has been read. */
  private final List<Path> files = new ArrayList<>();
  private final List<Long> readUpTo = new ArrayList<>();
  private final Map<Integer, FileChannel> readers = new HashMap<>();
  @Nullable private FileChannel writer;
  private int writerNumber = -1;
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
    final ByteBuffer entry = ByteBuffer.allocate(entryLength).order(ByteOrder.LITTLE_ENDIAN);
    entry.put(hash);
    for (final float value : vector) {
      entry.putFloat(value);
    }
    entry.flip();
    synchronized (this) {
      ensureScanned(false);
      if (index.containsKey(key)) {
        return;
      }
      try {
        final FileChannel channel = writer();
        final long offset = channel.size();
        while (entry.hasRemaining()) {
          channel.write(entry, offset + entry.position());
        }
        final long end = offset + entryLength;
        readUpTo.set(writerNumber, end);
        index.put(key, location(writerNumber, offset + HASH_LENGTH));
      } catch (final IOException e) {
        LOG.warn("Could not add a vector to the embedding cache {}: {}", directory, e.getMessage());
      }
    }
  }

  /** The number of vectors in the cache, as read so far. */
  synchronized int size() {
    ensureScanned(false);
    return index.size();
  }

  private FileChannel writer() throws IOException {
    if (writer == null) {
      final Path file = directory.resolve(UUID.randomUUID() + FILE_SUFFIX);
      writer = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
      writerNumber = files.size();
      files.add(file);
      readUpTo.add(0L);
      readers.put(writerNumber, writer);
    }
    return writer;
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
        if (number == writerNumber && number >= 0) {
          continue;
        }
        if (number < 0) {
          number = files.size();
          files.add(file);
          readUpTo.add(0L);
        }
        readFrom(number);
      }
    } catch (final IOException e) {
      LOG.warn("Could not read the embedding cache {}: {}", directory, e.getMessage());
    }
    if (index.size() > before) {
      LOG.info("Embedding cache {}: {} vectors", directory, index.size());
    }
  }

  private void readFrom(final int number) throws IOException {
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
      index.putIfAbsent(key(hash.array()), location(number, offset + HASH_LENGTH));
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
    writer = null;
  }
}
