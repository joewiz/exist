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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * RocksDB-backed storage engine implementation.
 *
 * <p>Uses column families as partitions (dom, collections, symbols).
 * Transactions use WriteBatchWithIndex for read-your-own-writes support
 * and snapshots for read isolation.</p>
 */
public class RocksDbStorageEngine implements StorageEngine {

    private static final String[] PARTITION_NAMES = {"dom", "collections", "symbols"};
    private static final long BLOCK_CACHE_SIZE = 64 * 1024 * 1024; // 64MB for PoC

    private RocksDB db;
    private DBOptions dbOptions;
    private Cache blockCache;
    private ColumnFamilyOptions cfOptions;
    private final Map<String, ColumnFamilyHandle> cfHandles = new LinkedHashMap<>();
    private final List<ColumnFamilyHandle> cfHandleList = new ArrayList<>();
    private WriteOptions syncWriteOptions;

    @Override
    public void open(final Path dataDir) throws StorageException {
        RocksDB.loadLibrary();

        try {
            // Configure block cache — store reference for cleanup
            blockCache = new LRUCache(BLOCK_CACHE_SIZE);
            final BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
                    .setBlockCache(blockCache);

            cfOptions = new ColumnFamilyOptions()
                    .setTableFormatConfig(tableConfig);

            // Build column family descriptors
            final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
            // Default CF is required by RocksDB
            cfDescriptors.add(new ColumnFamilyDescriptor(
                    RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));
            for (final String name : PARTITION_NAMES) {
                cfDescriptors.add(new ColumnFamilyDescriptor(
                        name.getBytes(StandardCharsets.UTF_8), cfOptions));
            }

            dbOptions = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true);

            syncWriteOptions = new WriteOptions().setSync(true);

            // Open database
            db = RocksDB.open(dbOptions, dataDir.toString(), cfDescriptors, cfHandleList);

            // Map handles by name
            cfHandles.put("default", cfHandleList.get(0));
            for (int i = 0; i < PARTITION_NAMES.length; i++) {
                cfHandles.put(PARTITION_NAMES[i], cfHandleList.get(i + 1));
            }

        } catch (final RocksDBException e) {
            throw new StorageException("Failed to open RocksDB: " + e.getMessage(), e);
        }
    }

    @Override
    public Partition getPartition(final String name) throws StorageException {
        final ColumnFamilyHandle handle = cfHandles.get(name);
        if (handle == null) {
            throw new StorageException("Partition not found: " + name);
        }
        return new RocksDbPartition(name, db, handle);
    }

    @Override
    public ReadTransaction beginReadTransaction() throws StorageException {
        if (db == null) {
            throw new StorageException("Database not open");
        }
        final Snapshot snapshot = db.getSnapshot();
        return new RocksDbReadTransaction(db, snapshot);
    }

    @Override
    public WriteTransaction beginWriteTransaction() throws StorageException {
        if (db == null) {
            throw new StorageException("Database not open");
        }
        final Snapshot snapshot = db.getSnapshot();
        final WriteBatchWithIndex writeBatch = new WriteBatchWithIndex(true);
        return new RocksDbWriteTransaction(db, writeBatch, snapshot, syncWriteOptions);
    }

    @Override
    public void close() {
        for (final ColumnFamilyHandle handle : cfHandleList) {
            handle.close();
        }
        cfHandleList.clear();
        cfHandles.clear();

        if (syncWriteOptions != null) {
            syncWriteOptions.close();
            syncWriteOptions = null;
        }
        if (db != null) {
            db.close();
            db = null;
        }
        if (dbOptions != null) {
            dbOptions.close();
            dbOptions = null;
        }
        if (cfOptions != null) {
            cfOptions.close();
            cfOptions = null;
        }
        if (blockCache != null) {
            blockCache.close();
            blockCache = null;
        }
    }
}
