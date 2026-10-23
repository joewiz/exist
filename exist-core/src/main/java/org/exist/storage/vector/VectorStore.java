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

import org.exist.backup.RawDataBackup;
import org.exist.numbering.NodeId;
import org.exist.storage.txn.Txn;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Keeps the vectors a vector field computed, so that a reindex can restore them instead of computing them again.
 *
 * <p>A vector is kept by its document's id, its node's id, its field and its chunk: 0 for a field with one vector per
 * node, and from 0 in order for a field that cuts a node's text into chunks. With it are the hash of the text it was
 * computed from and the model's fingerprint, so that a vector is only reused for the same text and the same
 * model, and a chunk's offsets in the node's text.</p>
 */
public interface VectorStore extends Closeable {

    /**
     * A stored vector.
     *
     * @param start for a chunk, the offset of its first character in the node's chunked text; otherwise 0
     * @param end for a chunk, the offset after its last character; otherwise 0
     * @param inputHash a hash of the text the vector was computed from, see {@link VectorStores#inputHash}
     * @param model the fingerprint of the model that computed it, or 0 if it isn't known
     * @param vector the vector
     */
    record Entry(int start, int end, long inputHash, long model, float[] vector) {
    }

    /**
     * Gets a node's vector of a field.
     *
     * @param docId the document's id
     * @param nodeId the node's id
     * @param field the vector field
     * @param chunk the chunk, 0 for a field with one vector per node
     * @return the entry, or null if none is stored
     */
    @Nullable
    Entry get(int docId, NodeId nodeId, String field, int chunk) throws IOException;

    /**
     * Gets a node's vectors of a field, in the order of their chunks.
     *
     * @param docId the document's id
     * @param nodeId the node's id
     * @param field the vector field
     * @return the entries, none if none is stored
     */
    List<Entry> getChunks(int docId, NodeId nodeId, String field) throws IOException;

    /**
     * Stores a node's vector of a field, in place of any stored for the same chunk.
     *
     * @param transaction the current transaction
     * @param docId the document's id
     * @param nodeId the node's id
     * @param field the vector field
     * @param chunk the chunk, 0 for a field with one vector per node
     * @param entry the vector, with its hashes and offsets
     */
    void put(Txn transaction, int docId, NodeId nodeId, String field, int chunk, Entry entry) throws IOException;

    /**
     * Removes a node's vectors of a field from a chunk on, as when its text now has fewer chunks.
     *
     * @param transaction the current transaction
     * @param docId the document's id
     * @param nodeId the node's id
     * @param field the vector field
     * @param fromChunk the first chunk to remove
     */
    void removeChunksFrom(Txn transaction, int docId, NodeId nodeId, String field, int fromChunk) throws IOException;

    /**
     * Removes all of a node's vectors, of every field.
     *
     * @param transaction the current transaction
     * @param docId the document's id
     * @param nodeId the node's id
     */
    void removeNode(Txn transaction, int docId, NodeId nodeId) throws IOException;

    /**
     * Removes all vectors of a document.
     *
     * @param transaction the current transaction
     * @param docId the document's id
     */
    void removeByDocument(Txn transaction, int docId) throws IOException;

    /**
     * Backs up the store to the given backup.
     *
     * @param backup the backup
     */
    void backupToArchive(RawDataBackup backup) throws IOException;
}
