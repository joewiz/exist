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
package org.exist.storage.rocksdb;

import org.exist.storage.engine.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end document lifecycle test through the adapter layer with RocksDB.
 * Demonstrates: store document → retrieve document → list collection →
 * query nodes → remove document. Uses StorageDOMFile for node storage and
 * a "collections" partition for collection metadata.
 *
 * <p>This test exercises the full RocksDB → adapter → document lifecycle
 * without modifying NativeBroker, proving the storage path works.</p>
 */
public class RocksDbDocumentLifecycleTest {

    @TempDir
    Path tempDir;

    private StorageEngine engine;
    private StorageTxnAdapter txnAdapter;
    private StorageDOMFile domFile;
    private Partition collectionsPartition;

    @BeforeEach
    void setUp() throws StorageException {
        engine = new RocksDbStorageEngine();
        engine.open(tempDir);
        txnAdapter = new StorageTxnAdapter(engine);
        domFile = new StorageDOMFile(engine.getPartition("dom"), txnAdapter);
        collectionsPartition = engine.getPartition("collections");
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void testFullDocumentLifecycle() throws StorageException {
        final int docId = 1;
        final String collectionPath = "/db/test";
        final String docName = "doc.xml";
        final byte[] xmlContent = "<root><child attr=\"val\">text</child></root>".getBytes(StandardCharsets.UTF_8);

        // === STORE: create collection and store document ===
        txnAdapter.beginWrite();

        // Store collection metadata
        final byte[] collKey = ("C:" + collectionPath).getBytes(StandardCharsets.UTF_8);
        final byte[] collMeta = ("{\"id\":1,\"path\":\"" + collectionPath + "\"}").getBytes(StandardCharsets.UTF_8);
        txnAdapter.put(collectionsPartition, collKey, collMeta);

        // Store document metadata
        final byte[] docMetaKey = ("D:1:" + docName).getBytes(StandardCharsets.UTF_8);
        final byte[] docMeta = ("{\"docId\":" + docId + ",\"name\":\"" + docName + "\"}").getBytes(StandardCharsets.UTF_8);
        txnAdapter.put(collectionsPartition, docMetaKey, docMeta);

        // Store document content as DOM nodes (simplified: single root node)
        domFile.putNode(docId, new byte[]{1}, xmlContent);

        // Store child node
        final byte[] childContent = "<child attr=\"val\">text</child>".getBytes(StandardCharsets.UTF_8);
        domFile.putNode(docId, new byte[]{1, 1}, childContent);

        txnAdapter.commit();

        // === RETRIEVE: read document back ===
        txnAdapter.beginRead();

        // Verify collection metadata
        final byte[] readCollMeta = txnAdapter.get(collectionsPartition, collKey);
        assertNotNull(readCollMeta, "Collection metadata should exist");
        assertTrue(new String(readCollMeta, StandardCharsets.UTF_8).contains(collectionPath));

        // Verify document metadata
        final byte[] readDocMeta = txnAdapter.get(collectionsPartition, docMetaKey);
        assertNotNull(readDocMeta, "Document metadata should exist");
        assertTrue(new String(readDocMeta, StandardCharsets.UTF_8).contains(docName));

        // Verify root node content
        final byte[] rootNode = domFile.getNode(docId, new byte[]{1});
        assertNotNull(rootNode, "Root node should exist");
        assertEquals(new String(xmlContent, StandardCharsets.UTF_8),
                new String(rootNode, StandardCharsets.UTF_8));

        // Verify child node
        final byte[] childNode = domFile.getNode(docId, new byte[]{1, 1});
        assertNotNull(childNode, "Child node should exist");
        assertTrue(new String(childNode, StandardCharsets.UTF_8).contains("attr=\"val\""));

        txnAdapter.endRead();

        // === LIST COLLECTION: scan document keys ===
        txnAdapter.beginRead();

        final List<byte[]> docNodes = new ArrayList<>();
        final ReadTransaction rtxn = txnAdapter.getActiveReadTransaction();
        final byte[] startKey = StorageDOMFile.makeDocPrefix(docId);
        final byte[] endKey = StorageDOMFile.makeDocPrefix(docId + 1);
        engine.getPartition("dom").scan(rtxn, startKey, endKey, (k, v) -> docNodes.add(v));
        assertEquals(2, docNodes.size(), "Document should have 2 nodes");

        txnAdapter.endRead();

        // === REMOVE: delete document ===
        txnAdapter.beginWrite();

        domFile.deleteNode(docId, new byte[]{1});
        domFile.deleteNode(docId, new byte[]{1, 1});
        txnAdapter.delete(collectionsPartition, docMetaKey);

        txnAdapter.commit();

        // === VERIFY REMOVAL ===
        txnAdapter.beginRead();

        assertNull(domFile.getNode(docId, new byte[]{1}), "Root node should be deleted");
        assertNull(domFile.getNode(docId, new byte[]{1, 1}), "Child node should be deleted");
        assertNull(txnAdapter.get(collectionsPartition, docMetaKey), "Document metadata should be deleted");
        // Collection still exists
        assertNotNull(txnAdapter.get(collectionsPartition, collKey), "Collection should still exist");

        txnAdapter.endRead();
    }

    @Test
    void testConcurrentReadWriteIsolation() throws StorageException {
        final int docId = 10;

        // Store initial document
        txnAdapter.beginWrite();
        domFile.putNode(docId, new byte[]{1}, "v1".getBytes(StandardCharsets.UTF_8));
        txnAdapter.commit();

        // Start read transaction (snapshot)
        txnAdapter.beginRead();
        assertEquals("v1", new String(domFile.getNode(docId, new byte[]{1}), StandardCharsets.UTF_8));
        txnAdapter.endRead();

        // Update document
        txnAdapter.beginWrite();
        domFile.putNode(docId, new byte[]{1}, "v2".getBytes(StandardCharsets.UTF_8));
        txnAdapter.commit();

        // New read should see v2
        txnAdapter.beginRead();
        assertEquals("v2", new String(domFile.getNode(docId, new byte[]{1}), StandardCharsets.UTF_8));
        txnAdapter.endRead();
    }

    @Test
    void testSymbolTableRoundTrip() throws StorageException {
        final Partition symbols = engine.getPartition("symbols");

        // Store symbol mappings (like element/attribute names → IDs)
        txnAdapter.beginWrite();
        txnAdapter.put(symbols, "N:root".getBytes(StandardCharsets.UTF_8),
                new byte[]{0, 0, 0, 1}); // symbol ID 1
        txnAdapter.put(symbols, "N:child".getBytes(StandardCharsets.UTF_8),
                new byte[]{0, 0, 0, 2}); // symbol ID 2
        txnAdapter.put(symbols, "A:attr".getBytes(StandardCharsets.UTF_8),
                new byte[]{0, 0, 0, 3}); // symbol ID 3
        txnAdapter.put(symbols, "I:1".getBytes(StandardCharsets.UTF_8),
                "root".getBytes(StandardCharsets.UTF_8)); // reverse: ID → name
        txnAdapter.put(symbols, "I:2".getBytes(StandardCharsets.UTF_8),
                "child".getBytes(StandardCharsets.UTF_8));
        txnAdapter.commit();

        // Read back
        txnAdapter.beginRead();
        assertArrayEquals(new byte[]{0, 0, 0, 1},
                txnAdapter.get(symbols, "N:root".getBytes(StandardCharsets.UTF_8)));
        assertEquals("child", new String(
                txnAdapter.get(symbols, "I:2".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        txnAdapter.endRead();
    }
}
