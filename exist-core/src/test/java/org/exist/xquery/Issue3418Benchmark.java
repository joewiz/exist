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

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;

/**
 * Diagnostic benchmark for issue #3418 (nested FLWOR over fn:json-to-xml output).
 *
 * Run with:
 * <pre>
 *   mvn test -pl exist-core -Dtest=Issue3418Benchmark \
 *       -Dexist.run.benchmarks=true -Ddependency-check.skip=true -Ddocker=false
 * </pre>
 */
public class Issue3418Benchmark {

    @ClassRule
    public static final ExistXmldbEmbeddedServer server =
            new ExistXmldbEmbeddedServer(false, true, true);

    @BeforeClass
    public static void assumeBenchmarks() {
        Assume.assumeTrue("Set -Dexist.run.benchmarks=true to enable.",
                Boolean.getBoolean("exist.run.benchmarks"));
    }

    /**
     * Synthesizes a JSON payload mimicking the GitHub /stats/contributors API:
     * an array of N user objects, each with an "author" map and a "weeks" array
     * of 52 entries (timestamp w, commit count c).
     *
     * Year 2020 timestamps span Jan 1 = 1577836800 to Dec 31 = 1609459199.
     * We deterministically distribute timestamps from 2010-01-01 onward.
     */
    private static String synthJson(final int users, final int weeksPerUser) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        // 2010-01-01T00:00:00Z = 1262304000
        final long baseSec = 1262304000L;
        // 1 week = 604800 sec
        for (int u = 0; u < users; u++) {
            if (u > 0) sb.append(',');
            sb.append("{\"author\":{\"login\":\"user").append(u).append("\"},\"total\":");
            sb.append(weeksPerUser);
            sb.append(",\"weeks\":[");
            for (int w = 0; w < weeksPerUser; w++) {
                if (w > 0) sb.append(',');
                final long ts = baseSec + 604800L * w;
                final int commits = ((u + w) % 5);
                sb.append("{\"w\":").append(ts).append(",\"c\":").append(commits)
                        .append(",\"a\":1,\"d\":1}");
            }
            sb.append("]}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String reproducerQuery(final int users, final int weeks,
                                          final int yearStart, final int yearEnd) {
        return "let $json := '" + synthJson(users, weeks) + "'\n"
                + "let $data := fn:json-to-xml($json)\n"
                + "return\n"
                + "<results>{\n"
                + "  for $year in (" + yearStart + " to " + yearEnd + ")\n"
                + "  let $ut-start := (xs:dateTime($year || \"-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "  let $ut-end := (xs:dateTime(($year + 1)|| \"-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "  order by $year descending\n"
                + "  return\n"
                + "    <year number=\"{$year}\">\n"
                + "      {\n"
                + "        for $user-data in $data/fn:array/fn:map\n"
                + "        let $username := $user-data//fn:map[@key eq \"author\"]/fn:string[@key eq \"login\"]/string(.)\n"
                + "        let $commits := fn:sum($user-data/fn:array[@key eq \"weeks\"]/fn:map[fn:number[@key eq \"w\"][xs:int(.) ge $ut-start][xs:int(.) lt $ut-end]]/fn:number[@key eq \"c\"]/xs:int(.))\n"
                + "        where $commits gt 0\n"
                + "        order by $commits descending\n"
                + "        return\n"
                + "        <user name=\"{$username}\">{$commits}</user>\n"
                + "      }\n"
                + "    </year>\n"
                + "}</results>";
    }

    /**
     * Manually-hoisted variant: $data/fn:array/fn:map is bound to $users outside
     * the outer year loop. Tests the loop-invariant-input hypothesis.
     */
    private static String hoistedInputQuery(final int users, final int weeks,
                                             final int yearStart, final int yearEnd) {
        return "let $json := '" + synthJson(users, weeks) + "'\n"
                + "let $data := fn:json-to-xml($json)\n"
                + "let $users := $data/fn:array/fn:map\n"
                + "return\n"
                + "<results>{\n"
                + "  for $year in (" + yearStart + " to " + yearEnd + ")\n"
                + "  let $ut-start := (xs:dateTime($year || \"-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "  let $ut-end := (xs:dateTime(($year + 1)|| \"-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "  order by $year descending\n"
                + "  return\n"
                + "    <year number=\"{$year}\">\n"
                + "      {\n"
                + "        for $user-data in $users\n"
                + "        let $username := $user-data//fn:map[@key eq \"author\"]/fn:string[@key eq \"login\"]/string(.)\n"
                + "        let $commits := fn:sum($user-data/fn:array[@key eq \"weeks\"]/fn:map[fn:number[@key eq \"w\"][xs:int(.) ge $ut-start][xs:int(.) lt $ut-end]]/fn:number[@key eq \"c\"]/xs:int(.))\n"
                + "        where $commits gt 0\n"
                + "        order by $commits descending\n"
                + "        return\n"
                + "        <user name=\"{$username}\">{$commits}</user>\n"
                + "      }\n"
                + "    </year>\n"
                + "}</results>";
    }

    /**
     * Aggressively hoisted variant: precompute per-user (username, weeks-map, w-as-int)
     * tuples outside the outer year loop, so the per-year inner loop only does the
     * year-specific filter+sum.
     */
    private static String fullyHoistedQuery(final int users, final int weeks,
                                             final int yearStart, final int yearEnd) {
        return "let $json := '" + synthJson(users, weeks) + "'\n"
                + "let $data := fn:json-to-xml($json)\n"
                + "let $user-info :=\n"
                + "  for $user-data in $data/fn:array/fn:map\n"
                + "  return map {\n"
                + "    'username': $user-data//fn:map[@key eq \"author\"]/fn:string[@key eq \"login\"]/string(.),\n"
                + "    'weeks':    $user-data/fn:array[@key eq \"weeks\"]/fn:map\n"
                + "  }\n"
                + "return\n"
                + "<results>{\n"
                + "  for $year in (" + yearStart + " to " + yearEnd + ")\n"
                + "  let $ut-start := (xs:dateTime($year || \"-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "  let $ut-end := (xs:dateTime(($year + 1)|| \"-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "  order by $year descending\n"
                + "  return\n"
                + "    <year number=\"{$year}\">\n"
                + "      {\n"
                + "        for $u in $user-info\n"
                + "        let $commits := fn:sum($u?weeks[fn:number[@key eq \"w\"][xs:int(.) ge $ut-start][xs:int(.) lt $ut-end]]/fn:number[@key eq \"c\"]/xs:int(.))\n"
                + "        where $commits gt 0\n"
                + "        order by $commits descending\n"
                + "        return\n"
                + "        <user name=\"{$u?username}\">{$commits}</user>\n"
                + "      }\n"
                + "    </year>\n"
                + "}</results>";
    }

    /** Inner FLWOR alone — no outer year iteration — gives the per-iteration cost baseline. */
    private static String innerOnlyQuery(final int users, final int weeks) {
        return "let $json := '" + synthJson(users, weeks) + "'\n"
                + "let $data := fn:json-to-xml($json)\n"
                + "let $ut-start := (xs:dateTime(\"2015-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "let $ut-end := (xs:dateTime(\"2016-01-01T00:00:00-00:00\") - xs:dateTime(\"1970-01-01T00:00:00-00:00\")) div xs:dayTimeDuration('PT1S')\n"
                + "return\n"
                + "  for $user-data in $data/fn:array/fn:map\n"
                + "  let $username := $user-data//fn:map[@key eq \"author\"]/fn:string[@key eq \"login\"]/string(.)\n"
                + "  let $commits := fn:sum($user-data/fn:array[@key eq \"weeks\"]/fn:map[fn:number[@key eq \"w\"][xs:int(.) ge $ut-start][xs:int(.) lt $ut-end]]/fn:number[@key eq \"c\"]/xs:int(.))\n"
                + "  where $commits gt 0\n"
                + "  order by $commits descending\n"
                + "  return <user name=\"{$username}\">{$commits}</user>";
    }

    private static long timeQuery(final String query, final int warmup, final int iters)
            throws XMLDBException {
        for (int i = 0; i < warmup; i++) server.executeQuery(query);
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
        System.out.println("\n=== Issue #3418 scaling benchmark ===\n");
        System.out.printf("%-7s %6s %6s %8s %10s %10s %10s %10s%n",
                "users", "weeks", "years", "innerMs", "asWritten", "hoistInput", "fullyHoist", "speedup");

        final int[][] scales = {
                {10, 52, 2005, 2020},   // 10 users x 52 weeks x 16 years
                {30, 52, 2005, 2020},   // 30 users x 52 weeks x 16 years (issue-like)
                {50, 52, 2005, 2020},   // 50 users x 52 weeks x 16 years (issue-like)
                {50, 200, 2005, 2020},  // 50 users x 200 weeks x 16 years (large)
        };

        for (final int[] s : scales) {
            final int users = s[0], weeks = s[1], y0 = s[2], y1 = s[3];
            final int years = y1 - y0 + 1;
            final long innerMs = timeQuery(innerOnlyQuery(users, weeks), 1, 3);
            final long asWrittenMs = timeQuery(reproducerQuery(users, weeks, y0, y1), 1, 3);
            final long hoistInputMs = timeQuery(hoistedInputQuery(users, weeks, y0, y1), 1, 3);
            final long fullyHoistMs = timeQuery(fullyHoistedQuery(users, weeks, y0, y1), 1, 3);
            final double speedup = fullyHoistMs == 0 ? 0 : (double) asWrittenMs / fullyHoistMs;
            System.out.printf("%-7d %6d %6d %8d %10d %10d %10d %9.2fx%n",
                    users, weeks, years, innerMs, asWrittenMs, hoistInputMs, fullyHoistMs, speedup);
            System.out.flush();
        }
        System.out.println();
        System.out.println("Columns:");
        System.out.println("  innerMs    -- inner FLWOR alone (no outer year loop)");
        System.out.println("  asWritten  -- reproducer as in issue #3418");
        System.out.println("  hoistInput -- $data/fn:array/fn:map hoisted to outer let");
        System.out.println("  fullyHoist -- per-user (username, weeks) hoisted to outer let");
        System.out.println("  speedup    -- asWritten / fullyHoist");
    }
}
