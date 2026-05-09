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

import jdk.jfr.Recording;
import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Diagnostic benchmark for issue #3406 (FLWOR + for-each-pair, n^2 nested join).
 *
 * The reduced reproducer from the issue body:
 *
 * <pre>
 * declare function local:compare($i1, $i2) { $i1 eq $i2 };
 * let $length := N
 * let $seq := (1 to $length) ! map { "n": ., "values": ("a"||., "b"||., "c"||.) }
 * for $n1 in $seq, $n2 in $seq
 * let $alignments := for-each-pair($n1?values, $n2?values, local:compare#2)
 * return
 *     if (every $alignment in $alignments satisfies $alignment) then "yay" else ()
 * </pre>
 *
 * Run with:
 * <pre>
 *   mvn test -pl exist-core -Dtest=Issue3406Benchmark \
 *       -Dexist.run.benchmarks=true -Ddependency-check.skip=true -Ddocker=false
 * </pre>
 */
public class Issue3406Benchmark {

    @ClassRule
    public static final ExistXmldbEmbeddedServer server =
            new ExistXmldbEmbeddedServer(false, true, true);

    @BeforeClass
    public static void assumeBenchmarks() {
        Assume.assumeTrue("Set -Dexist.run.benchmarks=true to enable.",
                Boolean.getBoolean("exist.run.benchmarks"));
    }

    /** As-written reproducer at the requested length. */
    private static String asWrittenQuery(final int length) {
        return "xquery version \"3.1\";\n"
                + "declare function local:compare($i1, $i2) { $i1 eq $i2 };\n"
                + "let $length := " + length + "\n"
                + "let $seq := (1 to $length) ! map { \"n\": ., \"values\": (\"a\" || ., \"b\" || ., \"c\" || .) }\n"
                + "return\n"
                + "  count(\n"
                + "    for $n1 in $seq, $n2 in $seq\n"
                + "    let $alignments := for-each-pair($n1?values, $n2?values, local:compare#2)\n"
                + "    return\n"
                + "      if (every $alignment in $alignments satisfies $alignment) then\n"
                + "        \"yay\"\n"
                + "      else\n"
                + "        ()\n"
                + "  )";
    }

    /**
     * Variant 1: replace for-each-pair with a hand-written FLWOR. Isolates HOF
     * overhead from the outer nested-loop cost.
     */
    private static String inlineFlworQuery(final int length) {
        return "xquery version \"3.1\";\n"
                + "let $length := " + length + "\n"
                + "let $seq := (1 to $length) ! map { \"n\": ., \"values\": (\"a\" || ., \"b\" || ., \"c\" || .) }\n"
                + "return\n"
                + "  count(\n"
                + "    for $n1 in $seq, $n2 in $seq\n"
                + "    let $a := $n1?values\n"
                + "    let $b := $n2?values\n"
                + "    let $alignments :=\n"
                + "      for $i in 1 to (if (count($a) lt count($b)) then count($a) else count($b))\n"
                + "      return $a[$i] eq $b[$i]\n"
                + "    return\n"
                + "      if (every $alignment in $alignments satisfies $alignment) then\n"
                + "        \"yay\"\n"
                + "      else\n"
                + "        ()\n"
                + "  )";
    }

    /**
     * Variant 2: skip the every-quantifier — just count for-each-pair calls.
     * Isolates HOF dispatch from the every-quantifier cost.
     */
    private static String hofOnlyQuery(final int length) {
        return "xquery version \"3.1\";\n"
                + "declare function local:compare($i1, $i2) { $i1 eq $i2 };\n"
                + "let $length := " + length + "\n"
                + "let $seq := (1 to $length) ! map { \"n\": ., \"values\": (\"a\" || ., \"b\" || ., \"c\" || .) }\n"
                + "return\n"
                + "  count(\n"
                + "    for $n1 in $seq, $n2 in $seq\n"
                + "    return for-each-pair($n1?values, $n2?values, local:compare#2)\n"
                + "  )";
    }

    /**
     * Variant 3: only the outer nested loop, no inner for-each-pair, no every.
     * Establishes the floor cost of the n^2 nested-loop join itself.
     */
    private static String outerLoopOnlyQuery(final int length) {
        return "xquery version \"3.1\";\n"
                + "let $length := " + length + "\n"
                + "let $seq := (1 to $length) ! map { \"n\": ., \"values\": (\"a\" || ., \"b\" || ., \"c\" || .) }\n"
                + "return\n"
                + "  count(\n"
                + "    for $n1 in $seq, $n2 in $seq\n"
                + "    return $n1?n + $n2?n\n"
                + "  )";
    }

    private static long timeQuery(final String query, final int warmup, final int iters)
            throws XMLDBException {
        for (int i = 0; i < warmup; i++) {
            server.executeQuery(query);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iters; i++) {
            final long t0 = System.nanoTime();
            final ResourceSet rs = server.executeQuery(query);
            final long elapsed = System.nanoTime() - t0;
            best = Math.min(best, elapsed);
            // Touch result size so it isn't optimized away.
            rs.getSize();
        }
        return best / 1_000_000L;
    }

    @Test
    public void scaling() throws XMLDBException {
        System.out.println("\n=== Issue #3406 scaling benchmark ===\n");
        System.out.println("All times are best-of-N in milliseconds.");
        System.out.println("asWritten = original reproducer with for-each-pair");
        System.out.println("hofOnly   = same nested loop, no every-quantifier");
        System.out.println("outerOnly = nested loop only (floor of n^2 join cost)");
        System.out.println("inlineFlwor = for-each-pair replaced by inline FLWOR\n");
        System.out.printf("%-7s %12s %12s %12s %12s %12s%n",
                "length", "outerOnly", "hofOnly", "asWritten", "inlineFlwor", "ms/length^2");

        // 2020 issue table for reference: lengths 100, 200, 500, 750, 1000, 1250, 1500, 1750, 2000, 3000.
        final int[] lengths = {10, 50, 100, 200, 500, 1000, 2000, 3000};

        for (final int length : lengths) {
            final long warmup = length <= 200 ? 1 : 0;
            final int iters = length <= 200 ? 3 : 1;
            final long outer = timeQuery(outerLoopOnlyQuery(length), (int) warmup, iters);
            final long hof = timeQuery(hofOnlyQuery(length), (int) warmup, iters);
            final long asWritten = timeQuery(asWrittenQuery(length), (int) warmup, iters);
            final long inline = timeQuery(inlineFlworQuery(length), (int) warmup, iters);
            final double msPerL2 = ((double) asWritten) / (((double) length) * length);
            System.out.printf("%-7d %12d %12d %12d %12d %12.6f%n",
                    length, outer, hof, asWritten, inline, msPerL2);
            System.out.flush();
        }
    }

    /**
     * JFR-instrumented run at length=1000. Captures a 60s flight recording while
     * the as-written reproducer runs in a tight loop, so JMC can bucket time by
     * call site (HOF dispatch, FLWOR iteration, FunctionCall.eval, etc.).
     */
    @Test
    public void jfrProfileLength1000() throws IOException, XMLDBException {
        Assume.assumeTrue("Set -Dexist.run.jfr=true to enable JFR capture.",
                Boolean.getBoolean("exist.run.jfr"));

        final int length = 1000;
        final String query = asWrittenQuery(length);

        // Warm up JIT for one iteration.
        server.executeQuery(query).getSize();

        final Path jfrPath = Path.of("/tmp/issue-3406-length-1000.jfr");
        try (final Recording rec = new Recording()) {
            rec.setName("issue-3406-length-1000");
            rec.setDuration(Duration.ofSeconds(60));
            rec.setDestination(jfrPath);
            // Default profiling settings sample method execution + allocation.
            rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            rec.enable("jdk.ObjectAllocationSample");
            rec.start();

            final long endAt = System.nanoTime() + Duration.ofSeconds(55).toNanos();
            int reps = 0;
            while (System.nanoTime() < endAt) {
                server.executeQuery(query).getSize();
                reps++;
            }
            rec.stop();
            System.out.println("\nJFR: completed " + reps + " reps of length=" + length
                    + ", recording at " + jfrPath);
        }
    }
}
