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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Adapts the StorageEngine Partition API for collection and document metadata.
 *
 * <p>Key formats:
 * <ul>
 *   <li>Collection: "C:" + collectionPath → metadata bytes</li>
 *   <li>Document: "D:" + collectionId + ":" + documentName → metadata bytes</li>
 *   <li>Next ID: "NEXT_ID" → 4-byte big-endian int</li>
 * </ul>
 *
 * <p>This class is engine-agnostic — works with any StorageEngine.</p>
 */
public class StorageCollectionStore {

    private static final String COLLECTION_PREFIX = "C:";
    private static final String DOCUMENT_PREFIX = "D:";
    private static final byte[] NEXT_COLLECTION_ID_KEY = "NEXT_COLLECTION_ID".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT_DOCUMENT_ID_KEY = "NEXT_DOCUMENT_ID".getBytes(StandardCharsets.UTF_8);

    private final Partition partition;
    private final StorageTxnAdapter txnAdapter;

    public StorageCollectionStore(final Partition partition, final StorageTxnAdapter txnAdapter) {
        this.partition = partition;
        this.txnAdapter = txnAdapter;
    }

    /**
     * Store collection metadata.
     */
    public void putCollection(final String path, final byte[] metadata) throws StorageException {
        txnAdapter.put(partition, collectionKey(path), metadata);
    }

    /**
     * Get collection metadata.
     */
    @Nullable
    public byte[] getCollection(final String path) throws StorageException {
        return txnAdapter.get(partition, collectionKey(path));
    }

    /**
     * Delete collection metadata.
     */
    public void deleteCollection(final String path) throws StorageException {
        txnAdapter.delete(partition, collectionKey(path));
    }

    /**
     * Store document metadata.
     */
    public void putDocument(final int collectionId, final String docName, final byte[] metadata) throws StorageException {
        txnAdapter.put(partition, documentKey(collectionId, docName), metadata);
    }

    /**
     * Get document metadata.
     */
    @Nullable
    public byte[] getDocument(final int collectionId, final String docName) throws StorageException {
        return txnAdapter.get(partition, documentKey(collectionId, docName));
    }

    /**
     * Delete document metadata.
     */
    public void deleteDocument(final int collectionId, final String docName) throws StorageException {
        txnAdapter.delete(partition, documentKey(collectionId, docName));
    }

    /**
     * Get the next collection ID (atomic increment).
     */
    public int nextCollectionId() throws StorageException {
        return nextId(NEXT_COLLECTION_ID_KEY);
    }

    /**
     * Get the next document ID (atomic increment).
     */
    public int nextDocumentId() throws StorageException {
        return nextId(NEXT_DOCUMENT_ID_KEY);
    }

    /**
     * List all documents in a collection.
     */
    public List<String> listDocuments(final int collectionId) throws StorageException {
        final String prefix = DOCUMENT_PREFIX + collectionId + ":";
        final byte[] startKey = prefix.getBytes(StandardCharsets.UTF_8);
        // End key: prefix with next char after ':'
        final byte[] endKey = (DOCUMENT_PREFIX + collectionId + ";").getBytes(StandardCharsets.UTF_8);

        final List<String> docNames = new ArrayList<>();
        final ReadTransaction rtxn = txnAdapter.getActiveReadTransaction();
        if (rtxn != null) {
            partition.scan(rtxn, startKey, endKey, (key, value) -> {
                final String keyStr = new String(key, StandardCharsets.UTF_8);
                docNames.add(keyStr.substring(prefix.length()));
            });
        }
        return docNames;
    }

    private int nextId(final byte[] idKey) throws StorageException {
        final byte[] current = txnAdapter.get(partition, idKey);
        int id;
        if (current == null) {
            id = 1;
        } else {
            id = ((current[0] & 0xFF) << 24) | ((current[1] & 0xFF) << 16)
                    | ((current[2] & 0xFF) << 8) | (current[3] & 0xFF);
            id++;
        }
        final byte[] next = new byte[]{
                (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id
        };
        txnAdapter.put(partition, idKey, next);
        return id;
    }

    private static byte[] collectionKey(final String path) {
        return (COLLECTION_PREFIX + path).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] documentKey(final int collectionId, final String docName) {
        return (DOCUMENT_PREFIX + collectionId + ":" + docName).getBytes(StandardCharsets.UTF_8);
    }
}
