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
package org.exist.xquery;

import org.exist.EXistException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.value.Sequence;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Performance benchmarks for module loading, executed as JUnit tests
 * to get full access to the eXist infrastructure.
 *
 * <p>Run with:</p>
 * <pre>
 * mvn test -pl exist-core -Dtest="org.exist.xquery.ModuleLoadingBenchmarkTest" \
 *   -Ddependency-check.skip=true -Ddocker=false
 * </pre>
 */
public class ModuleLoadingBenchmarkTest {

    private static final int WARMUP = 3;
    private static final int ITERATIONS = 10;

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer =
            new ExistEmbeddedServer(true, true);

    // ===== Benchmark 2: Memory Footprint =====

    @Test
    public void benchmark2_memoryFootprint() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();

        System.out.println();
        System.out.println("=== Benchmark 2: Memory Footprint ===");

        // Force GC for baseline
        forceGC();
        final long baseline = usedMemory();

        try (final DBBroker broker = pool.get(Optional.empty())) {
            final XQuery xquery = pool.getXQueryService();

            // Count loaded modules
            final Sequence moduleCount = xquery.execute(broker,
                    "count(util:registered-modules())", null);
            System.out.printf("  Loaded modules: %s%n", moduleCount.getStringValue());

            // Count registered functions
            final Sequence fnCount = xquery.execute(broker,
                    "count(util:registered-functions())", null);
            System.out.printf("  Registered functions: %s%n", fnCount.getStringValue());
        }

        forceGC();
        final long afterModules = usedMemory();

        System.out.printf("  Heap baseline (before queries): %d KB%n", baseline / 1024);
        System.out.printf("  Heap after module introspection: %d KB%n", afterModules / 1024);
        System.out.printf("  Delta: %d KB%n", (afterModules - baseline) / 1024);

        // Measure per-XQueryContext cost (each context loads module references)
        forceGC();
        final long beforeContexts = usedMemory();

        try (final DBBroker broker = pool.get(Optional.empty())) {
            final XQuery xquery = pool.getXQueryService();
            // Create and discard 100 XQuery contexts to measure marginal cost
            for (int i = 0; i < 100; i++) {
                final XQueryContext ctx = new XQueryContext(pool);
                // Force module loading by referencing a function
                xquery.execute(broker, "1", null);
            }
        }

