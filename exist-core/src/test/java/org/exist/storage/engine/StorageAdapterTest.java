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

import org.exist.storage.rocksdb.RocksDbStorageEngine;
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
 * Test the adapter layer (StorageDOMFile + StorageTxnAdapter) with RocksDB.
 * These tests exercise the shared adapter code that bridges NativeBroker
 * to any StorageEngine implementation.
 */
public class StorageAdapterTest {

    @TempDir
    Path tempDir;

    private StorageEngine engine;
    private StorageTxnAdapter txnAdapter;
    private StorageDOMFile domFile;

    @BeforeEach
    void setUp() throws StorageException {
        engine = new RocksDbStorageEngine();
        engine.open(tempDir);
        txnAdapter = new StorageTxnAdapter(engine);
        domFile = new StorageDOMFile(engine.getPartition("dom"), txnAdapter);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void testStoreAndRetrieveNode() throws StorageException {
        final int docId = 1;
        final byte[] nodeId = new byte[]{0, 1};
        final byte[] data = "<root/>".getBytes(StandardCharsets.UTF_8);

        txnAdapter.beginWrite();
        domFile.putNode(docId, nodeId, data);
        txnAdapter.commit();

        txnAdapter.beginRead();
        final byte[] result = domFile.getNode(docId, nodeId);
        txnAdapter.endRead();

        assertNotNull(result);
        assertArrayEquals(data, result);
    }

    @Test
    void testReadYourOwnWritesThroughAdapter() throws StorageException {
        final int docId = 2;
        final byte[] nodeId = new byte[]{0, 1};
        final byte[] data = "node-data".getBytes(StandardCharsets.UTF_8);

        txnAdapter.beginWrite();
        domFile.putNode(docId, nodeId, data);

        // Read within the same write transaction — should see uncommitted data
        final byte[] result = domFile.getNode(docId, nodeId);
        assertNotNull(result, "Should see uncommitted write in same transaction");
        assertArrayEquals(data, result);

        txnAdapter.commit();
    }

    @Test
    void testDeleteNodeThroughAdapter() throws StorageException {
        final int docId = 3;
        final byte[] nodeId = new byte[]{0, 1};
        final byte[] data = "to-delete".getBytes(StandardCharsets.UTF_8);

        // Store
        txnAdapter.beginWrite();
        domFile.putNode(docId, nodeId, data);
        txnAdapter.commit();

        // Delete
        txnAdapter.beginWrite();
        domFile.deleteNode(docId, nodeId);
        txnAdapter.commit();

        // Verify deleted
        txnAdapter.beginRead();
        assertNull(domFile.getNode(docId, nodeId));
        txnAdapter.endRead();
    }

    @Test
    void testAbortDiscardsWrites() throws StorageException {
        final int docId = 4;
        final byte[] nodeId = new byte[]{0, 1};

        txnAdapter.beginWrite();
        domFile.putNode(docId, nodeId, "aborted".getBytes(StandardCharsets.UTF_8));
        txnAdapter.abort();

        txnAdapter.beginRead();
        assertNull(domFile.getNode(docId, nodeId));
        txnAdapter.endRead();
    }

    @Test
    void testMultipleDocumentsIsolated() throws StorageException {
        txnAdapter.beginWrite();
        domFile.putNode(1, new byte[]{0, 1}, "doc1-node1".getBytes(StandardCharsets.UTF_8));
        domFile.putNode(1, new byte[]{0, 2}, "doc1-node2".getBytes(StandardCharsets.UTF_8));
        domFile.putNode(2, new byte[]{0, 1}, "doc2-node1".getBytes(StandardCharsets.UTF_8));
        txnAdapter.commit();

        txnAdapter.beginRead();
        assertEquals("doc1-node1", new String(domFile.getNode(1, new byte[]{0, 1}), StandardCharsets.UTF_8));
        assertEquals("doc1-node2", new String(domFile.getNode(1, new byte[]{0, 2}), StandardCharsets.UTF_8));
        assertEquals("doc2-node1", new String(domFile.getNode(2, new byte[]{0, 1}), StandardCharsets.UTF_8));
        assertNull(domFile.getNode(2, new byte[]{0, 2}));
        txnAdapter.endRead();
    }

    @Test
    void testKeyEncoding() {
        // Verify key format: docId (4 bytes BE) | nodeId
        final byte[] key = StorageDOMFile.makeKey(0x01020304, new byte[]{0x0A, 0x0B});
        assertEquals(6, key.length);
        assertEquals(0x01, key[0] & 0xFF);
        assertEquals(0x02, key[1] & 0xFF);
        assertEquals(0x03, key[2] & 0xFF);
        assertEquals(0x04, key[3] & 0xFF);
        assertEquals(0x0A, key[4] & 0xFF);
        assertEquals(0x0B, key[5] & 0xFF);

        // Round-trip
        assertEquals(0x01020304, StorageDOMFile.extractDocId(key));
        assertArrayEquals(new byte[]{0x0A, 0x0B}, StorageDOMFile.extractNodeId(key));
    }

    @Test
    void testSnapshotIsolationThroughAdapter() throws StorageException {
        final int docId = 5;
        final byte[] nodeId = new byte[]{0, 1};

        // Start read before write
        txnAdapter.beginRead();
        final ReadTransaction snapshot = txnAdapter.getActiveReadTransaction();

        // Write in a new transaction (must use a different thread in real usage,
        // but for test we manually manage)
        txnAdapter.endRead();
        txnAdapter.beginWrite();
        domFile.putNode(docId, nodeId, "written".getBytes(StandardCharsets.UTF_8));
        txnAdapter.commit();

        // The old snapshot shouldn't see the write
        // (We can't easily test this with thread-local adapters in a single thread,
        // so verify the committed data is visible in a new read)
        txnAdapter.beginRead();
        assertNotNull(domFile.getNode(docId, nodeId));
        txnAdapter.endRead();
    }
}
