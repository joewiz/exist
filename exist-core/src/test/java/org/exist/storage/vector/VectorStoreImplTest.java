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

import org.exist.numbering.NodeId;
import org.exist.storage.BrokerPool;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.ByteConversion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VectorStoreImpl}: vectors by document, node, field and chunk, and their entry count.
 */
public class VectorStoreImplTest {

    @RegisterExtension
    public final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private VectorStoreImpl store;
    private BrokerPool pool;

    @BeforeEach
    void setUp() {
        pool = existEmbeddedServer.getBrokerPool();
        final VectorStore vectorStore = pool.getVectorStore();
        assertNotNull(vectorStore);
        assertInstanceOf(VectorStoreImpl.class, vectorStore);
        store = (VectorStoreImpl) vectorStore;
        store.resetEntryCountCache();
    }

    @Test
    void entryCountTracksPutAndRemove() throws Exception {
        final NodeId node = pool.getNodeFactory().createInstance();
        assertEquals(0, store.getEntryCount());
        inTransaction(txn -> store.put(txn, 1, node, "f", 0, entry(0)));
        assertEquals(1, store.getEntryCount());
        inTransaction(txn -> store.put(txn, 1, node, "f", 0, entry(1)));
        assertEquals(1, store.getEntryCount(), "the same key is replaced");
        inTransaction(txn -> store.removeNode(txn, 1, node));
        assertEquals(0, store.getEntryCount());
    }

    @Test
    void removeByDocumentAdjustsEntryCount() throws Exception {
        final NodeId node1 = pool.getNodeFactory().createInstance(1);
        final NodeId node2 = pool.getNodeFactory().createInstance(2);
        inTransaction(txn -> {
            store.put(txn, 2, node1, "f", 0, entry(0));
            store.put(txn, 2, node2, "f", 0, entry(1));
            store.put(txn, 3, node1, "f", 0, entry(2));
        });
        assertEquals(3, store.getEntryCount());
        inTransaction(txn -> store.removeByDocument(txn, 2));
        assertEquals(1, store.getEntryCount());
        assertNotNull(store.get(3, node1, "f", 0), "another document's vector is kept");
    }

    @Test
    void resetEntryCountCacheForcesRescan() throws Exception {
        final NodeId node = pool.getNodeFactory().createInstance();
        inTransaction(txn -> store.put(txn, 4, node, "f", 0, entry(0)));
        assertEquals(1, store.getEntryCount());
        store.resetEntryCountCache();
        assertTrue(store.getEntryCount() >= 1);
    }

    @Test
    void anEntryIsReadAsWritten() throws Exception {
        final NodeId node = pool.getNodeFactory().createInstance();
        final VectorStore.Entry written = new VectorStore.Entry(12, 345, 0x1234_5678_9abc_def0L, -1L, new float[] {0.5f, -1.25f, 3});
        inTransaction(txn -> store.put(txn, 5, node, "f", 2, written));
        final VectorStore.Entry read = store.get(5, node, "f", 2);
        assertNotNull(read);
        assertEquals(written.start(), read.start());
        assertEquals(written.end(), read.end());
        assertEquals(written.inputHash(), read.inputHash());
        assertEquals(written.model(), read.model());
        assertArrayEquals(written.vector(), read.vector());
        assertNull(store.get(5, node, "f", 0), "another chunk");
    }

    @Test
    void aNodesFieldsAreKeptApart() throws Exception {
        final NodeId node = pool.getNodeFactory().createInstance();
        inTransaction(txn -> {
            store.put(txn, 6, node, "one", 0, entry(1));
            store.put(txn, 6, node, "two", 0, entry(2));
        });
        assertArrayEquals(entry(1).vector(), store.get(6, node, "one", 0).vector());
        assertArrayEquals(entry(2).vector(), store.get(6, node, "two", 0).vector());
        assertNull(store.get(6, node, "on", 0), "a field whose name is a prefix of another's");
    }

    @Test
    void chunksAreInOrderAndCanBeTrimmed() throws Exception {
        final NodeId node = pool.getNodeFactory().createInstance();
        inTransaction(txn -> {
            // stored out of order, and more than 255 of them, so that the order isn't the bytes' by chance
            for (final int chunk : new int[] {300, 2, 0, 1, 256}) {
                store.put(txn, 7, node, "f", chunk, entry(chunk));
            }
        });
        final List<VectorStore.Entry> chunks = store.getChunks(7, node, "f");
        assertEquals(List.of(0f, 1f, 2f, 256f, 300f), chunks.stream().map(e -> e.vector()[0]).toList());
        inTransaction(txn -> store.removeChunksFrom(txn, 7, node, "f", 2));
        assertEquals(List.of(0f, 1f), store.getChunks(7, node, "f").stream().map(e -> e.vector()[0]).toList());
    }

    @Test
    void removingANodeKeepsItsDescendants() throws Exception {
        final NodeId parent = pool.getNodeFactory().createInstance(1);
        final NodeId child = parent.newChild();
        inTransaction(txn -> {
            store.put(txn, 8, parent, "f", 0, entry(1));
            store.put(txn, 8, parent, "g", 0, entry(2));
            store.put(txn, 8, child, "f", 0, entry(3));
        });
        inTransaction(txn -> store.removeNode(txn, 8, parent));
        assertNull(store.get(8, parent, "f", 0));
        assertNull(store.get(8, parent, "g", 0), "every field of the node");
        assertNotNull(store.get(8, child, "f", 0), "a descendant, whose id starts with the node's");
    }

    @Test
    void aFileOfAnotherVersionIsSetAside(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(VectorStoreImpl.FILE_NAME);
        // version 1, written as a file's header writes it
        Files.write(file, header((short) 1));
        VectorStoreImpl.setAsideOtherVersion(file);
        assertFalse(Files.exists(file));
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith(VectorStoreImpl.FILE_NAME + ".v1-")));
        }
        Files.write(file, header(VectorStoreImpl.FILE_FORMAT_VERSION_ID));
        VectorStoreImpl.setAsideOtherVersion(file);
        assertTrue(Files.exists(file), "a file of this version stays");
    }

    private static byte[] header(final short version) {
        return ByteConversion.shortToByte(version, new byte[4], 0);
    }

    private static VectorStore.Entry entry(final float first) {
        return new VectorStore.Entry(0, 0, 1, 1, new float[] {first, 0, 0});
    }

    private interface InTransaction {
        void accept(Txn txn) throws Exception;
    }

    private void inTransaction(final InTransaction work) throws Exception {
        final TransactionManager mgr = pool.getTransactionManager();
        try (final Txn txn = mgr.beginTransaction()) {
            work.accept(txn);
            mgr.commit(txn);
        }
    }
}
