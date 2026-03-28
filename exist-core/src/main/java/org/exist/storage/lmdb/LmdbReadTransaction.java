package org.exist.storage.lmdb;

import org.exist.storage.engine.ReadTransaction;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;

public class LmdbReadTransaction implements ReadTransaction {
    final Txn<ByteBuffer> txn;

    LmdbReadTransaction(final Txn<ByteBuffer> txn) {
        this.txn = txn;
    }

    @Override
    public void close() {
        txn.close();
    }
}
