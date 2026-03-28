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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RocksDB-specific storage engine tests. Inherits engine-agnostic tests
 * from StorageEngineTest and adds RocksDB-specific behavior tests.
 */
public class RocksDbStorageEngineTest extends StorageEngineTest {

    @TempDir
    Path tempDir;

    @Override
    protected StorageEngine createEngine() {
        return new RocksDbStorageEngine();
    }

    protected Path getDataDir() {
        return tempDir;
    }

    @Test
    void testReadYourOwnWrites() throws StorageException {
        final StorageEngine engine = createEngine();
        engine.open(tempDir.resolve("ryow"));
        try {
            final Partition dom = engine.getPartition("dom");
            final byte[] key = "ryow-key".getBytes(StandardCharsets.UTF_8);
            final byte[] value = "ryow-value".getBytes(StandardCharsets.UTF_8);

            try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
                dom.put(wtxn, key, value);

                // Read within the same write transaction — should see the uncommitted write
                final byte[] result = dom.get(wtxn, key);
                assertNotNull(result, "Read-your-own-writes: uncommitted write should be visible");
                assertArrayEquals(value, result);

                wtxn.commit();
            }
        } finally {
            engine.close();
        }
    }

    @Test
    void testSnapshotIsolation() throws StorageException {
        final StorageEngine engine = createEngine();
        engine.open(tempDir.resolve("isolation"));
        try {
            final Partition dom = engine.getPartition("dom");
            final byte[] key = "iso-key".getBytes(StandardCharsets.UTF_8);
            final byte[] value = "iso-value".getBytes(StandardCharsets.UTF_8);

            // Start a read transaction BEFORE the write
            try (final ReadTransaction rtxn = engine.beginReadTransaction()) {

                // Write and commit in a separate transaction
                try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
                    dom.put(wtxn, key, value);
                    wtxn.commit();
                }

                // The read transaction (started before the write) should NOT see the write
                final byte[] result = dom.get(rtxn, key);
                assertNull(result, "Snapshot isolation: read txn started before write should not see it");
            }

            // A NEW read transaction should see the committed write
            try (final ReadTransaction rtxn2 = engine.beginReadTransaction()) {
                final byte[] result = dom.get(rtxn2, key);
                assertNotNull(result, "New read txn should see committed write");
                assertArrayEquals(value, result);
            }
        } finally {
            engine.close();
        }
    }
}
