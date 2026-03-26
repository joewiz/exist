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
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.FileUtils;
import org.exist.xquery.value.Sequence;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertTrue;

/**
 * Compares per-query overhead between minimal (4 module) and full (14 module)
 * configurations. Tests Adam Retter's claim (issue #1848) that eXist pays a
 * per-query cost proportional to the number of configured modules.
 *
 * <p>Run with:</p>
 * <pre>
 * mvn test -pl exist-core -Dtest="org.exist.xquery.ModuleLoadingComparisonTest" \
 *   -Ddependency-check.skip=true -Ddocker=false
 * </pre>
 */
public class ModuleLoadingComparisonTest {

    private static final int WARMUP = 100;
    private static final int ITERATIONS = 10_000;
    private static final String TRIVIAL_QUERY = "count(1 to 10)";

    // ===== Benchmark 5: Per-query overhead vs. module count =====

    @Test
    public void benchmark5_perQueryOverhead() throws Exception {
        System.out.println();
        System.out.println("=== Benchmark 5: Per-Query Overhead vs. Module Count ===");
        System.out.println("  Query: " + TRIVIAL_QUERY);
        System.out.println("  Iterations: " + ITERATIONS);
        System.out.println();

        // --- Minimal config (4 modules) ---
        final long[] minimalResults = runWithConfig("conf-minimal.xml", "Minimal (4 modules)");

        // --- Full config (14 modules) ---
        final long[] fullResults = runWithConfig("conf.xml", "Full (14 modules)");

        // --- Comparison ---
        if (minimalResults != null && fullResults != null) {
            final long delta = fullResults[0] - minimalResults[0];
            final double perModule = delta / 10.0; // 14 - 4 = 10 extra modules
            System.out.println("  --- Comparison ---");
            System.out.printf("  Delta (full - minimal): avg=%d ns/query (%.1f ns per extra module)%n",
                    delta, perModule);
            System.out.printf("  Percentage overhead: %.1f%%%n",
                    (double) delta / minimalResults[0] * 100);
        }
    }

    private long[] runWithConfig(String confName, String label) throws Exception {
        final ExistEmbeddedServer server = new ExistEmbeddedServer(
                confName.replace(".xml", ""),
                findConfFile(confName),
                null, true, true);

        try {
            server.startDb();
            final BrokerPool pool = server.getBrokerPool();

            try (final DBBroker broker = pool.get(Optional.empty())) {
                final XQuery xquery = pool.getXQueryService();

                // Count modules
                final Sequence moduleCount = xquery.execute(broker,
                        "count(util:registered-modules())", null);
                System.out.printf("  [%s] Loaded modules: %s%n", label, moduleCount.getStringValue());

                // Warm up
                for (int i = 0; i < WARMUP; i++) {
                    xquery.execute(broker, TRIVIAL_QUERY, null);
                }

                // Measure — each iteration compiles and executes
                final List<Long> times = new ArrayList<>(ITERATIONS);
                final long totalStart = System.nanoTime();
                for (int i = 0; i < ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    xquery.execute(broker, TRIVIAL_QUERY, null);
                    times.add(System.nanoTime() - start);
                }
                final long totalElapsed = System.nanoTime() - totalStart;

                final long avg = times.stream().mapToLong(Long::longValue).sum() / times.size();
                final long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
                final long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
                final long median = times.stream().sorted().skip(times.size() / 2).findFirst().orElse(0L);
                final long p99 = times.stream().sorted().skip((long)(times.size() * 0.99)).findFirst().orElse(0L);

                System.out.printf("  [%s] Total: %d ms for %d queries%n",
                        label, totalElapsed / 1_000_000, ITERATIONS);
                System.out.printf("  **[%s]** avg=%d ns, median=%d ns, min=%d ns, max=%d ns, p99=%d ns%n",
                        label, avg, median, min, max, p99);

                return new long[]{avg, median, min, max};
            }
        } finally {
            server.stopDb();
        }
    }

    // ===== Benchmark 6: XQueryContext construction cost =====

    @Test
    public void benchmark6_contextCreation() throws Exception {
        System.out.println();
        System.out.println("=== Benchmark 6: XQueryContext Construction Cost ===");
        System.out.println("  Iterations: " + ITERATIONS);
        System.out.println();

        // --- Minimal ---
        contextCreationWithConfig("conf-minimal.xml", "Minimal (4 modules)");

        // --- Full ---
        contextCreationWithConfig("conf.xml", "Full (14 modules)");
    }

