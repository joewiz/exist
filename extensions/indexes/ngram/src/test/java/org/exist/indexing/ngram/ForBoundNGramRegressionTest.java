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
 * Sibling-bug audit for F1 -- check whether the for-bound + range index correctness
 * defect affects ngram:contains.
 * Opt-in via -Dexist.fnid.repro=true.
 */
package org.exist.indexing.ngram;

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
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class ForBoundNGramRegressionTest {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static final String CONFIG =
            "<collection xmlns=\"http://exist-db.org/collection-config/1.0\">"
                    + "<index>"
                    + "<ngram qname=\"name\"/>"
                    + "</index>"
                    + "</collection>";

    private static final int N_ITEMS = 100;
    private static final int N_KEYS = 5;

    @BeforeClass
    public static void gate() {
        Assume.assumeTrue(
            "for-bound ngram repro is opt-in; set -Dexist.fnid.repro=true",
            "true".equalsIgnoreCase(System.getProperty("exist.fnid.repro"))
        );
    }

    @Test
    public void forBound_returnsCorrectHits() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final XmldbURI col = XmldbURI.ROOT_COLLECTION_URI.append("forbound-ngram");
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            createCollectionAndStore(pool, broker, col);
        }

        final StringBuilder keysExpr = new StringBuilder("(");
        for (int i = 0; i < N_KEYS; i++) {
            if (i > 0) keysExpr.append(',');
            keysExpr.append('"').append("name").append(i).append('"');
        }
        keysExpr.append(')');

        final String literal = "collection(\"" + col + "\")//item[ngram:contains(name, \"name0\")]";
        final String forBound = "for $v in " + keysExpr
                + " return collection(\"" + col + "\")//item[ngram:contains(name, $v)]";

        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final long literalHits = run(broker, "declare namespace ngram=\"http://exist-db.org/xquery/ngram\";" + literal);
            final long forBoundHits = run(broker, "declare namespace ngram=\"http://exist-db.org/xquery/ngram\";" + forBound);

            System.out.printf("[NGramSearch] literal hits=%d, for-bound hits=%d (expected for-bound>=%d)%n",
                    literalHits, forBoundHits, N_KEYS);

            // Each for-bound iter should match at least 1 element (substring "nameK" appears in "nameK", "nameK0", "nameK1", ... at scale=100, just nameK directly).
            // To keep the test simple and rigorous, we only assert that the for-bound count equals N_KEYS *exactly* if literal=1.
            // ngram:contains is substring; "name0" is substring of name0, name00, name01, ... — but we only have name0..name99, so name0 substring matches 11 items: name0, name10..name19. So we expect more than 1 hit per iter.
            // Skip exact assertion; instead assert <= N_KEYS * N_ITEMS (the all-pass bug ceiling).
            // The bug returns (N_ITEMS + 1) * N_KEYS = 505. Correctness returns far fewer.
            org.junit.Assert.assertTrue("for-bound should not return all elements (bug ceiling)",
                    forBoundHits < (long) N_ITEMS * N_KEYS / 2);
        } finally {
            cleanup(pool, col);
        }
    }

    private long run(final DBBroker broker, final String query) throws XPathException, PermissionDeniedException {
        final BrokerPool pool = broker.getBrokerPool();
        final XQuery xqs = pool.getXQueryService();
        final XQueryContext ctx = new XQueryContext(pool);
        final CompiledXQuery compiled = xqs.compile(ctx, query);
        return xqs.execute(broker, compiled, null).getItemCount();
    }

    private void createCollectionAndStore(final BrokerPool pool, final DBBroker broker, final XmldbURI col) throws Exception {
        final TransactionManager tm = pool.getTransactionManager();
        try (final Txn txn = tm.beginTransaction()) {
            final Collection collection = broker.getOrCreateCollection(txn, col);
            broker.saveCollection(txn, collection);
            final CollectionConfigurationManager cm = pool.getConfigurationManager();
            cm.addConfiguration(txn, broker, collection, CONFIG);

            final StringBuilder doc = new StringBuilder();
            doc.append("<root>");
            for (int i = 0; i < N_ITEMS; i++) {
                doc.append("<item><name>name").append(i).append("</name></item>");
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
