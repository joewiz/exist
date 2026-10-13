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
package org.exist.indexing.jmh;

import org.exist.collections.Collection;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.test.TestConstants;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XQuery;
import org.exist.xquery.modules.ngram.NGramSearch;
import org.exist.xquery.pragmas.Optimize;
import org.exist.xquery.value.Sequence;

import java.util.Optional;
import java.util.Random;

/**
 * Reproducer for Juri's residual perf finding on PR #6295: even after #6300,
 * function-documentation app's ngram search is still slow.
 *
 * The app's actual search shape (from function-documentation/.build/modules/app.xqm:107-115):
 *   $app:data//(
 *       xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, $q)]
 *       |
 *       xqdoc:module[ngram:contains(xqdoc:comment/xqdoc:description, $q)]
 *   )
 *
 * where $app:data is a module-level variable bound to collection(...)/xqdoc:xqdoc.
 *
 * Three suspect features:
 *   1. Module-level variable indirection (similar shape to GH-873)
 *   2. UnionExpr inside a path: //(A[pred] | B[pred])
 *   3. Multi-step path inside the predicate's first arg (xqdoc:comment/xqdoc:description)
 *
 * This diagnostic loads a synthesised xqdoc-shaped corpus and runs progressively
 * simpler variants to isolate which feature causes the slowness.
 */
public final class FundocsSearchDiagnostic {

    private FundocsSearchDiagnostic() { }

    private static final int NUM_DOCS = Integer.getInteger("docs", 50);
    private static final int FUNCS_PER_DOC = Integer.getInteger("funcs", 30);
    private static final String SEARCH_TERM = "calculates";

