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
package org.exist.storage.vector;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.backup.RawDataBackup;
import org.exist.numbering.NodeId;
import org.exist.storage.BrokerPool;
import org.exist.storage.btree.BTreeException;
import org.exist.storage.btree.DBException;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Value;
import org.exist.storage.index.BFile;
import org.exist.storage.txn.Txn;
import org.exist.util.ByteConversion;
import org.exist.util.FileUtils;
import org.exist.util.FixedByteArray;
import org.exist.xquery.TerminatedException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link VectorStore} in {@code vector.dbx}, a {@link BFile}.
 *
 * <p>Format version 2. A key is the document's id (4 bytes), the node's id (its number of units, 2 bytes, then its
 * bytes), the field's name (its length, 2 bytes, then its UTF-8 bytes) and the chunk (4 bytes), all big-endian, so
 * that the keys of a document, of a node and of a node's field are each a prefix of the next, and a node's chunks
 * are in their order. A value is the format (1 byte), the encoding (1 byte, 0 for float32), the dimension (2 bytes),
 * the model's fingerprint and the input's hash (8 bytes each), the chunk's start and end (4 bytes each), and the
 * vector, little-endian.</p>
 *
 * <p>A file of another format version, such as version 1, which kept one vector per node by its document's path, is
 * set aside at startup, as {@code vector.dbx.v<version>-<time>}, and a new one created: a reindex computes its
 * vectors again.</p>
 */
public class VectorStoreImpl implements VectorStore {

    private static final Logger LOG = LogManager.getLogger(VectorStoreImpl.class);

    public static final String FILE_NAME = "vector.dbx";
    public static final short FILE_FORMAT_VERSION_ID = 2;
    public static final byte VECTOR_DBX_ID = 0x20;

    private static final byte VALUE_FORMAT = 2;
    private static final byte ENCODING_FLOAT32 = 0;
    private static final int VALUE_HEADER_LENGTH = 1 + 1 + 2 + 8 + 8 + 4 + 4;

    private final BFile bfile;
    private final AtomicLong entryCount = new AtomicLong(-1);

    public VectorStoreImpl(final BrokerPool pool, final Path dataDir) throws DBException {
        final Path file = dataDir.resolve(FILE_NAME);
        setAsideOtherVersion(file);
        this.bfile = new BFile(pool, VECTOR_DBX_ID, FILE_FORMAT_VERSION_ID, true, file,
                pool.getCacheManager(), 1.25, 0.03);
    }

