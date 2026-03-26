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

import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.value.Sequence;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * Deep-dive benchmarks investigating per-query module overhead.
 *
 * <p>Tests Adam Retter's claim (issue #1848) at the code level by profiling
 * XQueryContext.loadDefaults() → addBuiltInModuleOrDeclareNamespace() →
 * instantiateModule() to determine:</p>
 * <ol>
 *   <li>Whether modules are shared or re-instantiated per context</li>
 *   <li>The cost of instantiateModule() (reflection + newInstance + prepare)</li>
 *   <li>Which prepare() calls are expensive</li>
 *   <li>Compilation cost scaling with module count</li>
 * </ol>
 */
public class ModuleInstantiationTest {

    private static final int ITERATIONS = 1000;

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer =
            new ExistEmbeddedServer(true, true);

    /**
     * Benchmark 8: XQueryContext creation with instrumentation.
     *
     * Creates 1000 fresh XQueryContexts and measures the time. Each creation
     * calls loadDefaults() which iterates all modules and calls
     * instantiateModule() for each one — creating new module instances,
     * doing reflection, and calling prepare().
     */
    @Test
    public void benchmark8_contextCreationInstrumented() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();

        System.out.println();
        System.out.println("=== Benchmark 8: XQueryContext Creation (Instrumented) ===");

        // Get module count from config
        final Map<String, Class<Module>> builtInModules = (Map<String, Class<Module>>)
                pool.getConfiguration().getProperty(XQueryContext.PROPERTY_BUILT_IN_MODULES);
        final int moduleCount = builtInModules != null ? builtInModules.size() : 0;
        System.out.printf("  Configured built-in modules: %d%n", moduleCount);
        if (builtInModules != null) {
            for (final Map.Entry<String, Class<Module>> entry : builtInModules.entrySet()) {
                System.out.printf("    %s → %s%n", entry.getKey(), entry.getValue().getSimpleName());
            }
        }

        // Warm up
        for (int i = 0; i < 50; i++) {
            new XQueryContext(pool);
        }

        // Measure context creation time
        final List<Long> times = new ArrayList<>(ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            final long start = System.nanoTime();
            final XQueryContext ctx = new XQueryContext(pool);
            times.add(System.nanoTime() - start);
        }

        printStats("Context creation (" + moduleCount + " modules)", times, "ns");

        // Now verify: how many modules are actually in a fresh context?
        final XQueryContext freshCtx = new XQueryContext(pool);
        int loadedModuleCount = 0;
        final Set<String> loadedNamespaces = new HashSet<>();
        for (final Iterator<Module> it = freshCtx.getModules(); it.hasNext(); ) {
            final Module mod = it.next();
            loadedModuleCount++;
            loadedNamespaces.add(mod.getNamespaceURI());
        }
        System.out.printf("  Modules in fresh context: %d%n", loadedModuleCount);
        System.out.printf("  Unique namespaces: %d%n", loadedNamespaces.size());

