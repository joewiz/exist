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
package org.exist.indexing.lucene;

import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.CollectionConfigurationException;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.collections.triggers.TriggerException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.EXistOutputKeys;
import org.exist.storage.serializers.Serializer;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.test.TestConstants;
import org.exist.util.LockException;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.xml.transform.OutputKeys;
import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * Performance benchmark for Lucene match highlighting (Matches API via MemoryIndex).
 *
 * <p>Measures wall-clock time for full-text query + highlighting (util:expand)
 * across different query types and document sizes. Isolates highlighting cost
 * by comparing query-only vs query+expand times.</p>
 *
 * <p>This class intentionally does <em>not</em> end in {@code *Test} so that
 * Surefire will not discover it during normal {@code mvn test} runs.
 * Run explicitly with:</p>
 * <pre>
 *   mvn test -pl extensions/indexes/lucene \
 *       -Dtest=HighlightingBenchmark \
 *       -Dexist.run.benchmarks=true \
 *       -Ddependency-check.skip=true
 * </pre>
 */
public class HighlightingBenchmark {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURE_ITERATIONS = 10;
    private static final int[] DOC_SIZES = {10, 50, 200};

    private static final String CONF =
            "<collection xmlns=\"http://exist-db.org/collection-config/1.0\">" +
            "   <index>" +
            "       <lucene>" +
            "           <text qname=\"para\"/>" +
            "       </lucene>" +
            "   </index>" +
            "</collection>";

    // Words for generating realistic text content
    private static final String[] WORDS = {
        "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
        "in", "a", "forest", "near", "river", "bank", "under", "tall",
        "trees", "with", "green", "leaves", "and", "bright", "flowers",
        "growing", "wild", "along", "path", "through", "ancient", "woods",
        "where", "birds", "sing", "their", "morning", "songs", "while",
        "sun", "rises", "above", "distant", "mountains", "casting", "long",
        "shadows", "across", "meadow", "filled", "golden", "light"
    };

    @BeforeClass
    public static void assumeBenchmarks() {
        Assume.assumeTrue("Benchmarks are disabled. Set -Dexist.run.benchmarks=true to enable.",
                Boolean.getBoolean("exist.run.benchmarks"));
    }

