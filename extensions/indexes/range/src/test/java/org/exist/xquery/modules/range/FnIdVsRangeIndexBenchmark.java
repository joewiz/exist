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
/*
 * Phase A diagnostic harness for "fn:id and the legacy range index" investigation.
 * NOT for upstream merge -- measurement only. Opt-in via -Dexist.fnid.bench=true.
 *
 * Compares:
 *   (1) fn:id("X")           -- forced legacy NativeValueIndex via FunId.getId()
 *   (2) //*[@xml:id = "X"]   -- general comparison; uses configured Lucene-backed range index
 *   (3) //*[@xml:id eq "X"]  -- value comparison variant
 *   (4) for $v in $TERMS return //*[@xml:id = $v]  -- loop-bound shape (#6300 / #873 family)
 *
 * Two configs (legacy auto-indexes Type.ID unconditionally; only difference is whether
 * a <range> block is configured for @xml:id):
 *   L: legacy only         -- no <range> block; (2)/(3)/(4) fall back to scan
 *   N: legacy + new range  -- <range><create qname="@xml:id" type="xs:string"/></range>
 *
 * Corpus scales: 10K and 100K elements with unique xml:id, single document per scale.
 *
 * Run with:
 *   mvn test -pl extensions/indexes/range \
 *     -Dtest=FnIdVsRangeIndexBenchmark -DfailIfNoTests=false \
 *     -Dexist.fnid.bench=true \
 *     -Ddependency-check.skip=true -Ddocker=false
 */
package org.exist.xquery.modules.range;

