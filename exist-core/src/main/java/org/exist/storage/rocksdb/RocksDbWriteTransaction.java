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

import org.exist.storage.engine.StorageException;
import org.exist.storage.engine.WriteTransaction;
import org.rocksdb.*;

/**
 * A read-write RocksDB transaction backed by WriteBatchWithIndex.
 *
 * <p>Uses WriteBatchWithIndex (not plain WriteBatch) for read-your-own-writes:
 * getFromBatchAndDB() checks the batch first, then falls back to the snapshot.
 * Commits are atomic via db.write(batch).</p>
 */
public class RocksDbWriteTransaction implements WriteTransaction {

    private final RocksDB db;
    private final WriteBatchWithIndex writeBatch;
    private final Snapshot snapshot;
    private final ReadOptions readOptions;
    private final WriteOptions writeOptions;
    private boolean committed = false;

    RocksDbWriteTransaction(final RocksDB db, final WriteBatchWithIndex writeBatch,
                            final Snapshot snapshot, final WriteOptions writeOptions) {
        this.db = db;
        this.writeBatch = writeBatch;
        this.snapshot = snapshot;
        this.readOptions = new ReadOptions().setSnapshot(snapshot);
        this.writeOptions = writeOptions;
    }

    WriteBatchWithIndex getWriteBatch() {
        return writeBatch;
    }

    ReadOptions getReadOptions() {
        return readOptions;
    }

    @Override
    public void commit() throws StorageException {
        if (committed) {
            throw new StorageException("Transaction already committed");
        }
        try {
            db.write(writeOptions, writeBatch);
            committed = true;
        } catch (final RocksDBException e) {
            throw new StorageException("Failed to commit transaction", e);
        }
    }

    @Override
    public void abort() {
        // WriteBatch is discarded (not written to DB) — close releases native memory
        if (!committed) {
            writeBatch.close();
            committed = true; // prevent double-close
        }
    }

    @Override
    public void close() {
        try {
            // Always close writeBatch — even after commit, native memory must be released
            writeBatch.close();
        } finally {
            readOptions.close();
            db.releaseSnapshot(snapshot);
        }
    }
}
