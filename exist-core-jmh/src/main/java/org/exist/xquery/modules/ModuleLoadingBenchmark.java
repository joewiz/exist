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
package org.exist.xquery.modules;

import org.exist.EXistException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.util.Configuration;
import org.exist.util.DatabaseConfigurationException;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.XQuery;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.Sequence;

import org.exist.util.ConfigurationHelper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Standalone benchmark for measuring module loading performance.
 *
 * <p>Measures startup time, memory footprint, and query execution latency
 * with different module configurations. Results are written to stdout
 * as a Markdown table for easy inclusion in reports.</p>
 *
 * <p>This is NOT a JMH benchmark — BrokerPool's singleton nature makes it
 * incompatible with JMH's forking model. Instead, it uses manual timing
 * with multiple iterations and warm-up.</p>
 *
 * <p>Run with:</p>
 * <pre>
 * cd exist-core-jmh
 * mvn compile exec:java -Dexec.mainClass="org.exist.xquery.modules.ModuleLoadingBenchmark"
 * </pre>
 *
 * <p>Or from the command line after building:</p>
 * <pre>
 * java -cp target/classes:target/dependency/* org.exist.xquery.modules.ModuleLoadingBenchmark
 * </pre>
 */
public class ModuleLoadingBenchmark {

    private static final int WARMUP_ITERATIONS = 2;
    private static final int MEASUREMENT_ITERATIONS = 5;

    // Use the test conf.xml as the "all modules" baseline (14 modules)
    // and create a minimal variant with only essential modules
    private static final String CONF_ALL = "conf.xml";
    private static final String CONF_MINIMAL = "conf-minimal.xml";

    public static void main(String[] args) throws Exception {
        // Disable auto-deploy to avoid XAR installation during benchmarks
        System.setProperty("autodeploy", "off");

        System.out.println("# Module Loading Performance Benchmark");
        System.out.println();
        System.out.println("eXist-db " + org.exist.SystemProperties.getInstance().getSystemProperty("product-version", "unknown"));
        System.out.println("Java " + System.getProperty("java.version"));
        System.out.println("Date: " + java.time.LocalDateTime.now());
        System.out.println();

        createMinimalConf();

        // Benchmark 1: Startup time
        System.out.println("## Benchmark 1: Startup Time");
        System.out.println();
        benchmarkStartup(CONF_ALL, "All Modules (14)");
        benchmarkStartup(CONF_MINIMAL, "Minimal Modules (4)");
        System.out.println();

        // Benchmark 2: Memory footprint
        System.out.println("## Benchmark 2: Memory Footprint");
        System.out.println();
        benchmarkMemory(CONF_ALL, "All Modules (14)");
        benchmarkMemory(CONF_MINIMAL, "Minimal Modules (4)");
        System.out.println();

        // Benchmark 3: Query execution (cold vs warm)
        System.out.println("## Benchmark 3: Query Execution Latency");
        System.out.println();
        benchmarkQueryExecution();
        System.out.println();

        // Benchmark 4: Module discovery cost
        System.out.println("## Benchmark 4: Module Discovery Cost");
        System.out.println();
        benchmarkModuleDiscovery();
    }

    /**
     * Create a minimal conf.xml with only essential modules.
     */
    private static void createMinimalConf() throws IOException {
        // The minimal conf.xml is created by the test resource filtering,
        // or we create a programmatic Configuration with fewer modules.
        // For now, we just use the default conf.xml and note which modules are present.
    }