    /**
     * Moves a file of another format version out of the way, so that a new one is created in its place.
     */
    static void setAsideOtherVersion(final Path file) throws DBException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        final short version;
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] header = in.readNBytes(2);
            if (header.length < 2) {
                return;
            }
            version = ByteConversion.byteToShort(header, 0);
        } catch (final IOException e) {
            throw new DBException("Could not read the format version of " + file + ": " + e.getMessage());
        }
        if (version == FILE_FORMAT_VERSION_ID) {
            return;
        }
        final Path aside = file.resolveSibling(FILE_NAME + ".v" + version + "-" + System.currentTimeMillis());
        try {
            Files.move(file, aside);
        } catch (final IOException e) {
            throw new DBException("Could not set aside " + file + ", of format version " + version + ": " + e.getMessage());
        }
        LOG.warn("{} has format version {}, not {}: it was set aside as {}, and a new one created. Reindex the collections "
                + "with vector fields to store their vectors again; the old file can then be deleted.",
                file, version, FILE_FORMAT_VERSION_ID, aside.getFileName());
    }

    public BFile getFile() {
        return bfile;
    }

    public long getEntryCount() throws IOException {
        final long cached = entryCount.get();
        if (cached >= 0) {
            return cached;
        }
        return initializeEntryCount();
    }

    public void resetEntryCountCache() {
        entryCount.set(-1);
    }

    Path getFilePath() {
        return bfile.getFile();
    }

    private long initializeEntryCount() throws IOException {
        try {
            final long count = bfile.getKeys().size();
            entryCount.compareAndSet(-1, count);
            return entryCount.get() >= 0 ? entryCount.get() : count;
        } catch (final BTreeException | TerminatedException e) {
            throw new IOException(e);
        }
    }

    private void adjustEntryCount(final long delta) {
        final long cached = entryCount.get();
        if (cached >= 0) {
            entryCount.addAndGet(delta);
        }
    }

    /** The key of a document, a node, a field and a chunk, or of a prefix of them, if the later ones are null. */
    private static Value key(final int docId, @Nullable final NodeId nodeId, @Nullable final String field,
            @Nullable final Integer chunk) {
        final byte[] fieldBytes = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
        final int length = 4 + (nodeId == null ? 0 : 2 + nodeId.size()) + (fieldBytes == null ? 0 : 2 + fieldBytes.length)
                + (chunk == null ? 0 : 4);
        final byte[] key = new byte[length];
        int offset = 0;
        ByteConversion.intToByteH(docId, key, offset);
        offset += 4;
        if (nodeId != null) {
            ByteConversion.shortToByteH((short) nodeId.units(), key, offset);
            offset += 2;
            nodeId.serialize(key, offset);
            offset += nodeId.size();
        }
        if (fieldBytes != null) {
            ByteConversion.shortToByteH((short) fieldBytes.length, key, offset);
            offset += 2;
            System.arraycopy(fieldBytes, 0, key, offset, fieldBytes.length);
            offset += fieldBytes.length;
        }
        if (chunk != null) {
            ByteConversion.intToByteH(chunk, key, offset);
        }
        return new Value(key);
    }

    private static byte[] encode(final Entry entry) {
        final ByteBuffer buffer = ByteBuffer.allocate(VALUE_HEADER_LENGTH + Float.BYTES * entry.vector().length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(VALUE_FORMAT).put(ENCODING_FLOAT32).putShort((short) entry.vector().length)
                .putLong(entry.model()).putLong(entry.inputHash()).putInt(entry.start()).putInt(entry.end());
        for (final float value : entry.vector()) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    @Nullable
    private static Entry decode(@Nullable final Value value) {
        if (value == null) {
            return null;
        }
        final byte[] data = value.getData();
        if (data.length < VALUE_HEADER_LENGTH) {
            return null;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        final byte format = buffer.get();
        final byte encoding = buffer.get();
        final int dimension = Short.toUnsignedInt(buffer.getShort());
        if (format != VALUE_FORMAT || encoding != ENCODING_FLOAT32 || data.length != VALUE_HEADER_LENGTH + Float.BYTES * dimension) {
            return null;
        }
        final long model = buffer.getLong();
        final long inputHash = buffer.getLong();
        final int start = buffer.getInt();
        final int end = buffer.getInt();
        final float[] vector = new float[dimension];
        buffer.asFloatBuffer().get(vector);
        return new Entry(start, end, inputHash, model, vector);
    }

    private List<Value> keysWithPrefix(final Value prefix) throws IOException {
        try {
            return bfile.findKeys(new IndexQuery(IndexQuery.TRUNC_RIGHT, prefix));
        } catch (final BTreeException | TerminatedException e) {
            throw new IOException(e);
        }
    }

    @Override
    @Nullable
    public Entry get(final int docId, final NodeId nodeId, final String field, final int chunk) throws IOException {
        return decode(bfile.get(key(docId, nodeId, field, chunk)));
    }

    @Override
    public List<Entry> getChunks(final int docId, final NodeId nodeId, final String field) throws IOException {
        final List<Entry> entries = new ArrayList<>();
        for (final Value key : keysWithPrefix(key(docId, nodeId, field, null))) {
            final Entry entry = decode(bfile.get(key));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    @Override
    public void put(final Txn transaction, final int docId, final NodeId nodeId, final String field, final int chunk,
            final Entry entry) throws IOException {
        final Value key = key(docId, nodeId, field, chunk);
        final boolean existed = bfile.get(key) != null;
        final byte[] value = encode(entry);
        bfile.put(transaction, key, new FixedByteArray(value, 0, value.length), true);
        if (!existed) {
            adjustEntryCount(1);
        }
    }

    @Override
    public void removeChunksFrom(final Txn transaction, final int docId, final NodeId nodeId, final String field,
            final int fromChunk) throws IOException {
        int removed = 0;
        for (final Value key : keysWithPrefix(key(docId, nodeId, field, null))) {
            final byte[] data = key.getData();
            if (ByteConversion.byteToIntH(data, data.length - 4) >= fromChunk) {
                bfile.remove(transaction, key);
                removed++;
            }
        }
        adjustEntryCount(-removed);
    }

    @Override
    public void removeNode(final Txn transaction, final int docId, final NodeId nodeId) throws IOException {
        removeWithPrefix(transaction, key(docId, nodeId, null, null));
    }

    @Override
    public void removeByDocument(final Txn transaction, final int docId) throws IOException {
        removeWithPrefix(transaction, key(docId, null, null, null));
    }

    private void removeWithPrefix(final Txn transaction, final Value prefix) throws IOException {
        final int removed = keysWithPrefix(prefix).size();
        if (removed == 0) {
            return;
        }
        try {
            bfile.removeAll(transaction, new IndexQuery(IndexQuery.TRUNC_RIGHT, prefix));
        } catch (final BTreeException e) {
            throw new IOException(e);
        }
        adjustEntryCount(-removed);
    }

    @Override
    public void backupToArchive(final RawDataBackup backup) throws IOException {
        try {
            final OutputStream os = backup.newEntry(FileUtils.fileName(bfile.getFile()));
            bfile.backupToStream(os);
        } finally {
            backup.closeEntry();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bfile.close();
        } catch (final DBException e) {
            throw new IOException(e);
        }
    }
}
