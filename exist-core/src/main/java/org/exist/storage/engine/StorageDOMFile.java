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
package org.exist.storage.engine;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Adapts the StorageEngine Partition API to DOM node storage operations.
 *
 * <p>Key format: docId (4 bytes big-endian) | nodeId (variable-length DLN bytes).
 * Values are the binary node data (same format as DOMFile records).</p>
 *
 * <p>This class is engine-agnostic — it works with any StorageEngine
 * implementation (LMDB, RocksDB, etc.).</p>
 */
public class StorageDOMFile {

    private final Partition partition;
    private final StorageTxnAdapter txnAdapter;

    public StorageDOMFile(final Partition partition, final StorageTxnAdapter txnAdapter) {
        this.partition = partition;
        this.txnAdapter = txnAdapter;
    }

    /**
     * Store a node's data.
     *
     * @param docId  the document ID
     * @param nodeId the node ID bytes (DLN)
     * @param data   the node data bytes
     * @throws StorageException on write error
     */
    public void putNode(final int docId, final byte[] nodeId, final byte[] data) throws StorageException {
        final byte[] key = makeKey(docId, nodeId);
        txnAdapter.put(partition, key, data);
    }

    /**
     * Retrieve a node's data.
     *
     * @param docId  the document ID
     * @param nodeId the node ID bytes (DLN)
     * @return the node data, or null if not found
     * @throws StorageException on read error
     */
    @Nullable
    public byte[] getNode(final int docId, final byte[] nodeId) throws StorageException {
        final byte[] key = makeKey(docId, nodeId);
        return txnAdapter.get(partition, key);
    }

    /**
     * Delete a node.
     *
     * @param docId  the document ID
     * @param nodeId the node ID bytes (DLN)
     * @throws StorageException on write error
     */
    public void deleteNode(final int docId, final byte[] nodeId) throws StorageException {
        final byte[] key = makeKey(docId, nodeId);
        txnAdapter.delete(partition, key);
    }

    /**
     * Scan all nodes for a document in key order.
     *
     * @param docId   the document ID
     * @param visitor called for each (nodeId, data) pair
     * @throws StorageException on read error
     */
    public void scanDocument(final int docId, final BiConsumer<byte[], byte[]> visitor) throws StorageException {
        final byte[] startKey = makeDocPrefix(docId);
        final byte[] endKey = makeDocPrefix(docId + 1);

        final ReadTransaction rtxn = txnAdapter.getActiveReadTransaction();
        if (rtxn != null) {
            partition.scan(rtxn, startKey, endKey, (key, value) -> {
                final byte[] nodeId = extractNodeId(key);
                visitor.accept(nodeId, value);
            });
        } else {
            // Auto-read: this shouldn't happen in normal flow, but handle gracefully
            try {
                txnAdapter.get(partition, startKey); // trigger auto-read creation
            } catch (final StorageException e) {
                // ignore
            }
        }
    }

    /**
     * Create a key from document ID and node ID.
     * Format: docId (4 bytes big-endian) | nodeId (variable bytes)
     */
    public static byte[] makeKey(final int docId, final byte[] nodeId) {
        final byte[] key = new byte[4 + nodeId.length];
        key[0] = (byte) (docId >> 24);
        key[1] = (byte) (docId >> 16);
        key[2] = (byte) (docId >> 8);
        key[3] = (byte) docId;
        System.arraycopy(nodeId, 0, key, 4, nodeId.length);
        return key;
    }

    /**
     * Create a prefix for all keys belonging to a document.
     */
    public static byte[] makeDocPrefix(final int docId) {
        return new byte[] {
                (byte) (docId >> 24),
                (byte) (docId >> 16),
                (byte) (docId >> 8),
                (byte) docId
        };
    }

    /**
     * Extract the node ID bytes from a key.
     */
    public static byte[] extractNodeId(final byte[] key) {
        final byte[] nodeId = new byte[key.length - 4];
        System.arraycopy(key, 4, nodeId, 0, nodeId.length);
        return nodeId;
    }

    /**
     * Extract the document ID from a key.
     */
    public static int extractDocId(final byte[] key) {
        return ((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
                | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF);
    }
}