        // Critical check: are module INSTANCES shared between contexts?
        final XQueryContext ctx1 = new XQueryContext(pool);
        final XQueryContext ctx2 = new XQueryContext(pool);
        int sharedInstances = 0;
        int distinctInstances = 0;
        for (final Iterator<Module> it1 = ctx1.getModules(); it1.hasNext(); ) {
            final Module mod1 = it1.next();
            final Module[] mods2 = ctx2.getModules(mod1.getNamespaceURI());
            if (mods2 != null && mods2.length > 0) {
                if (mod1 == mods2[0]) {
                    sharedInstances++;
                } else {
                    distinctInstances++;
                }
            }
        }
        System.out.printf("  ** Module instances shared between contexts: %d%n", sharedInstances);
        System.out.printf("  ** Module instances DISTINCT (re-created): %d%n", distinctInstances);
        if (distinctInstances > 0) {
            System.out.println("  ⚠ CONFIRMED: Modules are RE-INSTANTIATED per context (not shared)");
        } else {
            System.out.println("  ✓ Modules are shared between contexts");
        }
    }

    /**
     * Benchmark 9: Per-module instantiation cost breakdown.
     *
     * Measures the cost of reflection + newInstance() + prepare() for each
     * module individually by timing the module constructor and prepare() calls.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void benchmark9_perModuleInstantiationCost() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();

        System.out.println();
        System.out.println("=== Benchmark 9: Per-Module Instantiation Cost ===");

        final Map<String, Class<Module>> builtInModules = (Map<String, Class<Module>>)
                pool.getConfiguration().getProperty(XQueryContext.PROPERTY_BUILT_IN_MODULES);
        if (builtInModules == null) {
            System.out.println("  No built-in modules configured");
            return;
        }

        final Map<String, Map<String, List<? extends Object>>> moduleParams =
                (Map<String, Map<String, List<? extends Object>>>)
                        pool.getConfiguration().getProperty(XQueryContext.PROPERTY_MODULE_PARAMETERS);

        System.out.printf("  %-45s %12s %12s %12s%n",
                "Module", "Constructor", "prepare()", "Total");
        System.out.printf("  %-45s %12s %12s %12s%n",
                "------", "-----------", "---------", "-----");

        long grandTotal = 0;

        for (final Map.Entry<String, Class<Module>> entry : builtInModules.entrySet()) {
            final String ns = entry.getKey();
            final Class<Module> moduleClass = entry.getValue();

            // Find constructor
            Constructor<Module> constructor;
            try {
                constructor = moduleClass.getConstructor(Map.class);
            } catch (final NoSuchMethodException e) {
                try {
                    constructor = moduleClass.getConstructor();
                } catch (final NoSuchMethodException e2) {
                    System.out.printf("  %-45s  NO CONSTRUCTOR%n", moduleClass.getSimpleName());
                    continue;
                }
            }

            // Time constructor
            final int runs = 100;
            long constructorTotal = 0;
            long prepareTotal = 0;
            Module lastModule = null;

            for (int i = 0; i < runs; i++) {
                final long ctorStart = System.nanoTime();
                final Module module;
                if (constructor.getParameterCount() == 1) {
                    module = constructor.newInstance(moduleParams != null ? moduleParams.get(ns) : null);
                } else {
                    module = constructor.newInstance();
                }
                constructorTotal += System.nanoTime() - ctorStart;

                if (module instanceof InternalModule) {
                    // prepare() needs a context
                    final XQueryContext tempCtx = new XQueryContext(pool);
                    final long prepStart = System.nanoTime();
                    ((InternalModule) module).prepare(tempCtx);
                    prepareTotal += System.nanoTime() - prepStart;
                }
                lastModule = module;
            }

            final long ctorAvg = constructorTotal / runs;
            final long prepAvg = prepareTotal / runs;
            final long total = ctorAvg + prepAvg;
            grandTotal += total;

            System.out.printf("  %-45s %,10d ns %,10d ns %,10d ns%n",
                    moduleClass.getSimpleName(), ctorAvg, prepAvg, total);
        }

        System.out.printf("  %-45s %12s %12s %,10d ns%n",
                "GRAND TOTAL (per context)", "", "", grandTotal);
        System.out.printf("  = %.1f µs per context creation from module instantiation%n",
                grandTotal / 1000.0);
    }

    /**
     * Benchmark 10: Compilation cost with varying function resolution.
     *
     * Compiles queries that reference functions from different numbers of
     * modules to see if function resolution scales with module count.
     */
    @Test
    public void benchmark10_compilationFunctionResolution() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();

        System.out.println();
        System.out.println("=== Benchmark 10: Compilation Cost — Function Resolution ===");

        try (final DBBroker broker = pool.get(Optional.empty())) {
            final XQuery xquery = pool.getXQueryService();

            // Query using only built-in fn: functions (no module import)
            final String q1 = "count(1 to 10)";
            // Query using util: module function
            final String q2 = "string-length(util:uuid())";
            // Query using multiple module functions
            final String q3 = "let $x := util:uuid() " +
                    "let $y := system:get-version() " +
                    "return string-length($x) + string-length($y)";
            // Query with many function calls to stress resolution
            final String q4 = "let $a := count(1 to 10) " +
                    "let $b := sum(1 to 10) " +
                    "let $c := string-length('hello') " +
                    "let $d := ceiling(3.14) " +
                    "let $e := floor(3.14) " +
                    "let $f := round(3.14) " +
                    "let $g := abs(-1) " +
                    "let $h := max((1,2,3)) " +
                    "let $i := min((1,2,3)) " +
                    "return $a + $b + $c + $d + $e + $f + $g + $h + $i";

            final String[][] queries = {
                    {q1, "Simple (1 fn: call)"},
                    {q2, "Single module (util:uuid)"},
                    {q3, "Two modules (util + system)"},
                    {q4, "Many fn: calls (9 functions)"},
            };

            for (final String[] qpair : queries) {
                // Warm up
                for (int i = 0; i < 50; i++) {
                    final XQueryContext ctx = new XQueryContext(pool);
                    xquery.compile(ctx, qpair[0]);
                }

                // Measure compile-only time (includes context creation)
                final List<Long> compileTimes = new ArrayList<>();
                for (int i = 0; i < ITERATIONS; i++) {
                    final XQueryContext ctx = new XQueryContext(pool);
                    final long start = System.nanoTime();
                    xquery.compile(ctx, qpair[0]);
                    compileTimes.add(System.nanoTime() - start);
                }
                printStats("Compile: " + qpair[1], compileTimes, "ns");
            }
        }
    }

    private static void printStats(String label, List<Long> values, String unit) {
        if (values.isEmpty()) {
            System.out.printf("  %s: no data%n", label);
            return;
        }
        final long avg = values.stream().mapToLong(Long::longValue).sum() / values.size();
        final long median = values.stream().sorted().skip(values.size() / 2).findFirst().orElse(0L);
        final long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
        final long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf("  **%s**: avg=%,d %s, median=%,d %s, min=%,d, max=%,d (n=%d)%n",
                label, avg, unit, median, unit, min, max, values.size());
    }
}
