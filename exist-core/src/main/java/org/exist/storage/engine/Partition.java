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
import java.util.function.BiConsumer;

/**
 * A named partition within a storage engine. Analogous to an LMDB named
 * database or a RocksDB column family. All operations require an active
 * transaction.
 */
public interface Partition {

    /**
     * @return the partition name
     */
    String getName();

    /**
     * Get a value by key within a read transaction.
     *
     * @param txn the read transaction
     * @param key the key bytes
     * @return the value bytes, or null if not found
     * @throws StorageException on read error
     */
    @Nullable
    byte[] get(ReadTransaction txn, byte[] key) throws StorageException;

    /**
     * Get a value by key within a write transaction (supports read-your-own-writes).
     *
     * @param txn the write transaction
     * @param key the key bytes
     * @return the value bytes, or null if not found
     * @throws StorageException on read error
     */
    @Nullable
    byte[] get(WriteTransaction txn, byte[] key) throws StorageException;

    /**
     * Put a key-value pair within a write transaction.
     *
     * @param txn   the write transaction
     * @param key   the key bytes
     * @param value the value bytes
     * @throws StorageException on write error
     */
    void put(WriteTransaction txn, byte[] key, byte[] value) throws StorageException;

    /**
     * Delete a key within a write transaction.
     *
     * @param txn the write transaction
     * @param key the key bytes
     * @throws StorageException on write error
     */
    void delete(WriteTransaction txn, byte[] key) throws StorageException;

    /**
     * Scan all key-value pairs in order within a read transaction.
     *
     * @param txn     the read transaction
     * @param visitor called for each key-value pair
     * @throws StorageException on read error
     */
    void scan(ReadTransaction txn, BiConsumer<byte[], byte[]> visitor) throws StorageException;

    /**
     * Scan key-value pairs in a range [startKey, endKey) within a read transaction.
     *
     * @param txn      the read transaction
     * @param startKey the start key (inclusive)
     * @param endKey   the end key (exclusive), or null for unbounded
     * @param visitor  called for each key-value pair
     * @throws StorageException on read error
     */
    void scan(ReadTransaction txn, byte[] startKey, @Nullable byte[] endKey,
              BiConsumer<byte[], byte[]> visitor) throws StorageException;
}