    private void contextCreationWithConfig(String confName, String label) throws Exception {
        final ExistEmbeddedServer server = new ExistEmbeddedServer(
                confName.replace(".xml", ""),
                findConfFile(confName),
                null, true, true);

        try {
            server.startDb();
            final BrokerPool pool = server.getBrokerPool();

            // Warm up
            for (int i = 0; i < WARMUP; i++) {
                final XQueryContext ctx = new XQueryContext(pool);
            }

            // Measure just context creation
            final List<Long> times = new ArrayList<>(ITERATIONS);
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                final XQueryContext ctx = new XQueryContext(pool);
                times.add(System.nanoTime() - start);
            }

            final long avg = times.stream().mapToLong(Long::longValue).sum() / times.size();
            final long median = times.stream().sorted().skip(times.size() / 2).findFirst().orElse(0L);
            final long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
            final long max = times.stream().mapToLong(Long::longValue).max().orElse(0);

            System.out.printf("  **[%s]** avg=%d ns, median=%d ns, min=%d ns, max=%d ns%n",
                    label, avg, median, min, max);
        } finally {
            server.stopDb();
        }
    }

    // ===== Benchmark 7: Compile vs. Execute breakdown =====

    @Test
    public void benchmark7_compileVsExecute() throws Exception {
        System.out.println();
        System.out.println("=== Benchmark 7: Compile vs. Execute Breakdown ===");
        System.out.println("  Query: " + TRIVIAL_QUERY);
        System.out.println("  Iterations: " + ITERATIONS);
        System.out.println();

        // --- Minimal ---
        compileExecuteWithConfig("conf-minimal.xml", "Minimal (4 modules)");

        // --- Full ---
        compileExecuteWithConfig("conf.xml", "Full (14 modules)");
    }

    private void compileExecuteWithConfig(String confName, String label) throws Exception {
        final ExistEmbeddedServer server = new ExistEmbeddedServer(
                confName.replace(".xml", ""),
                findConfFile(confName),
                null, true, true);

        try {
            server.startDb();
            final BrokerPool pool = server.getBrokerPool();

            try (final DBBroker broker = pool.get(Optional.empty())) {
                final XQuery xquery = pool.getXQueryService();

                // Warm up
                for (int i = 0; i < WARMUP; i++) {
                    final XQueryContext ctx = new XQueryContext(pool);
                    final CompiledXQuery compiled = xquery.compile(ctx, TRIVIAL_QUERY);
                    xquery.execute(broker, compiled, null);
                }

                // Measure compile only
                final List<Long> compileTimes = new ArrayList<>(ITERATIONS);
                for (int i = 0; i < ITERATIONS; i++) {
                    final XQueryContext ctx = new XQueryContext(pool);
                    final long start = System.nanoTime();
                    final CompiledXQuery compiled = xquery.compile(ctx, TRIVIAL_QUERY);
                    compileTimes.add(System.nanoTime() - start);
                }

                // Measure execute only (pre-compiled)
                final XQueryContext ctx = new XQueryContext(pool);
                final CompiledXQuery preCompiled = xquery.compile(ctx, TRIVIAL_QUERY);
                // Warm up execute
                for (int i = 0; i < WARMUP; i++) {
                    xquery.execute(broker, preCompiled, null);
                    preCompiled.getContext().reset();
                }
                final List<Long> executeTimes = new ArrayList<>(ITERATIONS);
                for (int i = 0; i < ITERATIONS; i++) {
                    final long start = System.nanoTime();
                    xquery.execute(broker, preCompiled, null);
                    executeTimes.add(System.nanoTime() - start);
                    preCompiled.getContext().reset();
                }

                final long compileAvg = compileTimes.stream().mapToLong(Long::longValue).sum() / compileTimes.size();
                final long executeAvg = executeTimes.stream().mapToLong(Long::longValue).sum() / executeTimes.size();

                System.out.printf("  **[%s] Compile**: avg=%d ns (%d µs)%n",
                        label, compileAvg, compileAvg / 1000);
                System.out.printf("  **[%s] Execute**: avg=%d ns (%d µs)%n",
                        label, executeAvg, executeAvg / 1000);
                System.out.printf("  **[%s] Total**: %d ns (%d µs)%n",
                        label, compileAvg + executeAvg, (compileAvg + executeAvg) / 1000);
            }
        } finally {
            server.stopDb();
        }
    }

    // ===== Helpers =====

    private static Path findConfFile(String name) {
        final Path workDir = Paths.get(System.getProperty("user.dir"));
        final Path[] candidates = {
                workDir.resolve("exist-core/target/test-classes/" + name),
                workDir.resolve("target/test-classes/" + name),
        };
        for (final Path p : candidates) {
            if (java.nio.file.Files.isReadable(p)) {
                return p;
            }
        }
        throw new RuntimeException("Cannot find " + name + ". Run 'mvn process-test-resources -pl exist-core' first.");
    }
}
