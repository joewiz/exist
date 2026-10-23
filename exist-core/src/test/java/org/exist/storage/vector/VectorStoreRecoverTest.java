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

import org.exist.numbering.NodeIdFactory;
import org.exist.storage.BrokerPool;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that the vector store's changes are recovered after an unclean shutdown: {@code vector.dbx}
 * is recoverable, so its pages are journaled, and recovery must redo the committed changes and undo
 * the uncommitted ones.
 */
public class VectorStoreRecoverTest {

    /** 384 dimensions of 4-byte floats, as all-MiniLM-L6-v2 produces: enough entries fill several pages. */
    private static final int VECTOR_BYTES = 384 * Float.BYTES;
    private static final String FIELD = "embedding";
    private static final int COMMITTED = 300;
    private static final int UNCOMMITTED = 100;

    @RegisterExtension
    public final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    @AfterEach
    void resetCorruption() {
        BrokerPool.FORCE_CORRUPTION = false;
    }

    @Test
    void committedVectorsAreRecovered() throws Exception {
        BrokerPool.FORCE_CORRUPTION = true;
        put(1, COMMITTED, true);

        // without a checkpoint at shutdown, the restart has to recover from the journal
        existEmbeddedServer.restart();
        BrokerPool.FORCE_CORRUPTION = false;

        assertStored(1, COMMITTED);
    }

    @Test
    void uncommittedVectorsAreRolledBack() throws Exception {
        BrokerPool.FORCE_CORRUPTION = true;
        put(1, COMMITTED, true);
        put(COMMITTED + 1, COMMITTED + UNCOMMITTED, false);
        existEmbeddedServer.getBrokerPool().getJournalManager().get().flush(true, false);

        existEmbeddedServer.restart();
        BrokerPool.FORCE_CORRUPTION = false;

        assertStored(1, COMMITTED);
        final VectorStore store = existEmbeddedServer.getBrokerPool().getVectorStore();
        final NodeIdFactory nodeIds = existEmbeddedServer.getBrokerPool().getNodeFactory();
        for (int i = COMMITTED + 1; i <= COMMITTED + UNCOMMITTED; i++) {
            assertNull(store.get(i, nodeIds.createInstance(1), FIELD, 0), "uncommitted vector " + i);
        }
    }

    /**
     * Stores the vectors {@code from} to {@code to}, committing the transaction or, for an
     * uncommitted one, leaving it open.
     */
    private void put(final int from, final int to, final boolean commit) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final TransactionManager transactionManager = pool.getTransactionManager();
        final VectorStore store = pool.getVectorStore();
        final NodeIdFactory nodeIds = pool.getNodeFactory();

        final Txn txn = transactionManager.beginTransaction();
        for (int i = from; i <= to; i++) {
            store.put(txn, i, nodeIds.createInstance(1), FIELD, 0, new VectorStore.Entry(0, 0, i, 1, vector(i)));
        }
        if (commit) {
            transactionManager.commit(txn);
            txn.close();
        }
    }

    private void assertStored(final int from, final int to) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final VectorStore store = pool.getVectorStore();
        final NodeIdFactory nodeIds = pool.getNodeFactory();
        for (int i = from; i <= to; i++) {
            final VectorStore.Entry entry = store.get(i, nodeIds.createInstance(1), FIELD, 0);
            assertNotNull(entry, "vector " + i);
            assertArrayEquals(vector(i), entry.vector(), "vector " + i);
        }
    }

    private static float[] vector(final int i) {
        final float[] vector = new float[VECTOR_BYTES / Float.BYTES];
        for (int d = 0; d < vector.length; d++) {
            vector[d] = i + d / 1000f;
        }
        return vector;
    }
}
