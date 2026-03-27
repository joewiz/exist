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
package org.exist.storage;

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.CollectionManagementService;
import org.xmldb.api.modules.XMLResource;
import org.xmldb.api.modules.XQueryService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Concurrency tests that demonstrate data corruption under concurrent writes.
 *
 * These tests reproduce the scenario reported by Alexander Henket (Slack, 2026-03-27):
 * "When 4-6 users do more or less heavy writing in the same resource...
 *  data meant for one attribute ends up in another."
 *
 * Phase 1: Run on develop (no preclaiming) to establish baseline corruption rate.
 * Phase 2: Cherry-pick PR #6112 to validate preclaiming prevents corruption.
 */
public class ConcurrentWriteCorruptionTest {

    private static final String TEST_COLLECTION = "concurrent-write-test";
    private static final String COLLECTION_URI = "/db/" + TEST_COLLECTION;
    private static final int NUM_THREADS = 6;
    private static final int ITERATIONS = 50;
    /** Duration in seconds for sustained-load tests */
    private static final int SUSTAINED_DURATION_SECONDS = 5;

    @ClassRule
    public static final ExistXmldbEmbeddedServer server = new ExistXmldbEmbeddedServer(false, true, true);

    private Collection testCollection;

    @Before
    public void setUp() throws XMLDBException {
        final Collection root = server.getRoot();
        final CollectionManagementService cms = root.getService(CollectionManagementService.class);
        testCollection = cms.createCollection(TEST_COLLECTION);
    }

    @After
    public void tearDown() throws XMLDBException {
        final Collection root = server.getRoot();
        final CollectionManagementService cms = root.getService(CollectionManagementService.class);
        cms.removeCollection(TEST_COLLECTION);
    }

