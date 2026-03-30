package org.exist.storage.lmdb;

import org.exist.storage.engine.StorageException;
import org.exist.storage.engine.WriteTransaction;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

public class LmdbWriteTransaction implements WriteTransaction {
    final Txn<ByteBuffer> txn;
    private final ReentrantLock writeLock;
    private boolean finished = false;

    LmdbWriteTransaction(final Txn<ByteBuffer> txn, final ReentrantLock writeLock) {
        this.txn = txn;
        this.writeLock = writeLock;
    }

    @Override
    public void commit() throws StorageException {
        if (finished) {
            return;
        }
        try {
            txn.commit();
            finished = true;
        } catch (final Exception e) {
            throw new StorageException("LMDB commit failed", e);
        }
    }

    @Override
    public void abort() {
        if (!finished) {
            txn.abort();
            finished = true;
        }
    }

    @Override
    public void close() {
        try {
            if (!finished) {
                txn.abort();
                finished = true;
            }
            txn.close();
        } finally {
            writeLock.unlock();
        }
    }
}