        forceGC();
        final long afterContexts = usedMemory();
        final long perContext = (afterContexts - beforeContexts) / 100;
        System.out.printf("  Per-XQueryContext overhead (100 contexts): ~%d KB each%n",
                perContext / 1024);
    }

    // ===== Benchmark 3: Cold vs Warm Call Latency =====

    @Test
    public void benchmark3_queryLatency() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();

        System.out.println();
        System.out.println("=== Benchmark 3: Query Execution Latency ===");

        try (final DBBroker broker = pool.get(Optional.empty())) {
            final XQuery xquery = pool.getXQueryService();

            // --- Warm query: built-in fn:count, no module import ---
            final String warmQuery = "count(1 to 1000)";
            // Warm up
            for (int i = 0; i < WARMUP; i++) {
                xquery.execute(broker, warmQuery, null);
            }
            final List<Long> warmTimes = new ArrayList<>();
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                xquery.execute(broker, warmQuery, null);
                warmTimes.add((System.nanoTime() - start) / 1000);
            }
            printStats("Warm query (count(1 to 1000))", warmTimes, "µs");

            // --- Module function call: util:system-time ---
            final String utilQuery = "string(util:system-time())";
            for (int i = 0; i < WARMUP; i++) {
                xquery.execute(broker, utilQuery, null);
            }
            final List<Long> utilTimes = new ArrayList<>();
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                xquery.execute(broker, utilQuery, null);
                utilTimes.add((System.nanoTime() - start) / 1000);
            }
            printStats("Module call (util:system-time)", utilTimes, "µs");

            // --- Import + call: security manager module ---
            final String importQuery =
                    "import module namespace sm='http://exist-db.org/xquery/securitymanager'; " +
                    "count(sm:get-account-metadata-keys())";
            for (int i = 0; i < WARMUP; i++) {
                try { xquery.execute(broker, importQuery, null); } catch (Exception e) { /* ok */ }
            }
            final List<Long> importTimes = new ArrayList<>();
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                try {
                    xquery.execute(broker, importQuery, null);
                } catch (Exception e) { /* module may not support this */ }
                importTimes.add((System.nanoTime() - start) / 1000);
            }
            printStats("Import + call (sm:get-account-metadata-keys)", importTimes, "µs");

            // --- XQuery compilation cost ---
            final String complexQuery =
                    "for $i in 1 to 10 " +
                    "let $x := map { 'key': $i, 'value': $i * $i } " +
                    "where $i mod 2 = 0 " +
                    "return $x?value";
            for (int i = 0; i < WARMUP; i++) {
                xquery.execute(broker, complexQuery, null);
            }
            final List<Long> complexTimes = new ArrayList<>();
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                xquery.execute(broker, complexQuery, null);
                complexTimes.add((System.nanoTime() - start) / 1000);
            }
            printStats("FLWOR with map (compile+execute)", complexTimes, "µs");
        }
    }

    // ===== Benchmark 4: Module Discovery Cost =====

    @Test
    public void benchmark4_moduleDiscovery() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();

        System.out.println();
        System.out.println("=== Benchmark 4: Module Discovery Cost ===");

        try (final DBBroker broker = pool.get(Optional.empty())) {
            final XQuery xquery = pool.getXQueryService();

            // --- registered-modules() ---
            for (int i = 0; i < WARMUP; i++) {
                xquery.execute(broker, "count(util:registered-modules())", null);
            }
            final List<Long> modulesTimes = new ArrayList<>();
            String moduleCountStr = "?";
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                final Sequence result = xquery.execute(broker,
                        "count(util:registered-modules())", null);
                modulesTimes.add((System.nanoTime() - start) / 1000);
                moduleCountStr = result.getStringValue();
            }
            printStats("registered-modules() [" + moduleCountStr + " modules]",
                    modulesTimes, "µs");

            // --- registered-functions() (all modules) ---
            for (int i = 0; i < WARMUP; i++) {
                xquery.execute(broker, "count(util:registered-functions())", null);
            }
            final List<Long> functionsTimes = new ArrayList<>();
            String fnCountStr = "?";
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                final Sequence result = xquery.execute(broker,
                        "count(util:registered-functions())", null);
                functionsTimes.add((System.nanoTime() - start) / 1000);
                fnCountStr = result.getStringValue();
            }
            printStats("registered-functions() [" + fnCountStr + " functions]",
                    functionsTimes, "µs");

            // --- registered-functions() for a single module ---
            for (int i = 0; i < WARMUP; i++) {
                xquery.execute(broker,
                        "count(util:registered-functions('http://exist-db.org/xquery/util'))", null);
            }
            final List<Long> singleModTimes = new ArrayList<>();
            String singleCountStr = "?";
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                final Sequence result = xquery.execute(broker,
                        "count(util:registered-functions('http://exist-db.org/xquery/util'))", null);
                singleModTimes.add((System.nanoTime() - start) / 1000);
                singleCountStr = result.getStringValue();
            }
            printStats("registered-functions(util) [" + singleCountStr + " functions]",
                    singleModTimes, "µs");

            // --- List all module URIs ---
            final Sequence modules = xquery.execute(broker,
                    "string-join(util:registered-modules(), ', ')", null);
            System.out.printf("  Loaded modules: %s%n", modules.getStringValue());
        }
    }

    // ===== Helpers =====

    private static void forceGC() throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        System.gc();
        Thread.sleep(100);
    }

    private static long usedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static void printStats(String label, List<Long> values, String unit) {
        if (values.isEmpty()) {
            System.out.printf("  %s: no data%n", label);
            return;
        }
        final long avg = values.stream().mapToLong(Long::longValue).sum() / values.size();
        final long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
        final long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        final long median = values.stream().sorted().skip(values.size() / 2).findFirst().orElse(0L);
        System.out.printf("  **%s**: avg=%d %s, median=%d %s, min=%d, max=%d (n=%d)%n",
                label, avg, unit, median, unit, min, max, values.size());
    }
}