    /**
     * Test 1: Concurrent attribute updates (Alexander Henket's scenario).
     *
     * 6 threads concurrently update different attributes on the same elements.
     * Each thread writes a unique value pattern to its designated attribute.
     * After all threads complete, we verify no cross-contamination occurred.
     */
    @Test
    public void concurrentAttributeUpdates() throws Exception {
        // Setup: create document with elements that have multiple attributes
        final String doc = "<root>" +
                "<item id='1' a1='init' a2='init' a3='init' a4='init' a5='init' a6='init'/>" +
                "<item id='2' a1='init' a2='init' a3='init' a4='init' a5='init' a6='init'/>" +
                "<item id='3' a1='init' a2='init' a3='init' a4='init' a5='init' a6='init'/>" +
                "<item id='4' a1='init' a2='init' a3='init' a4='init' a5='init' a6='init'/>" +
                "<item id='5' a1='init' a2='init' a3='init' a4='init' a5='init' a6='init'/>" +
                "</root>";
        storeDocument("attrs.xml", doc);

        final AtomicInteger corruptionCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // Reset document
            storeDocument("attrs.xml", doc);

            final CyclicBarrier barrier = new CyclicBarrier(NUM_THREADS);
            final ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
            final List<Future<?>> futures = new ArrayList<>();

            // Each thread updates its own attribute (a1..a6) across all items
            for (int t = 0; t < NUM_THREADS; t++) {
                final int threadId = t;
                final String attrName = "a" + (threadId + 1);
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        final XQueryService qs = server.getRoot().getService(XQueryService.class);
                        for (int item = 1; item <= 5; item++) {
                            final String value = "t" + threadId + "-i" + item;
                            qs.query(
                                    "update value doc('" + COLLECTION_URI + "/attrs.xml')//item[@id='" + item + "']/@" + attrName +
                                            " with '" + value + "'"
                            );
                        }
                    } catch (final Exception e) {
                        errorCount.incrementAndGet();
                    }
                    return null;
                }));
            }

            // Wait for all threads
            for (final Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();

            // Verify: each attribute should contain only values from its designated thread
            final XQueryService qs = server.getRoot().getService(XQueryService.class);
            for (int item = 1; item <= 5; item++) {
                for (int t = 0; t < NUM_THREADS; t++) {
                    final String attrName = "a" + (t + 1);
                    final ResourceSet result = qs.query(
                            "string(doc('" + COLLECTION_URI + "/attrs.xml')//item[@id='" + item + "']/@" + attrName + ")"
                    );
                    final String value = result.getResource(0).getContent().toString();
                    // Value should be either "init" (thread didn't reach this item yet) or "tN-iM" (thread N wrote item M)
                    if (!value.equals("init") && !value.startsWith("t" + t + "-")) {
                        corruptionCount.incrementAndGet();
                    }
                }
            }
        }

        // Report results — corruption count > 0 means concurrent writes caused cross-contamination
        System.out.println("=== Test 1: Concurrent Attribute Updates ===");
        System.out.println("  Iterations: " + ITERATIONS);
        System.out.println("  Threads: " + NUM_THREADS);
        System.out.println("  Corruptions detected: " + corruptionCount.get());
        System.out.println("  Errors: " + errorCount.get());

        // On develop (no preclaiming), we expect some corruption.
        // With preclaiming, we expect zero corruption.
        // Don't assert — just report. The test documents the corruption rate.
    }

    /**
     * Test 2: Concurrent xmldb:store to the same collection.
     *
     * 4 threads concurrently store different documents to the same collection.
     * Verifies no documents are missing or corrupted after all stores complete.
     */
    @Test
    public void concurrentXmldbStore() throws Exception {
        final int docsPerThread = 10;
        final AtomicInteger errorCount = new AtomicInteger(0);
        final AtomicInteger missingCount = new AtomicInteger(0);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // Clean collection
            tearDown();
            setUp();

            final CyclicBarrier barrier = new CyclicBarrier(4);
            final ExecutorService executor = Executors.newFixedThreadPool(4);
            final List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < 4; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        final XQueryService qs = server.getRoot().getService(XQueryService.class);
                        for (int d = 0; d < docsPerThread; d++) {
                            final String docName = "t" + threadId + "-doc" + d + ".xml";
                            final String content = "<doc thread='" + threadId + "' seq='" + d + "'>content-" + threadId + "-" + d + "</doc>";
                            qs.query(
                                    "xmldb:store('" + COLLECTION_URI + "', '" + docName + "', " +
                                            "<doc thread='" + threadId + "' seq='" + d + "'>content-" + threadId + "-" + d + "</doc>)"
                            );
                        }
                    } catch (final Exception e) {
                        errorCount.incrementAndGet();
                    }
                    return null;
                }));
            }

            for (final Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();

            // Verify all documents exist
            final XQueryService qs = server.getRoot().getService(XQueryService.class);
            final ResourceSet countResult = qs.query("count(collection('" + COLLECTION_URI + "'))");
            final int actual = Integer.parseInt(countResult.getResource(0).getContent().toString());
            final int expected = 4 * docsPerThread;
            if (actual != expected) {
                missingCount.addAndGet(expected - actual);
            }
        }

        System.out.println("=== Test 2: Concurrent xmldb:store ===");
        System.out.println("  Iterations: " + ITERATIONS);
        System.out.println("  Expected docs per iteration: " + (4 * docsPerThread));
        System.out.println("  Total missing documents: " + missingCount.get());
        System.out.println("  Errors: " + errorCount.get());
    }

    /**
     * Test 3: Concurrent read/write interleaving.
     *
     * 2 writer threads update a counter in a document.
     * 4 reader threads query the counter value.
     * Readers should never see a partial/corrupt value.
     */
    @Test
    public void concurrentReadWriteInterleaving() throws Exception {
        storeDocument("counter.xml", "<counter value='0'/>");

        final AtomicInteger corruptionCount = new AtomicInteger(0);
        final AtomicInteger writeErrors = new AtomicInteger(0);
        final AtomicInteger readErrors = new AtomicInteger(0);
        final int writesPerThread = 50;

        final CyclicBarrier barrier = new CyclicBarrier(6); // 2 writers + 4 readers
        final ExecutorService executor = Executors.newFixedThreadPool(6);
        final List<Future<?>> futures = new ArrayList<>();
        final CountDownLatch writersComplete = new CountDownLatch(2);

        // 2 writer threads
        for (int w = 0; w < 2; w++) {
            final int writerId = w;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    final XQueryService qs = server.getRoot().getService(XQueryService.class);
                    for (int i = 0; i < writesPerThread; i++) {
                        final int newValue = writerId * 1000 + i;
                        qs.query(
                                "update value doc('" + COLLECTION_URI + "/counter.xml')/counter/@value with '" + newValue + "'"
                        );
                    }
                } catch (final Exception e) {
                    writeErrors.incrementAndGet();
                } finally {
                    writersComplete.countDown();
                }
                return null;
            }));
        }

        // 4 reader threads
        for (int r = 0; r < 4; r++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    final XQueryService qs = server.getRoot().getService(XQueryService.class);
                    while (!writersComplete.await(10, TimeUnit.MILLISECONDS)) {
                        final ResourceSet result = qs.query(
                                "string(doc('" + COLLECTION_URI + "/counter.xml')/counter/@value)"
                        );
                        final String value = result.getResource(0).getContent().toString();
                        // Value should be a valid integer — any non-integer result is corruption
                        try {
                            Integer.parseInt(value);
                        } catch (final NumberFormatException e) {
                            corruptionCount.incrementAndGet();
                        }
                    }
                } catch (final Exception e) {
                    readErrors.incrementAndGet();
                }
                return null;
            }));
        }

        for (final Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        System.out.println("=== Test 3: Concurrent Read/Write Interleaving ===");
        System.out.println("  Writers: 2 x " + writesPerThread + " writes");
        System.out.println("  Readers: 4 concurrent");
        System.out.println("  Corrupt reads (non-integer value): " + corruptionCount.get());
        System.out.println("  Write errors: " + writeErrors.get());
        System.out.println("  Read errors: " + readErrors.get());
    }

    /**
     * Test 4: Concurrent document updates to same document.
     *
     * Multiple threads update different child elements of the same document.
     * Verifies that updates don't interfere with each other (no lost updates).
     */
    @Test
    public void concurrentDocumentUpdates() throws Exception {
        final AtomicInteger lostUpdateCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // Create document with separate sections for each thread
            final StringBuilder sb = new StringBuilder("<root>");
            for (int t = 0; t < NUM_THREADS; t++) {
                sb.append("<section id='s").append(t).append("'><value>0</value></section>");
            }
            sb.append("</root>");
            storeDocument("sections.xml", sb.toString());

            final CyclicBarrier barrier = new CyclicBarrier(NUM_THREADS);
            final ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
            final List<Future<?>> futures = new ArrayList<>();

            // Each thread updates its own section 10 times (increment counter)
            for (int t = 0; t < NUM_THREADS; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        final XQueryService qs = server.getRoot().getService(XQueryService.class);
                        for (int i = 0; i < 10; i++) {
                            qs.query(
                                    "let $section := doc('" + COLLECTION_URI + "/sections.xml')//section[@id='s" + threadId + "']/value " +
                                            "return update value $section with string(number($section) + 1)"
                            );
                        }
                    } catch (final Exception e) {
                        errorCount.incrementAndGet();
                    }
                    return null;
                }));
            }

            for (final Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();

            // Verify: each section's counter should be 10 (incremented 10 times)
            final XQueryService qs = server.getRoot().getService(XQueryService.class);
            for (int t = 0; t < NUM_THREADS; t++) {
                final ResourceSet result = qs.query(
                        "string(doc('" + COLLECTION_URI + "/sections.xml')//section[@id='s" + t + "']/value)"
                );
                final String value = result.getResource(0).getContent().toString();
                try {
                    final int count = Integer.parseInt(value);
                    if (count != 10) {
                        lostUpdateCount.incrementAndGet();
                    }
                } catch (final NumberFormatException e) {
                    lostUpdateCount.incrementAndGet();
                }
            }
        }

        System.out.println("=== Test 4: Concurrent Document Updates (lost update detection) ===");
        System.out.println("  Iterations: " + ITERATIONS);
        System.out.println("  Threads: " + NUM_THREADS + " x 10 increments each");
        System.out.println("  Lost updates detected: " + lostUpdateCount.get());
        System.out.println("  Errors: " + errorCount.get());
    }

    /**
     * Test 4b: Sustained concurrent increment on a SHARED counter.
     *
     * This is the classic lost-update scenario: N threads all do read-modify-write
     * on the same counter for SUSTAINED_DURATION_SECONDS. Without serialization,
     * the final counter value will be less than the sum of all increments.
     *
     * Expected on develop (no preclaiming): significant lost updates (counter < total ops)
     * Expected with preclaiming: counter == total ops (serialized access)
     */
    @Test
    public void sustainedConcurrentSharedCounter() throws Exception {
        storeDocument("shared-counter.xml", "<counter value='0'/>");

        final AtomicInteger totalOps = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        final CyclicBarrier barrier = new CyclicBarrier(NUM_THREADS);
        final ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        final List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < NUM_THREADS; t++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    final XQueryService qs = server.getRoot().getService(XQueryService.class);
                    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SUSTAINED_DURATION_SECONDS);
                    while (System.nanoTime() < deadline) {
                        qs.query(
                                "let $c := doc('" + COLLECTION_URI + "/shared-counter.xml')/counter " +
                                        "return update value $c/@value with string(number($c/@value) + 1)"
                        );
                        totalOps.incrementAndGet();
                    }
                } catch (final Exception e) {
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }

        for (final Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Read the final counter value
        final XQueryService qs = server.getRoot().getService(XQueryService.class);
        final ResourceSet result = qs.query(
                "string(doc('" + COLLECTION_URI + "/shared-counter.xml')/counter/@value)"
        );
        final String rawValue = result.getResource(0).getContent().toString();

        int finalValue = -1;
        boolean corrupted = false;
        try {
            finalValue = Integer.parseInt(rawValue);
        } catch (final NumberFormatException e) {
            // Counter value is not a valid integer — this IS corruption
            // (e.g., "NaN" from concurrent read-modify-write race)
            corrupted = true;
        }

        final int lostUpdates = corrupted ? totalOps.get() : totalOps.get() - finalValue;
        final double lostPct = totalOps.get() > 0 ? 100.0 * lostUpdates / totalOps.get() : 0;

        System.out.println("=== Test 4b: Sustained Concurrent Shared Counter ===");
        System.out.println("  Duration: " + SUSTAINED_DURATION_SECONDS + "s");
        System.out.println("  Threads: " + NUM_THREADS);
        System.out.println("  Total operations: " + totalOps.get());
        System.out.println("  Final counter value: " + (corrupted ? rawValue + " (CORRUPTED)" : String.valueOf(finalValue)));
        System.out.println("  Lost updates: " + lostUpdates + " (" + String.format("%.1f", lostPct) + "%)");
        System.out.println("  Data corruption: " + corrupted);
        System.out.println("  Errors: " + errorCount.get());

        // This test DOCUMENTS the lost-update rate; it doesn't assert zero.
        // With preclaiming, lost updates should be zero (serialized access).
        // Without preclaiming, lost updates demonstrate the race condition.
    }

    /**
     * Test 5: Concurrent move + write (MoveResourceTest scenario).
     *
     * Thread A moves a document between collections.
     * Thread B writes to the document concurrently.
     * Thread C queries both source and target collections.
     * The document should appear in exactly one collection at all times.
     */
    @Test
    public void concurrentMoveAndWrite() throws Exception {
        final AtomicInteger moveErrors = new AtomicInteger(0);
        final AtomicInteger writeErrors = new AtomicInteger(0);
        final AtomicInteger queryErrors = new AtomicInteger(0);
        final AtomicInteger inconsistencyCount = new AtomicInteger(0);

        // Create source and target collections
        final XQueryService rootQs = server.getRoot().getService(XQueryService.class);
        rootQs.query("xmldb:create-collection('/db', 'move-source')");
        rootQs.query("xmldb:create-collection('/db', 'move-target')");

        for (int iter = 0; iter < 20; iter++) {
            // Setup: put document in source
            rootQs.query("xmldb:store('/db/move-source', 'moveme.xml', <doc iter='" + iter + "'>data</doc>)");

            final CyclicBarrier barrier = new CyclicBarrier(3);
            final ExecutorService executor = Executors.newFixedThreadPool(3);
            final CountDownLatch moveComplete = new CountDownLatch(1);
            final List<Future<?>> futures = new ArrayList<>();

            // Thread A: move document
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    final XQueryService qs = server.getRoot().getService(XQueryService.class);
                    qs.query("xmldb:move('/db/move-source', '/db/move-target', 'moveme.xml')");
                } catch (final Exception e) {
                    moveErrors.incrementAndGet();
                } finally {
                    moveComplete.countDown();
                }
                return null;
            }));

            // Thread B: try to update the document during move
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    final XQueryService qs = server.getRoot().getService(XQueryService.class);
                    qs.query(
                            "if (doc-available('/db/move-source/moveme.xml')) then " +
                                    "update value doc('/db/move-source/moveme.xml')/doc with 'updated' " +
                                    "else if (doc-available('/db/move-target/moveme.xml')) then " +
                                    "update value doc('/db/move-target/moveme.xml')/doc with 'updated' " +
                                    "else ()"
                    );
                } catch (final Exception e) {
                    writeErrors.incrementAndGet();
                }
                return null;
            }));

            // Thread C: check document location during move
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    final XQueryService qs = server.getRoot().getService(XQueryService.class);
                    for (int check = 0; check < 10 && !moveComplete.await(5, TimeUnit.MILLISECONDS); check++) {
                        final ResourceSet result = qs.query(
                                "let $in-source := doc-available('/db/move-source/moveme.xml') " +
                                        "let $in-target := doc-available('/db/move-target/moveme.xml') " +
                                        "return if ($in-source and $in-target) then 'BOTH' " +
                                        "else if (not($in-source) and not($in-target)) then 'NEITHER' " +
                                        "else 'OK'"
                        );
                        final String status = result.getResource(0).getContent().toString();
                        if ("BOTH".equals(status) || "NEITHER".equals(status)) {
                            inconsistencyCount.incrementAndGet();
                        }
                    }
                } catch (final Exception e) {
                    queryErrors.incrementAndGet();
                }
                return null;
            }));

            for (final Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();

            // Cleanup: ensure document is in target for next iteration's reset
            try {
                rootQs.query("if (doc-available('/db/move-target/moveme.xml')) then xmldb:remove('/db/move-target', 'moveme.xml') else ()");
            } catch (final Exception ignored) {
            }
            try {
                rootQs.query("if (doc-available('/db/move-source/moveme.xml')) then xmldb:remove('/db/move-source', 'moveme.xml') else ()");
            } catch (final Exception ignored) {
            }
        }

        // Cleanup collections
        try {
            rootQs.query("xmldb:remove('/db/move-source')");
            rootQs.query("xmldb:remove('/db/move-target')");
        } catch (final Exception ignored) {
        }

        System.out.println("=== Test 5: Concurrent Move + Write ===");
        System.out.println("  Iterations: 20");
        System.out.println("  Move errors: " + moveErrors.get());
        System.out.println("  Write errors: " + writeErrors.get());
        System.out.println("  Query errors: " + queryErrors.get());
        System.out.println("  Inconsistencies (doc in BOTH or NEITHER collection): " + inconsistencyCount.get());
    }

    private void storeDocument(final String name, final String content) throws XMLDBException {
        final XMLResource resource = testCollection.createResource(name, XMLResource.class);
        resource.setContent(content);
        testCollection.storeResource(resource);
    }
}