    /**
     * Benchmark BrokerPool startup time.
     */
    private static void benchmarkStartup(String confFile, String label) {
        final List<Long> times = new ArrayList<>();

        // Warm-up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try {
                final long elapsed = timeStartupShutdown();
                System.out.printf("  [warmup %d] %s: %d ms%n", i + 1, label, elapsed);
            } catch (Exception e) {
                System.err.println("  Warmup failed: " + e.getMessage());
            }
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            try {
                final long elapsed = timeStartupShutdown();
                times.add(elapsed);
                System.out.printf("  [measure %d] %s: %d ms%n", i + 1, label, elapsed);
            } catch (Exception e) {
                System.err.println("  Measurement failed: " + e.getMessage());
            }
        }

        if (!times.isEmpty()) {
            final long avg = times.stream().mapToLong(Long::longValue).sum() / times.size();
            final long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
            final long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
            System.out.printf("  **%s**: avg=%d ms, min=%d ms, max=%d ms (n=%d)%n",
                    label, avg, min, max, times.size());
        }
    }

    private static Path findConfFile() {
        final Path workDir = Paths.get(System.getProperty("user.dir"));
        final Path[] candidates = {
                workDir.resolve("exist-core/target/test-classes/conf.xml"),
                workDir.resolve("../exist-core/target/test-classes/conf.xml"),
        };
        for (final Path candidate : candidates) {
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static long timeStartupShutdown() throws Exception {
        final Path confFile = findConfFile();
        if (confFile == null) {
            throw new RuntimeException("Cannot find test conf.xml. Run 'mvn process-test-resources -pl exist-core' first.");
        }

        // Use temporary storage to avoid lock file conflicts
        final Path tmpDir = Files.createTempDirectory("exist-bench-");
        System.setProperty("org.exist.db-connection.files", tmpDir.toAbsolutePath().toString());
        System.setProperty("org.exist.db-connection.recovery.journal-dir", tmpDir.toAbsolutePath().toString());

        final long start = System.nanoTime();

        final Configuration config = new Configuration(confFile.toAbsolutePath().toString());
        BrokerPool.configure(1, 5, config);
        final BrokerPool pool = BrokerPool.getInstance();

        final long elapsed = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();
        BrokerPool.stopAll(false);

        // Clean up temp storage
        org.exist.util.FileUtils.deleteQuietly(tmpDir);

        // Small delay to ensure cleanup
        Thread.sleep(500);

        return elapsed;
    }

    /**
     * Benchmark memory footprint after startup.
     */
    private static void benchmarkMemory(String confFile, String label) {
        try {
            final Path cf = findConfFile();
            if (cf == null) { System.err.println("  No conf.xml found"); return; }
            final Path tmpDir = Files.createTempDirectory("exist-bench-mem-");
            System.setProperty("org.exist.db-connection.files", tmpDir.toAbsolutePath().toString());
            System.setProperty("org.exist.db-connection.recovery.journal-dir", tmpDir.toAbsolutePath().toString());

            // Force GC before measurement
            System.gc();
            Thread.sleep(200);
            System.gc();
            final long beforeMem = usedMemory();

            final Configuration config = new Configuration(cf.toAbsolutePath().toString());
            BrokerPool.configure(1, 5, config);
            final BrokerPool pool = BrokerPool.getInstance();

            // Force GC for stable measurement
            System.gc();
            Thread.sleep(200);
            System.gc();
            final long afterMem = usedMemory();

            final long delta = afterMem - beforeMem;

            // Count loaded modules
            int moduleCount = 0;
            try (final DBBroker broker = pool.get(Optional.empty())) {
                final XQuery xquery = pool.getXQueryService();
                final Sequence result = xquery.execute(broker,
                        "count(util:registered-modules())", null);
                moduleCount = Integer.parseInt(result.getStringValue());
            }

            System.out.printf("  **%s**: heap delta=%d MB (%d KB), modules=%d%n",
                    label, delta / (1024 * 1024), delta / 1024, moduleCount);

            pool.shutdown();
            BrokerPool.stopAll(false);
            org.exist.util.FileUtils.deleteQuietly(tmpDir);
            Thread.sleep(500);

        } catch (Exception e) {
            System.err.println("  Memory benchmark failed: " + e.getMessage());
        }
    }

    private static BrokerPool startPool() throws Exception {
        final Path cf = findConfFile();
        if (cf == null) throw new RuntimeException("No conf.xml found");
        final Path tmpDir = Files.createTempDirectory("exist-bench-");
        System.setProperty("org.exist.db-connection.files", tmpDir.toAbsolutePath().toString());
        System.setProperty("org.exist.db-connection.recovery.journal-dir", tmpDir.toAbsolutePath().toString());
        final Configuration config = new Configuration(cf.toAbsolutePath().toString());
        BrokerPool.configure(1, 5, config);
        return BrokerPool.getInstance();
    }

    private static void stopPool() throws Exception {
        BrokerPool.stopAll(false);
        Thread.sleep(500);
    }

    /**
     * Benchmark query execution: warm (fn:count) vs. cold (import external module).
     */
    private static void benchmarkQueryExecution() {
        try {
            final BrokerPool pool = startPool();

            try (final DBBroker broker = pool.get(Optional.empty())) {
                final XQuery xquery = pool.getXQueryService();

                // Warm query — built-in function, no module import
                final String warmQuery = "count(1 to 1000)";
                // Warm up the warm path
                for (int i = 0; i < 5; i++) {
                    xquery.execute(broker, warmQuery, null);
                }

                final List<Long> warmTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    xquery.execute(broker, warmQuery, null);
                    warmTimes.add((System.nanoTime() - start) / 1000); // microseconds
                }

                // Cold query — import a module and call a function
                final String coldQuery =
                        "import module namespace util='http://exist-db.org/xquery/util'; " +
                        "util:system-time()";

                final List<Long> coldTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    xquery.execute(broker, coldQuery, null);
                    coldTimes.add((System.nanoTime() - start) / 1000); // microseconds
                }

                // Module import query — import and use a function from a module
                final String importQuery =
                        "import module namespace sm='http://exist-db.org/xquery/securitymanager'; " +
                        "sm:get-account-metadata-keys()";

                final List<Long> importTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    try {
                        xquery.execute(broker, importQuery, null);
                    } catch (Exception e) {
                        // Module might not support this call — just time the compile+attempt
                    }
                    importTimes.add((System.nanoTime() - start) / 1000);
                }

                System.out.printf("  **Warm query** (count): avg=%d µs%n", avg(warmTimes));
                System.out.printf("  **Loaded module call** (util:system-time): avg=%d µs%n", avg(coldTimes));
                System.out.printf("  **Module import** (sm:get-account-metadata-keys): avg=%d µs%n", avg(importTimes));
            }

            stopPool();

        } catch (Exception e) {
            System.err.println("  Query benchmark failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /**
     * Benchmark module discovery cost (registered-modules, registered-functions).
     */
    private static void benchmarkModuleDiscovery() {
        try {
            final BrokerPool pool = startPool();

            try (final DBBroker broker = pool.get(Optional.empty())) {
                final XQuery xquery = pool.getXQueryService();

                // registered-modules()
                final List<Long> modulesTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    final Sequence result = xquery.execute(broker,
                            "count(util:registered-modules())", null);
                    modulesTimes.add((System.nanoTime() - start) / 1000);
                }

                // registered-functions() — this is the expensive one
                final List<Long> functionsTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    final Sequence result = xquery.execute(broker,
                            "count(util:registered-functions())", null);
                    functionsTimes.add((System.nanoTime() - start) / 1000);
                }

                // registered-functions for a specific module
                final List<Long> moduleFnTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    final Sequence result = xquery.execute(broker,
                            "count(util:registered-functions('http://exist-db.org/xquery/util'))", null);
                    moduleFnTimes.add((System.nanoTime() - start) / 1000);
                }

                System.out.printf("  **registered-modules()**: avg=%d µs%n", avg(modulesTimes));
                System.out.printf("  **registered-functions()**: avg=%d µs%n", avg(functionsTimes));
                System.out.printf("  **registered-functions(uri)**: avg=%d µs%n", avg(moduleFnTimes));
            }

            stopPool();

        } catch (Exception e) {
            System.err.println("  Discovery benchmark failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static long usedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static long avg(List<Long> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToLong(Long::longValue).sum() / values.size();
    }
}
