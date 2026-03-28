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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract test suite for StorageEngine implementations.
 * Subclasses provide the engine and temp directory.
 */
public abstract class StorageEngineTest {

    protected abstract StorageEngine createEngine();
    protected abstract Path getDataDir();

    private StorageEngine engine;

    @BeforeEach
    void setUp() throws StorageException {
        engine = createEngine();
        engine.open(getDataDir());
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void testStoreAndRetrieve() throws StorageException {
        final Partition dom = engine.getPartition("dom");
        final byte[] key = "doc1|node1".getBytes(StandardCharsets.UTF_8);
        final byte[] value = "<root>Hello</root>".getBytes(StandardCharsets.UTF_8);

        // Write
        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.put(wtxn, key, value);
            wtxn.commit();
        }

        // Read back
        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            final byte[] result = dom.get(rtxn, key);
            assertNotNull(result);
            assertArrayEquals(value, result);
        }
    }

    @Test
    void testGetNonexistentKey() throws StorageException {
        final Partition dom = engine.getPartition("dom");
        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            final byte[] result = dom.get(rtxn, "nonexistent".getBytes(StandardCharsets.UTF_8));
            assertNull(result);
        }
    }

    @Test
    void testDelete() throws StorageException {
        final Partition dom = engine.getPartition("dom");
        final byte[] key = "toDelete".getBytes(StandardCharsets.UTF_8);
        final byte[] value = "data".getBytes(StandardCharsets.UTF_8);

        // Write then delete
        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.put(wtxn, key, value);
            wtxn.commit();
        }
        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.delete(wtxn, key);
            wtxn.commit();
        }

        // Verify deleted
        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            assertNull(dom.get(rtxn, key));
        }
    }

    @Test
    void testMultiplePartitions() throws StorageException {
        final Partition dom = engine.getPartition("dom");
        final Partition collections = engine.getPartition("collections");
        final Partition symbols = engine.getPartition("symbols");

        final byte[] key = "key1".getBytes(StandardCharsets.UTF_8);

        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.put(wtxn, key, "dom-value".getBytes(StandardCharsets.UTF_8));
            collections.put(wtxn, key, "coll-value".getBytes(StandardCharsets.UTF_8));
            symbols.put(wtxn, key, "sym-value".getBytes(StandardCharsets.UTF_8));
            wtxn.commit();
        }

        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            assertEquals("dom-value", new String(dom.get(rtxn, key), StandardCharsets.UTF_8));
            assertEquals("coll-value", new String(collections.get(rtxn, key), StandardCharsets.UTF_8));
            assertEquals("sym-value", new String(symbols.get(rtxn, key), StandardCharsets.UTF_8));
        }
    }

    @Test
    void testScan() throws StorageException {
        final Partition dom = engine.getPartition("dom");

        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.put(wtxn, new byte[]{1}, "a".getBytes(StandardCharsets.UTF_8));
            dom.put(wtxn, new byte[]{2}, "b".getBytes(StandardCharsets.UTF_8));
            dom.put(wtxn, new byte[]{3}, "c".getBytes(StandardCharsets.UTF_8));
            wtxn.commit();
        }

        final List<String> values = new ArrayList<>();
        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            dom.scan(rtxn, (k, v) -> values.add(new String(v, StandardCharsets.UTF_8)));
        }

        assertEquals(3, values.size());
        assertEquals("a", values.get(0));
        assertEquals("b", values.get(1));
        assertEquals("c", values.get(2));
    }

    @Test
    void testRangeScan() throws StorageException {
        final Partition dom = engine.getPartition("dom");

        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.put(wtxn, new byte[]{1}, "a".getBytes(StandardCharsets.UTF_8));
            dom.put(wtxn, new byte[]{2}, "b".getBytes(StandardCharsets.UTF_8));
            dom.put(wtxn, new byte[]{3}, "c".getBytes(StandardCharsets.UTF_8));
            dom.put(wtxn, new byte[]{4}, "d".getBytes(StandardCharsets.UTF_8));
            wtxn.commit();
        }

        final List<String> values = new ArrayList<>();
        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            dom.scan(rtxn, new byte[]{2}, new byte[]{4}, (k, v) ->
                    values.add(new String(v, StandardCharsets.UTF_8)));
        }

        assertEquals(2, values.size());
        assertEquals("b", values.get(0));
        assertEquals("c", values.get(1));
    }

    @Test
    void testAbortDiscards() throws StorageException {
        final Partition dom = engine.getPartition("dom");
        final byte[] key = "aborted".getBytes(StandardCharsets.UTF_8);

        try (final WriteTransaction wtxn = engine.beginWriteTransaction()) {
            dom.put(wtxn, key, "value".getBytes(StandardCharsets.UTF_8));
            wtxn.abort();
        }

        try (final ReadTransaction rtxn = engine.beginReadTransaction()) {
            assertNull(dom.get(rtxn, key));
        }
    }

    @Test
    void testUnknownPartitionThrows() {
        assertThrows(StorageException.class, () -> engine.getPartition("nonexistent"));
    }
}
