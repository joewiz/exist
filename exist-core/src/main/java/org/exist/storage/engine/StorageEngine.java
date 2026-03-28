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

import java.io.Closeable;
import java.nio.file.Path;

/**
 * Abstract storage engine interface for eXist-db.
 * Implementations provide the underlying key-value store (LMDB, RocksDB, etc.).
 *
 * <p>The engine manages named partitions (analogous to LMDB databases or RocksDB
 * column families) and provides transactional read/write access.</p>
 */
public interface StorageEngine extends Closeable {

    /**
     * Open the storage engine at the given path, creating it if necessary.
     *
     * @param dataDir the directory for storage files
     * @throws StorageException if the engine cannot be opened
     */
    void open(Path dataDir) throws StorageException;

    /**
     * Get a named partition (column family / sub-database).
     *
     * @param name the partition name
     * @return the partition handle
     * @throws StorageException if the partition doesn't exist
     */
    Partition getPartition(String name) throws StorageException;

    /**
     * Begin a read-only transaction with snapshot isolation.
     *
     * @return a read transaction
     * @throws StorageException if the transaction cannot be started
     */
    ReadTransaction beginReadTransaction() throws StorageException;

    /**
     * Begin a read-write transaction.
     *
     * @return a write transaction
     * @throws StorageException if the transaction cannot be started
     */
    WriteTransaction beginWriteTransaction() throws StorageException;

    /**
     * Close the storage engine, releasing all resources.
     */
    @Override
    void close();
}