import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.CollectionConfigurationException;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.collections.triggers.TriggerException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.LockException;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.Sequence;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class FnIdVsRangeIndexBenchmark {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static final int[] SCALES = parseIntList(System.getProperty("exist.fnid.bench.scales", "10000,100000"));
    private static final int WARMUP_ITERS = Integer.getInteger("exist.fnid.bench.warmup", 200);
    private static final int MEASURE_ITERS = Integer.getInteger("exist.fnid.bench.iters", 2_000);
    private static final int LOOP_BOUND_KEYS = 50;          // for shape (4)
    /** Scales >= this skip shape (4) for-bound (otherwise minutes per cell). */
    private static final int FORBOUND_SKIP_SCALE = Integer.getInteger("exist.fnid.bench.forbound.skipScale", 50_000);
    /** Hard-skip shape (4) entirely (useful when F1 bug makes N for-bound intractable). */
    private static final boolean SKIP_FORBOUND = Boolean.getBoolean("exist.fnid.bench.skip.forbound");

    private static final String CONFIG_L =
            "<collection xmlns=\"http://exist-db.org/collection-config/1.0\">"
                    + "<index/>"
                    + "</collection>";

    private static final String CONFIG_N =
            "<collection xmlns=\"http://exist-db.org/collection-config/1.0\""
                    + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\""
                    + " xmlns:xml=\"http://www.w3.org/XML/1998/namespace\">"
                    + "<index>"
                    + "<range>"
                    + "<create qname=\"@xml:id\" type=\"xs:string\"/>"
                    + "</range>"
                    + "</index>"
                    + "</collection>";

    @BeforeClass
    public static void gate() {
        Assume.assumeTrue(
            "fn:id-vs-range-index benchmark is opt-in; set -Dexist.fnid.bench=true",
            "true".equalsIgnoreCase(System.getProperty("exist.fnid.bench"))
        );
    }

    @Test
    public void run() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final Map<String, Map<String, Long>> table = new LinkedHashMap<>();

        for (int scale : SCALES) {
            for (String config : new String[]{"L", "N"}) {
                final String key = config + "@" + scale;
                System.out.println("\n=== Config " + config + " / scale " + scale + " ===");
                final Map<String, Long> shapeTimings = runOneConfig(pool, config, scale);
                table.put(key, shapeTimings);
            }
        }

        printTable(table);
    }

    private Map<String, Long> runOneConfig(final BrokerPool pool, final String config, final int scale) throws Exception {
        final XmldbURI col = XmldbURI.ROOT_COLLECTION_URI.append("fnid-bench-" + config + "-" + scale);
        final String configXml = "L".equals(config) ? CONFIG_L : CONFIG_N;

        final long startSetup = System.nanoTime();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            createCollectionAndStore(pool, broker, col, configXml, scale);
        }
        System.out.printf("  [setup config=%s scale=%d took %.1fs]%n",
                config, scale, (System.nanoTime() - startSetup) / 1e9);

        final String hitId = "id" + (scale / 2);

        final Random rng = new Random(0xC01DBEEFL ^ scale);
        final StringBuilder loopKeys = new StringBuilder("(");
        for (int i = 0; i < LOOP_BOUND_KEYS; i++) {
            if (i > 0) loopKeys.append(',');
            loopKeys.append('"').append("id").append(rng.nextInt(scale)).append('"');
        }
        loopKeys.append(')');

        final String collectionUri = col.toString();
        final String fnIdQuery =
                "fn:id(\"" + hitId + "\", collection(\"" + collectionUri + "\")/*)";
        final String generalEqQuery =
                "collection(\"" + collectionUri + "\")//*[@xml:id = \"" + hitId + "\"]";
        final String valueEqQuery =
                "collection(\"" + collectionUri + "\")//*[@xml:id eq \"" + hitId + "\"]";
        final String forBoundQuery =
                "for $v in " + loopKeys.toString()
                        + " return collection(\"" + collectionUri + "\")//*[@xml:id = $v]";

        final Map<String, Long> timings = new LinkedHashMap<>();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            timings.put("(1) fn:id",          time(broker, fnIdQuery,    WARMUP_ITERS, MEASURE_ITERS));
            timings.put("(2) //*[@xml:id =]", time(broker, generalEqQuery, WARMUP_ITERS, MEASURE_ITERS));
            timings.put("(3) //*[@xml:id eq]",time(broker, valueEqQuery,   WARMUP_ITERS, MEASURE_ITERS));
            // for-bound shape is N * scan; skip at very large scales to keep wall time bounded.
            // Also skip entirely if the F1 bug is making this shape intractable.
            if (SKIP_FORBOUND) {
                System.out.println("  [shape (4) for-bound: SKIPPED via -Dexist.fnid.bench.skip.forbound]");
            } else if (scale < FORBOUND_SKIP_SCALE) {
                timings.put("(4) for-bound",      time(broker, forBoundQuery,
                                                       Math.max(WARMUP_ITERS / 4, 10),
                                                       Math.max(MEASURE_ITERS / 4, 25)));
            } else {
                // Single-iter probe: just establish the hits count + a single timing for L vs N
                timings.put("(4) for-bound", time(broker, forBoundQuery, 2, 5));
            }
        } finally {
            cleanup(pool, col);
        }
        return timings;
    }

    /** Returns nanoseconds per iteration (median across measurement run). */
    private long time(final DBBroker broker, final String query, final int warmup, final int measure)
            throws XPathException, PermissionDeniedException {
        final BrokerPool pool = broker.getBrokerPool();
        final XQuery xqs = pool.getXQueryService();
        final XQueryContext ctx = new XQueryContext(pool);

        final CompiledXQuery compiled = xqs.compile(ctx, query);

        long firstHits = -1;
        for (int i = 0; i < warmup; i++) {
            final Sequence r = xqs.execute(broker, compiled, null);
            if (firstHits < 0) firstHits = r.getItemCount();
        }

        final long[] samples = new long[measure];
        for (int i = 0; i < measure; i++) {
            final long t0 = System.nanoTime();
            xqs.execute(broker, compiled, null);
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);
        final long median = samples[measure / 2];
        final long p10 = samples[measure / 10];
        final long p90 = samples[(measure * 9) / 10];
        System.out.printf("  %-32s hits=%-6d median=%7.1f us  p10=%7.1f  p90=%7.1f  (n=%d)%n",
                trim(query), firstHits, median / 1000.0, p10 / 1000.0, p90 / 1000.0, measure);
        return median;
    }

    private static String trim(final String q) {
        final String s = q.replaceAll("\\s+", " ");
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private void createCollectionAndStore(final BrokerPool pool, final DBBroker broker,
                                          final XmldbURI colUri, final String configXml, final int scale)
            throws LockException, TriggerException, PermissionDeniedException, IOException, EXistException,
            CollectionConfigurationException, SAXException {
        final TransactionManager tm = pool.getTransactionManager();
        try (final Txn txn = tm.beginTransaction()) {
            final Collection col = broker.getOrCreateCollection(txn, colUri);
            broker.saveCollection(txn, col);

            final CollectionConfigurationManager cm = pool.getConfigurationManager();
            cm.addConfiguration(txn, broker, col, configXml);

            final StringBuilder doc = new StringBuilder(scale * 30);
            doc.append("<root xmlns:xml=\"http://www.w3.org/XML/1998/namespace\">");
            for (int i = 0; i < scale; i++) {
                doc.append("<item xml:id=\"id").append(i).append("\">v").append(i).append("</item>");
            }
            doc.append("</root>");

            broker.storeDocument(txn, XmldbURI.create("data.xml"),
                    new StringInputSource(doc.toString()), MimeType.XML_TYPE, col);
            broker.reindexCollection(txn, col.getURI());
            tm.commit(txn);
        }
    }

    private void cleanup(final BrokerPool pool, final XmldbURI col) {
        final TransactionManager tm = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn txn = tm.beginTransaction();
             final Collection c = broker.openCollection(col, org.exist.storage.lock.Lock.LockMode.WRITE_LOCK)) {
            if (c != null) broker.removeCollection(txn, c);
            tm.commit(txn);
        } catch (Exception e) {
            System.err.println("cleanup failed for " + col + ": " + e);
        }
    }

    private void printTable(final Map<String, Map<String, Long>> table) {
        System.out.println("\n========================================");
        System.out.println("  Phase A results table (median ns/iter)");
        System.out.println("========================================");
        System.out.printf("%-32s", "shape");
        for (String key : table.keySet()) System.out.printf("%14s", key);
        System.out.println();
        if (table.isEmpty()) return;
        for (String shape : table.values().iterator().next().keySet()) {
            System.out.printf("%-32s", shape);
            for (Map<String, Long> col : table.values()) {
                final Long v = col.get(shape);
                System.out.printf("%14s", v == null ? "-" : String.format("%.1f us", v / 1000.0));
            }
            System.out.println();
        }
        System.out.println();

        System.out.println("Ratio of each shape vs (1) fn:id under same config:");
        System.out.printf("%-32s", "shape");
        for (String key : table.keySet()) System.out.printf("%14s", key);
        System.out.println();
        for (String shape : table.values().iterator().next().keySet()) {
            System.out.printf("%-32s", shape);
            for (Map<String, Long> col : table.values()) {
                final Long v = col.get(shape);
                final Long base = col.get("(1) fn:id");
                if (v == null || base == null || base == 0) {
                    System.out.printf("%14s", "-");
                } else {
                    System.out.printf("%13.2fx", (double) v / base);
                }
            }
            System.out.println();
        }
        System.out.println("========================================\n");
    }

    private static int[] parseIntList(final String csv) {
        final String[] parts = csv.split(",");
        final int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }
}
