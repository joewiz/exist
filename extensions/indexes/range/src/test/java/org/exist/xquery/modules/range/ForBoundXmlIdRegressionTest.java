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
 * Phase A diagnostic - confirm the for-bound + range index correctness anomaly.
 * Opt-in via -Dexist.fnid.repro=true to keep it out of the default run.
 *
 * Reproduces:
 *   for $v in ("idA","idB","idC") return collection(...)//*[@xml:id = $v]
 * Under config L (no <range> on @xml:id): correctly returns 3 elements.
 * Under config N (<range create qname="@xml:id" type="xs:string"/>):
 *   incorrectly returns ALL elements with @xml:id (or all `*`) per outer iter.
 *
 * The smoke test in FnIdVsRangeIndexBenchmark already exhibits this; this
 * class is a focused, fast (< 5s) confirmation that does not depend on
 * timing scaffolding.
 */
package org.exist.xquery.modules.range;

import org.exist.collections.Collection;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
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

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class ForBoundXmlIdRegressionTest {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

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

    private static final int N_ITEMS = 100;
    private static final int N_KEYS = 5;

    @BeforeClass
    public static void gate() {
        Assume.assumeTrue(
            "for-bound xml:id repro is opt-in; set -Dexist.fnid.repro=true",
            "true".equalsIgnoreCase(System.getProperty("exist.fnid.repro"))
        );
    }

    @Test
    public void legacyAutoIndexOnly_returnsExpectedHits() throws Exception {
        runScenario("L", CONFIG_L, N_KEYS);
    }

    @Test
    public void rangeIndexConfigured_returnsCorrectHits() throws Exception {
        // EXPECTED to fail under the bug -- documents the anomaly.
        // After fix, this should pass and the legacyAutoIndexOnly count should match.
        runScenario("N", CONFIG_N, N_KEYS);
    }

    private void runScenario(final String tag, final String configXml, final int nKeys) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final XmldbURI col = XmldbURI.ROOT_COLLECTION_URI.append("forbound-" + tag);
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            createCollectionAndStore(pool, broker, col, configXml);
        }

        final StringBuilder keysExpr = new StringBuilder("(");
        for (int i = 0; i < nKeys; i++) {
            if (i > 0) keysExpr.append(',');
            keysExpr.append('"').append("id").append(i).append('"');
        }
        keysExpr.append(')');

        // Outer for-loop binding $v to each of N_KEYS distinct values that exist in the corpus.
        // Expected hits = N_KEYS (one element per outer iter).
        final String forBound = "for $v in " + keysExpr
                + " return collection(\"" + col + "\")//*[@xml:id = $v]";

        // Control: same shape with literal value -- expected hits = 1.
        final String literal = "collection(\"" + col + "\")//*[@xml:id = \"id0\"]";

        final BrokerPool p = pool;
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final long literalHits = run(broker, literal);
            final long forBoundHits = run(broker, forBound);

            System.out.printf("[%s] literal hits=%d, for-bound hits=%d (expected for-bound=%d)%n",
                    tag, literalHits, forBoundHits, nKeys);

            // Both should be correct.
            assertEquals(tag + " literal expected 1 hit", 1, literalHits);
            assertEquals(tag + " for-bound expected " + nKeys + " hits", nKeys, forBoundHits);
        } finally {
            cleanup(pool, col);
        }
    }

    private long run(final DBBroker broker, final String query) throws XPathException, PermissionDeniedException {
        final BrokerPool pool = broker.getBrokerPool();
        final XQuery xqs = pool.getXQueryService();
        final XQueryContext ctx = new XQueryContext(pool);
        final CompiledXQuery compiled = xqs.compile(ctx, query);
        final Sequence r = xqs.execute(broker, compiled, null);
        return r.getItemCount();
    }

    private void createCollectionAndStore(final BrokerPool pool, final DBBroker broker,
                                          final XmldbURI col, final String configXml) throws Exception {
        final TransactionManager tm = pool.getTransactionManager();
        try (final Txn txn = tm.beginTransaction()) {
            final Collection collection = broker.getOrCreateCollection(txn, col);
            broker.saveCollection(txn, collection);
            final CollectionConfigurationManager cm = pool.getConfigurationManager();
            cm.addConfiguration(txn, broker, collection, configXml);

            final StringBuilder doc = new StringBuilder();
            doc.append("<root xmlns:xml=\"http://www.w3.org/XML/1998/namespace\">");
            for (int i = 0; i < N_ITEMS; i++) {
                doc.append("<item xml:id=\"id").append(i).append("\">v").append(i).append("</item>");
            }
            doc.append("</root>");

            broker.storeDocument(txn, XmldbURI.create("data.xml"),
                    new StringInputSource(doc.toString()), MimeType.XML_TYPE, collection);
            broker.reindexCollection(txn, collection.getURI());
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
}
