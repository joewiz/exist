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

import org.exist.storage.engine.ReadTransaction;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Snapshot;

/**
 * A read-only RocksDB transaction backed by a snapshot.
 * All reads see a consistent point-in-time view of the database.
 */
public class RocksDbReadTransaction implements ReadTransaction {

    private final RocksDB db;
    private final Snapshot snapshot;
    private final ReadOptions readOptions;

    RocksDbReadTransaction(final RocksDB db, final Snapshot snapshot) {
        this.db = db;
        this.snapshot = snapshot;
        this.readOptions = new ReadOptions().setSnapshot(snapshot);
    }

    ReadOptions getReadOptions() {
        return readOptions;
    }

    @Override
    public void close() {
        readOptions.close();
        db.releaseSnapshot(snapshot);
    }
}