    public static void main(final String[] args) throws Exception {
        final ExistEmbeddedServer server = new ExistEmbeddedServer(true, true);
        server.startDb();
        final BrokerPool pool = server.getBrokerPool();
        try {
            loadCorpus(pool);
            final XQuery xquery = pool.getXQueryService();
            final String c = TestConstants.TEST_COLLECTION_URI.toString();

            // Variant 1: the actual fundocs-app shape -- module-var-equivalent + union of predicates
            // (using a let to simulate module-level $app:data)
            final String fundocsExact = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    let $app-data := collection('%s')/xqdoc:xqdoc
                    return count(
                        $app-data//(
                            xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                            |
                            xqdoc:module[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                        )
                    )
                    """.formatted(c, SEARCH_TERM, SEARCH_TERM);

            // Variant 2: same but no let -- direct path
            final String directNoLet = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    count(
                        collection('%s')/xqdoc:xqdoc//(
                            xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                            |
                            xqdoc:module[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                        )
                    )
                    """.formatted(c, SEARCH_TERM, SEARCH_TERM);

            // Variant 3: split the union into two separate paths, no let
            final String unionSplitNoLet = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    count(
                        collection('%s')/xqdoc:xqdoc//xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                        |
                        collection('%s')/xqdoc:xqdoc//xqdoc:module[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                    )
                    """.formatted(c, SEARCH_TERM, c, SEARCH_TERM);

            // Variant 4: just one branch, no let, simple path. (the "should be fast" baseline)
            final String simpleBaseline = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    count(
                        collection('%s')//xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                    )
                    """.formatted(c, SEARCH_TERM);

            // Variant 5: same as 4 but with a let-bound collection (isolate let effect)
            final String simpleWithLet = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    let $app-data := collection('%s')/xqdoc:xqdoc
                    return count(
                        $app-data//xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                    )
                    """.formatted(c, SEARCH_TERM);

            // Variant 6: union but no nested predicate path (single-step predicate target)
            final String unionWithSimplePred = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    count(
                        collection('%s')/xqdoc:xqdoc//(
                            xqdoc:function[ngram:contains(xqdoc:name, '%s')]
                            |
                            xqdoc:module[ngram:contains(xqdoc:name, '%s')]
                        )
                    )
                    """.formatted(c, SEARCH_TERM, SEARCH_TERM);

            // Variant 7: deepest path -- shifted predicate from xqdoc:comment/xqdoc:description
            // to a single qname with index defined directly (apples-to-apples for the predicate's index target)
            final String singleStepPred = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    count(
                        collection('%s')//xqdoc:description[ngram:contains(., '%s')]
                    )
                    """.formatted(c, SEARCH_TERM);

            // Variant 9: union of names, no predicate at all -- isolates path-traversal cost from optimizer behaviour
            final String unionNoPred = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    count(
                        collection('%s')/xqdoc:xqdoc//(xqdoc:function | xqdoc:module)
                    )
                    """.formatted(c);

            // Variant 10: split paths, no predicate -- the should-be-fast equivalent
            final String splitNoPred = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    count(
                        collection('%s')/xqdoc:xqdoc//xqdoc:function
                        |
                        collection('%s')/xqdoc:xqdoc//xqdoc:module
                    )
                    """.formatted(c, c);

            // Variant 8: search-everywhere shape (the larger union from app.xqm:128-152)
            final String searchEverywhere = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    declare namespace ngram="http://exist-db.org/xquery/ngram";
                    let $app-data := collection('%s')/xqdoc:xqdoc
                    return count(
                        $app-data//(
                            xqdoc:function[ngram:contains(xqdoc:name, '%s')]
                            |
                            xqdoc:function[ngram:contains(xqdoc:signature, '%s')]
                            |
                            xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                            |
                            xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:param, '%s')]
                            |
                            xqdoc:function[ngram:contains(xqdoc:comment/xqdoc:return, '%s')]
                            |
                            xqdoc:module[ngram:contains(xqdoc:comment/xqdoc:description, '%s')]
                            |
                            xqdoc:module[ngram:contains(xqdoc:name, '%s')]/..//xqdoc:function
                        )
                    )
                    """.formatted(c, SEARCH_TERM, SEARCH_TERM, SEARCH_TERM, SEARCH_TERM, SEARCH_TERM, SEARCH_TERM, SEARCH_TERM);

            System.out.printf("Corpus: %d modules x %d functions = %d functions total%n",
                    NUM_DOCS, FUNCS_PER_DOC, NUM_DOCS * FUNCS_PER_DOC);

            warm(pool, xquery, simpleBaseline);

            run(pool, xquery, "(7) simple-step predicate (index hit)   ", singleStepPred);
            run(pool, xquery, "(4) simple baseline, simple path        ", simpleBaseline);
            run(pool, xquery, "(5) simple but with let-bound collection", simpleWithLet);
            run(pool, xquery, "(6) union with simple-step pred         ", unionWithSimplePred);
            run(pool, xquery, "(2) union with multi-step pred, no let  ", directNoLet);
            run(pool, xquery, "(3) union split into 2 paths, no let    ", unionSplitNoLet);
            run(pool, xquery, "(1) ACTUAL fundocs shape                ", fundocsExact);
            run(pool, xquery, "(8) search-everywhere (7-branch union)  ", searchEverywhere);
            run(pool, xquery, "(9) union NO-PRED                       ", unionNoPred);
            run(pool, xquery, "(10) split-paths NO-PRED                ", splitNoPred);

            // Variant 11: parens around single name -- isolates "parens" from "union"
            final String parensSingle = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    count(collection('%s')/xqdoc:xqdoc//(xqdoc:function))
                    """.formatted(c);
            run(pool, xquery, "(11) //(xqdoc:function) parens, no union", parensSingle);

            // Variant 12: full descendant scan baseline
            final String allDescendants = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    count(collection('%s')/xqdoc:xqdoc//*)
                    """.formatted(c);
            run(pool, xquery, "(12) //* full descendant scan           ", allDescendants);

            // Variant 13: union of three names -- branch count effect
            final String unionThree = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    count(collection('%s')/xqdoc:xqdoc//(xqdoc:function | xqdoc:module | xqdoc:name))
                    """.formatted(c);
            run(pool, xquery, "(13) //(A | B | C) union three names    ", unionThree);

            // Variant 14: descendant-or-self with name test inside union
            final String descendantOrSelf = """
                    declare namespace xqdoc="http://www.xqdoc.org/1.0";
                    count(collection('%s')/xqdoc:xqdoc/descendant::*[self::xqdoc:function or self::xqdoc:module])
                    """.formatted(c);
            run(pool, xquery, "(14) descendant::*[self::A or self::B]  ", descendantOrSelf);
        } finally {
            server.stopDb();
        }
    }

    private static void warm(final BrokerPool pool, final XQuery xquery, final String q) throws Exception {
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            for (int i = 0; i < 3; i++) {
                xquery.execute(broker, q, null).getItemCount();
            }
        }
    }

    private static void run(final BrokerPool pool, final XQuery xquery,
                            final String label, final String query) throws Exception {
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            long best = Long.MAX_VALUE;
            long count = -1;
            int bestPreSel=-1, bestEval=-1, bestUnc=-1, bestOpt=-1, bestAnc=-1;
            long bestAncNanos = -1, bestOptTotalNanos = -1;
            for (int i = 0; i < 3; i++) {
                NGramSearch.preSelectCalls = 0;
                NGramSearch.evalCalls = 0;
                NGramSearch.evalUncached = 0;
                Optimize.evalCalls = 0;
                Optimize.evalOptimized = 0;
                Optimize.ancestorSelectCalls = 0;
                Optimize.ancestorSelectNanos = 0;
                Optimize.evalTotalNanos = 0;
                final long t0 = System.nanoTime();
                final Sequence result = xquery.execute(broker, query, null);
                count = result.itemAt(0).toJavaObject(Long.class);
                final long ns = System.nanoTime() - t0;
                if (ns < best) {
                    best = ns;
                    bestPreSel = NGramSearch.preSelectCalls;
                    bestEval = NGramSearch.evalCalls;
                    bestUnc = NGramSearch.evalUncached;
                    bestOpt = Optimize.evalOptimized;
                    bestAnc = Optimize.ancestorSelectCalls;
                    bestAncNanos = Optimize.ancestorSelectNanos;
                    bestOptTotalNanos = Optimize.evalTotalNanos;
                }
            }
            System.out.printf("%s  count=%-4d  best=%4dms  optMs=%-4d preSel=%-3d eval=%-4d opt=%-3d anc=%-3d ancMs=%dms%n",
                    label, count, best / 1_000_000L, bestOptTotalNanos / 1_000_000L,
                    bestPreSel, bestEval, bestOpt, bestAnc, bestAncNanos / 1_000_000L);
        }
    }

    private static void loadCorpus(final BrokerPool pool) throws Exception {
        final String collectionConfig = """
                <collection xmlns="http://exist-db.org/collection-config/1.0">
                    <index xmlns:xqdoc="http://www.xqdoc.org/1.0">
                        <ngram qname="xqdoc:description"/>
                        <ngram qname="xqdoc:name"/>
                        <ngram qname="xqdoc:signature"/>
                        <ngram qname="xqdoc:param"/>
                        <ngram qname="xqdoc:return"/>
                    </index>
                </collection>
                """;

        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn tx = pool.getTransactionManager().beginTransaction()) {

            final Collection root = broker.getOrCreateCollection(tx, TestConstants.TEST_COLLECTION_URI);
            broker.saveCollection(tx, root);
            pool.getConfigurationManager().addConfiguration(tx, broker, root, collectionConfig);

            final Random rnd = new Random(42);
            for (int doc = 0; doc < NUM_DOCS; doc++) {
                final String name = "module-" + doc;
                final StringBuilder sb = new StringBuilder();
                sb.append("<xqdoc:xqdoc xmlns:xqdoc=\"http://www.xqdoc.org/1.0\">\n");
                sb.append("  <xqdoc:module>\n");
                sb.append("    <xqdoc:uri>http://example.org/").append(name).append("</xqdoc:uri>\n");
                sb.append("    <xqdoc:name>").append(name).append("</xqdoc:name>\n");
                sb.append("    <xqdoc:comment>\n");
                sb.append("      <xqdoc:description>Module ").append(doc).append(" containing assorted helpers and routines.</xqdoc:description>\n");
                sb.append("    </xqdoc:comment>\n");
                sb.append("  </xqdoc:module>\n");
                sb.append("  <xqdoc:functions>\n");
                for (int f = 0; f < FUNCS_PER_DOC; f++) {
                    final String fname = "fn-" + doc + "-" + f;
                    sb.append("    <xqdoc:function arity=\"1\">\n");
                    sb.append("      <xqdoc:name>").append(fname).append("</xqdoc:name>\n");
                    sb.append("      <xqdoc:signature>declare function ").append(fname).append("($x as item()) as item()*</xqdoc:signature>\n");
                    sb.append("      <xqdoc:comment>\n");
                    // ~5% of functions have the search term in the description so we get sensible hit counts
                    final boolean hit = rnd.nextInt(20) == 0;
                    final String descSuffix = hit ? " calculates the answer." : " transforms the value.";
                    sb.append("        <xqdoc:description>Function ").append(fname).append(descSuffix).append("</xqdoc:description>\n");
                    sb.append("        <xqdoc:param>$x the input</xqdoc:param>\n");
                    sb.append("        <xqdoc:return>the output</xqdoc:return>\n");
                    sb.append("      </xqdoc:comment>\n");
                    sb.append("    </xqdoc:function>\n");
                }
                sb.append("  </xqdoc:functions>\n");
                sb.append("</xqdoc:xqdoc>\n");
                broker.storeDocument(tx, XmldbURI.create(name + ".xml"),
                        new StringInputSource(sb.toString()), MimeType.XML_TYPE, root);
            }
            tx.commit();
        }
    }
}