    @BeforeClass
    public static void setUp() throws Exception {
        if (!Boolean.getBoolean("exist.run.benchmarks")) {
            return;
        }
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final TransactionManager transact = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn transaction = transact.beginTransaction()) {
            final Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            broker.saveCollection(transaction, root);
            final CollectionConfigurationManager mgr = pool.getConfigurationManager();
            mgr.addConfiguration(transaction, broker, root, CONF);
            transact.commit(transaction);
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (!Boolean.getBoolean("exist.run.benchmarks")) {
            return;
        }
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final TransactionManager transact = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn transaction = transact.beginTransaction()) {
            final Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            broker.removeCollection(transaction, root);
            transact.commit(transaction);
        }
    }

    // ---- Query type benchmarks ----

    @Test
    public void termQuery() throws Exception {
        System.out.println("\n=== Term Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("term", size, "'quick'");
        }
    }

    @Test
    public void phraseQuery() throws Exception {
        System.out.println("\n=== Phrase Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("phrase", size, "'\"quick brown fox\"'");
        }
    }

    @Test
    public void proximityQuery() throws Exception {
        System.out.println("\n=== Proximity/Near Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("proximity", size,
                    "<query><near slop=\"3\">quick fox</near></query>");
        }
    }

    @Test
    public void wildcardQuery() throws Exception {
        System.out.println("\n=== Wildcard Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("wildcard", size, "'qu*'");
        }
    }

    @Test
    public void fuzzyQuery() throws Exception {
        System.out.println("\n=== Fuzzy Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("fuzzy", size, "'quikc~'");
        }
    }

    @Test
    public void booleanQuery() throws Exception {
        System.out.println("\n=== Boolean Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("boolean", size, "'+quick +lazy +forest'");
        }
    }

    @Test
    public void regexQuery() throws Exception {
        System.out.println("\n=== Regex Query Highlighting ===");
        printHeader();
        for (final int size : DOC_SIZES) {
            storeDocument(size);
            runHighlightBenchmark("regex", size,
                    "<query><regex>qu.*k</regex></query>");
        }
    }

    // ---- Summary comparison ----

    @Test
    public void highlightingOverheadSummary() throws Exception {
        System.out.println("\n=== Highlighting Overhead Summary (size=50) ===");
        System.out.printf("  %-16s  %12s  %12s  %12s  %8s%n",
                "Query Type", "Query (ms)", "Expand (ms)", "Highlight", "Overhead");
        System.out.println("  " + "=".repeat(70));

        final int size = 50;
        storeDocument(size);

        final String[] labels = {"term", "phrase", "proximity", "wildcard", "fuzzy", "boolean", "regex"};
        final String[] queries = {
            "'quick'",
            "'\"quick brown fox\"'",
            "<query><near slop=\"3\">quick fox</near></query>",
            "'qu*'",
            "'quikc~'",
            "'+quick +lazy +forest'",
            "<query><regex>qu.*k</regex></query>"
        };

        for (int i = 0; i < labels.length; i++) {
            final double queryOnly = measureAvg(size, queries[i], false);
            final double withExpand = measureAvg(size, queries[i], true);
            final double highlightCost = withExpand - queryOnly;
            final double overheadPct = queryOnly > 0 ? (highlightCost / queryOnly * 100) : 0;
            System.out.printf("  %-16s  %12.2f  %12.2f  %12.2f  %7.1f%%%n",
                    labels[i], queryOnly, withExpand, highlightCost, overheadPct);
        }
    }

    // ---- Helpers ----

    private void runHighlightBenchmark(final String label, final int size, final String queryExpr)
            throws Exception {
        final double queryOnly = measureAvg(size, queryExpr, false);
        final double withExpand = measureAvg(size, queryExpr, true);
        final double highlightCost = withExpand - queryOnly;
        System.out.printf("  %-16s  size=%3d  query=%8.2f ms  expand=%8.2f ms  highlight=%8.2f ms%n",
                label, size, queryOnly, withExpand, highlightCost);
    }

    private double measureAvg(final int size, final String queryExpr, final boolean expand)
            throws EXistException, PermissionDeniedException, XPathException, SAXException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xquery = pool.getXQueryService();

            final String xq;
            if (expand) {
                xq = "let $hits := collection('" + TestConstants.TEST_COLLECTION_URI + "')//para[ft:query(., " + queryExpr + ")] " +
                     "return for $hit in $hits return util:expand($hit)";
            } else {
                xq = "let $hits := collection('" + TestConstants.TEST_COLLECTION_URI + "')//para[ft:query(., " + queryExpr + ")] " +
                     "return count($hits)";
            }

            // warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                final Sequence seq = xquery.execute(broker, xq, null);
                if (expand && seq.getItemCount() == 0) {
                    System.err.println("  WARNING: no hits for " + queryExpr);
                }
            }

            // measure
            long totalNs = 0;
            for (int i = 0; i < MEASURE_ITERATIONS; i++) {
                final long start = System.nanoTime();
                final Sequence seq = xquery.execute(broker, xq, null);
                // Force serialization for expand to trigger highlighting
                if (expand) {
                    final Properties props = new Properties();
                    props.setProperty(OutputKeys.INDENT, "no");
                    props.setProperty(EXistOutputKeys.HIGHLIGHT_MATCHES, "elements");
                    final Serializer serializer = broker.borrowSerializer();
                    try {
                        serializer.setProperties(props);
                        for (int j = 0; j < seq.getItemCount(); j++) {
                            serializer.serialize((NodeValue) seq.itemAt(j));
                        }
                    } finally {
                        broker.returnSerializer(serializer);
                    }
                }
                totalNs += System.nanoTime() - start;
            }

            return (totalNs / (double) MEASURE_ITERATIONS) / 1_000_000.0;
        }
    }

    private void storeDocument(final int numParagraphs)
            throws EXistException, PermissionDeniedException, IOException, SAXException,
                   LockException, CollectionConfigurationException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final TransactionManager transact = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn transaction = transact.beginTransaction()) {
            final Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            final StringBuilder sb = new StringBuilder("<root>\n");
            final Random rng = new Random(42); // deterministic for reproducibility
            for (int i = 0; i < numParagraphs; i++) {
                sb.append("  <para>");
                // Each paragraph has 15-25 words; every paragraph includes "quick brown fox"
                // to ensure consistent hit counts across sizes
                final int wordsBefore = 5 + rng.nextInt(5);
                final int wordsAfter = 5 + rng.nextInt(10);
                for (int w = 0; w < wordsBefore; w++) {
                    sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
                }
                sb.append("quick brown fox ");
                for (int w = 0; w < wordsAfter; w++) {
                    sb.append(WORDS[rng.nextInt(WORDS.length)]).append(' ');
                }
                // Add "lazy" and "forest" for boolean queries
                if (i % 3 == 0) {
                    sb.append("lazy ");
                }
                if (i % 2 == 0) {
                    sb.append("forest ");
                }
                sb.append("</para>\n");
            }
            sb.append("</root>");

            broker.storeDocument(transaction, XmldbURI.create("bench.xml"),
                    new StringInputSource(sb.toString()), MimeType.XML_TYPE, root);
            transact.commit(transaction);
        }
    }

    private static void printHeader() {
        System.out.printf("  %-16s  %8s  %14s  %14s  %14s%n",
                "Query Type", "Size", "Query (ms)", "Expand (ms)", "Highlight (ms)");
        System.out.println("  " + "=".repeat(74));
    }
}
