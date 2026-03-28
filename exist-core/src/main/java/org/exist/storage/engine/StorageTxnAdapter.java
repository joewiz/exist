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

/**
 * Maps eXist's transaction model to the StorageEngine transaction model.
 *
 * <p>eXist's NativeBroker uses Txn objects for transaction management.
 * This adapter bridges between Txn and the StorageEngine's ReadTransaction
 * / WriteTransaction abstractions.</p>
 *
 * <p>Thread-local storage tracks the active transaction per broker thread.
 * Write transactions support read-your-own-writes; read transactions provide
 * snapshot isolation.</p>
 */
public class StorageTxnAdapter {

    private final StorageEngine engine;

    // Active transaction per thread
    private final ThreadLocal<ReadTransaction> activeReadTxn = new ThreadLocal<>();
    private final ThreadLocal<WriteTransaction> activeWriteTxn = new ThreadLocal<>();

    public StorageTxnAdapter(final StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Begin a read-only transaction with snapshot isolation.
     *
     * @return the read transaction
     * @throws StorageException if the transaction cannot be started
     */
    public ReadTransaction beginRead() throws StorageException {
        final ReadTransaction txn = engine.beginReadTransaction();
        activeReadTxn.set(txn);
        return txn;
    }

    /**
     * Begin a read-write transaction.
     *
     * @return the write transaction
     * @throws StorageException if the transaction cannot be started
     */
    public WriteTransaction beginWrite() throws StorageException {
        final WriteTransaction txn = engine.beginWriteTransaction();
        activeWriteTxn.set(txn);
        return txn;
    }

    /**
     * Get the active read transaction for this thread, or null.
     */
    @Nullable
    public ReadTransaction getActiveReadTransaction() {
        // Write transactions also serve as read transactions (read-your-own-writes)
        final WriteTransaction wtxn = activeWriteTxn.get();
        if (wtxn != null) {
            return null; // caller should use getActiveWriteTransaction instead
        }
        return activeReadTxn.get();
    }

    /**
     * Get the active write transaction for this thread, or null.
     */
    @Nullable
    public WriteTransaction getActiveWriteTransaction() {
        return activeWriteTxn.get();
    }

    /**
     * Check if the current thread is in a write transaction.
     */
    public boolean isWriteTransaction() {
        return activeWriteTxn.get() != null;
    }

    /**
     * Commit the active write transaction.
     *
     * @throws StorageException if the commit fails
     */
    public void commit() throws StorageException {
        final WriteTransaction txn = activeWriteTxn.get();
        if (txn != null) {
            txn.commit();
            txn.close();
            activeWriteTxn.remove();
        }
    }

    /**
     * Abort the active write transaction.
     */
    public void abort() {
        final WriteTransaction txn = activeWriteTxn.get();
        if (txn != null) {
            txn.abort();
            txn.close();
            activeWriteTxn.remove();
        }
    }

    /**
     * End the active read transaction.
     */
    public void endRead() {
        final ReadTransaction txn = activeReadTxn.get();
        if (txn != null) {
            txn.close();
            activeReadTxn.remove();
        }
    }

    /**
     * Read from a partition, routing through write transaction if active
     * (for read-your-own-writes), otherwise through read transaction.
     *
     * @param partition the partition to read from
     * @param key the key
     * @return the value, or null
     * @throws StorageException on read error
     */
    @Nullable
    public byte[] get(final Partition partition, final byte[] key) throws StorageException {
        final WriteTransaction wtxn = activeWriteTxn.get();
        if (wtxn != null) {
            return partition.get(wtxn, key);
        }
        final ReadTransaction rtxn = activeReadTxn.get();
        if (rtxn != null) {
            return partition.get(rtxn, key);
        }
        // No transaction — auto-read
        try (final ReadTransaction autoTxn = engine.beginReadTransaction()) {
            return partition.get(autoTxn, key);
        }
    }

    /**
     * Write to a partition within the active write transaction.
     *
     * @throws StorageException if no write transaction is active
     */
    public void put(final Partition partition, final byte[] key, final byte[] value) throws StorageException {
        final WriteTransaction wtxn = activeWriteTxn.get();
        if (wtxn == null) {
            throw new StorageException("No active write transaction");
        }
        partition.put(wtxn, key, value);
    }

    /**
     * Delete from a partition within the active write transaction.
     *
     * @throws StorageException if no write transaction is active
     */
    public void delete(final Partition partition, final byte[] key) throws StorageException {
        final WriteTransaction wtxn = activeWriteTxn.get();
        if (wtxn == null) {
            throw new StorageException("No active write transaction");
        }
        partition.delete(wtxn, key);
    }
}
