package org.exist.storage.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract engine-agnostic test suite for StorageEngine implementations.
 * Subclasses provide the concrete engine via {@link #createEngine()}.
 */
public abstract class StorageEngineTest {

    @TempDir
    Path tempDir;

    private StorageEngine engine;

    protected abstract StorageEngine createEngine();

    @BeforeEach
    void setUp() throws StorageException {
        engine = createEngine();
        engine.open(tempDir, new StorageConfig().setMapSize(64 * 1024 * 1024)); // 64MB for tests
    }

    @AfterEach
    void tearDown() throws StorageException {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void testStoreAndRetrieveDocument() {
        final Partition dom = engine.getPartition("dom");
        final byte[] docXml = "<root><child attr=\"val\">text</child></root>".getBytes(StandardCharsets.UTF_8);

        // Store: docId=1, nodeId=1 → document bytes
        final byte[] key = makeKey(1, 1);

        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            dom.put(wtx, key, docXml);
            wtx.commit();
        } catch (final StorageException e) {
            fail("Write transaction failed: " + e.getMessage());
        }

        // Retrieve
        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            final byte[] retrieved = dom.get(rtx, key);
            assertNotNull(retrieved, "Document should be retrievable after store");
            assertArrayEquals(docXml, retrieved, "Retrieved bytes should be identical");
        }
    }

    @Test
    void testCollectionCRUD() {
        final Partition collections = engine.getPartition("collections");

        final byte[] collKey = collectionKey("/db/test");
        final byte[] collMeta = "collection-metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] docKey = documentEntryKey("/db/test", "doc1.xml");
        final byte[] docMeta = "document-metadata".getBytes(StandardCharsets.UTF_8);

        // Create collection and add document entry
        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            collections.put(wtx, collKey, collMeta);
            collections.put(wtx, docKey, docMeta);
            wtx.commit();
        } catch (final StorageException e) {
            fail(e.getMessage());
        }

        // Read back
        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            assertNotNull(collections.get(rtx, collKey), "Collection metadata should exist");
            assertNotNull(collections.get(rtx, docKey), "Document entry should exist");
        }

        // List collection contents via scan
        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            final byte[] scanStart = documentEntryPrefix("/db/test");
            final byte[] scanEnd = documentEntryPrefixEnd("/db/test");
            final List<byte[]> keys = new ArrayList<>();
            try (final CloseableIterator iter = collections.scan(rtx, scanStart, scanEnd)) {
                while (iter.hasNext()) {
                    final Map.Entry<byte[], byte[]> entry = iter.next();
                    keys.add(entry.getKey());
                }
            }
            assertEquals(1, keys.size(), "Should find 1 document in collection");
        }

        // Delete document, verify gone
        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            collections.delete(wtx, docKey);
            wtx.commit();
        } catch (final StorageException e) {
            fail(e.getMessage());
        }

        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            assertNull(collections.get(rtx, docKey), "Document entry should be deleted");
            assertNotNull(collections.get(rtx, collKey), "Collection metadata should still exist");
        }
    }

    @Test
    void testSymbolTableRoundTrip() {
        final Partition symbols = engine.getPartition("symbols");

        // Forward mapping: "title" → ID 42
        final byte[] forwardKey = symbolKey((byte) 0x01, (byte) 0x01, "title");
        final byte[] forwardVal = shortToBytes((short) 42);

        // Reverse mapping: ID 42 → "title"
        final byte[] reverseKey = symbolKey((byte) 0x01, (byte) 0x02, shortToBytes((short) 42));
        final byte[] reverseVal = "title".getBytes(StandardCharsets.UTF_8);

        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            symbols.put(wtx, forwardKey, forwardVal);
            symbols.put(wtx, reverseKey, reverseVal);
            wtx.commit();
        } catch (final StorageException e) {
            fail(e.getMessage());
        }

        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            final byte[] id = symbols.get(rtx, forwardKey);
            assertNotNull(id);
            assertEquals(42, bytesToShort(id));

            final byte[] name = symbols.get(rtx, reverseKey);
            assertNotNull(name);
            assertEquals("title", new String(name, StandardCharsets.UTF_8));
        }
    }

    @Test
    void testReadTransactionIsolation() {
        final Partition dom = engine.getPartition("dom");
        final byte[] key = makeKey(1, 1);
        final byte[] value = "original".getBytes(StandardCharsets.UTF_8);

        // Store initial value
        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            dom.put(wtx, key, value);
            wtx.commit();
        } catch (final StorageException e) {
            fail(e.getMessage());
        }

        // Start a read transaction — this should see the original value
        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            // Now write a new value in a separate write transaction
            try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
                dom.put(wtx, key, "updated".getBytes(StandardCharsets.UTF_8));
                wtx.commit();
            } catch (final StorageException e) {
                fail(e.getMessage());
            }

            // The read transaction should still see the original value (snapshot isolation)
            final byte[] readValue = dom.get(rtx, key);
            assertNotNull(readValue);
            assertEquals("original", new String(readValue, StandardCharsets.UTF_8),
                    "Read transaction should see snapshot, not uncommitted write");
        }
    }

    @Test
    void testCrashRecovery() throws StorageException {
        final Partition dom = engine.getPartition("dom");
        final byte[] key = makeKey(1, 1);
        final byte[] value = "committed".getBytes(StandardCharsets.UTF_8);

        // Commit a value
        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            dom.put(wtx, key, value);
            wtx.commit();
        }

        // Begin a write but DON'T commit — simulate crash by closing engine
        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
            dom.put(wtx, key, "uncommitted-crash".getBytes(StandardCharsets.UTF_8));
            // No commit — abort via close
            wtx.abort();
        }

        // Close and reopen
        engine.close();
        engine = createEngine();
        engine.open(tempDir, new StorageConfig().setMapSize(64 * 1024 * 1024));

        // Should see committed value, not the aborted write
        final Partition dom2 = engine.getPartition("dom");
        try (final ReadTransaction rtx = engine.beginReadTransaction()) {
            final byte[] readValue = dom2.get(rtx, key);
            assertNotNull(readValue);
            assertEquals("committed", new String(readValue, StandardCharsets.UTF_8),
                    "After reopen, only committed data should be visible");
        }
    }

    // --- Key construction helpers ---

    static byte[] makeKey(final int docId, final int nodeId) {
        final ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(docId);
        buf.putInt(nodeId);
        return buf.array();
    }

    static byte[] collectionKey(final String uri) {
        final byte[] uriBytes = uri.getBytes(StandardCharsets.UTF_8);
        final byte[] key = new byte[1 + uriBytes.length];
        key[0] = 0x01;
        System.arraycopy(uriBytes, 0, key, 1, uriBytes.length);
        return key;
    }

    static byte[] documentEntryKey(final String collectionUri, final String docName) {
        final byte[] colBytes = collectionUri.getBytes(StandardCharsets.UTF_8);
        final byte[] docBytes = docName.getBytes(StandardCharsets.UTF_8);
        final byte[] key = new byte[1 + colBytes.length + 1 + docBytes.length];
        key[0] = 0x02;
        System.arraycopy(colBytes, 0, key, 1, colBytes.length);
        key[1 + colBytes.length] = 0x00; // separator
        System.arraycopy(docBytes, 0, key, 2 + colBytes.length, docBytes.length);
        return key;
    }

    static byte[] documentEntryPrefix(final String collectionUri) {
        final byte[] colBytes = collectionUri.getBytes(StandardCharsets.UTF_8);
        final byte[] prefix = new byte[1 + colBytes.length + 1];
        prefix[0] = 0x02;
        System.arraycopy(colBytes, 0, prefix, 1, colBytes.length);
        prefix[1 + colBytes.length] = 0x00;
        return prefix;
    }

    static byte[] documentEntryPrefixEnd(final String collectionUri) {
        final byte[] colBytes = collectionUri.getBytes(StandardCharsets.UTF_8);
        final byte[] prefix = new byte[1 + colBytes.length + 1];
        prefix[0] = 0x02;
        System.arraycopy(colBytes, 0, prefix, 1, colBytes.length);
        prefix[1 + colBytes.length] = 0x01; // one past separator
        return prefix;
    }

    static byte[] symbolKey(final byte type, final byte direction, final String name) {
        final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        final byte[] key = new byte[2 + nameBytes.length];
        key[0] = type;
        key[1] = direction;
        System.arraycopy(nameBytes, 0, key, 2, nameBytes.length);
        return key;
    }

    static byte[] symbolKey(final byte type, final byte direction, final byte[] data) {
        final byte[] key = new byte[2 + data.length];
        key[0] = type;
        key[1] = direction;
        System.arraycopy(data, 0, key, 2, data.length);
        return key;
    }

    static byte[] shortToBytes(final short val) {
        return ByteBuffer.allocate(2).putShort(val).array();
    }

    static short bytesToShort(final byte[] bytes) {
        return ByteBuffer.wrap(bytes).getShort();
    }
}
