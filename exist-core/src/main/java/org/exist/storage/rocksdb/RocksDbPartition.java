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
import org.rocksdb.*;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * A RocksDB column family as a storage partition.
 */
public class RocksDbPartition implements Partition {

    private final String name;
    private final RocksDB db;
    private final ColumnFamilyHandle cfHandle;

    RocksDbPartition(final String name, final RocksDB db, final ColumnFamilyHandle cfHandle) {
        this.name = name;
        this.db = db;
        this.cfHandle = cfHandle;
    }

    @Override
    public String getName() {
        return name;
    }

    ColumnFamilyHandle getHandle() {
        return cfHandle;
    }

    @Override
    @Nullable
    public byte[] get(final ReadTransaction txn, final byte[] key) throws StorageException {
        final RocksDbReadTransaction rtxn = (RocksDbReadTransaction) txn;
        try {
            return db.get(cfHandle, rtxn.getReadOptions(), key);
        } catch (final RocksDBException e) {
            throw new StorageException("Failed to read from partition " + name, e);
        }
    }

    @Override
    @Nullable
    public byte[] get(final WriteTransaction txn, final byte[] key) throws StorageException {
        final RocksDbWriteTransaction wtxn = (RocksDbWriteTransaction) txn;
        try {
            // Critical: getFromBatchAndDB checks the write batch first, then the snapshot.
            // This provides read-your-own-writes semantics.
            return wtxn.getWriteBatch().getFromBatchAndDB(
                    db, cfHandle, wtxn.getReadOptions(), key);
        } catch (final RocksDBException e) {
            throw new StorageException("Failed to read from partition " + name, e);
        }
    }

    @Override
    public void put(final WriteTransaction txn, final byte[] key, final byte[] value) throws StorageException {
        final RocksDbWriteTransaction wtxn = (RocksDbWriteTransaction) txn;
        try {
            wtxn.getWriteBatch().put(cfHandle, key, value);
        } catch (final RocksDBException e) {
            throw new StorageException("Failed to write to partition " + name, e);
        }
    }

    @Override
    public void delete(final WriteTransaction txn, final byte[] key) throws StorageException {
        final RocksDbWriteTransaction wtxn = (RocksDbWriteTransaction) txn;
        try {
            wtxn.getWriteBatch().delete(cfHandle, key);
        } catch (final RocksDBException e) {
            throw new StorageException("Failed to delete from partition " + name, e);
        }
    }

    @Override
    public void scan(final ReadTransaction txn, final BiConsumer<byte[], byte[]> visitor) throws StorageException {
        final RocksDbReadTransaction rtxn = (RocksDbReadTransaction) txn;
        try (final RocksIterator iter = db.newIterator(cfHandle, rtxn.getReadOptions())) {
            iter.seekToFirst();
            while (iter.isValid()) {
                visitor.accept(iter.key(), iter.value());
                iter.next();
            }
        }
    }

    @Override
    public void scan(final ReadTransaction txn, final byte[] startKey, @Nullable final byte[] endKey,
                     final BiConsumer<byte[], byte[]> visitor) throws StorageException {
        final RocksDbReadTransaction rtxn = (RocksDbReadTransaction) txn;
        try (final RocksIterator iter = db.newIterator(cfHandle, rtxn.getReadOptions())) {
            iter.seek(startKey);
            while (iter.isValid()) {
                final byte[] key = iter.key();
                if (endKey != null && Arrays.compareUnsigned(key, endKey) >= 0) {
                    break;
                }
                visitor.accept(key, iter.value());
                iter.next();
            }
        }
    }
}
