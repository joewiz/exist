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
import org.openjdk.jmh.annotations.*;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Scratch benchmark for the review of eXist-db/exist#6914: one vector per document, with the index configured on the
 * root element (as a configuration on {@code tei:TEI} does, with one FRUS document per file), compared with the
 * same search over the document nodes. Not part of any PR.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class RootElementContextBenchmark {

    private static final XmldbURI TEST_COLLECTION = XmldbURI.create("/db/bench-root-context");
    private static final String COLL = "collection('" + TEST_COLLECTION + "')";
    private static final int DIMENSION = 64;
    private static final int K = 5;
    private static final int DOCUMENTS_PER_TRANSACTION = 1000;

    @Param({"20000", "100000"})
    private int documents;

    private static final String LUCENE_CONFIG = """
            <collection xmlns="http://exist-db.org/collection-config/1.0">
              <index>
                <lucene>
                  <text qname="doc">
                    <vector-field name="emb" expression="embedding" dimension="64" similarity="cosine" encoding="text"/>
                  </text>
                </lucene>
              </index>
            </collection>""";

    private ExistEmbeddedServer server;
    private BrokerPool pool;
    private CompiledXQuery documentNodes;
    private CompiledXQuery rootElements;
    private CompiledXQuery narrowRootElements;
    private CompiledXQuery selectRootElements;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        final Properties configProperties = new Properties();
        configProperties.put("wait-before-shutdown", 0L);
        server = new ExistEmbeddedServer(configProperties, true, true);
        server.startDb();
        pool = server.getBrokerPool();
        storeCorpus();
        final String vector = "array { (" + vectorText(0.5, ", ") + ") }";
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xquery = pool.getXQueryService();
            documentNodes = compile(xquery, "count(ft:query-vector(" + COLL + ", " + vector + ", " + K + "))");
            rootElements = compile(xquery, "count(ft:query-vector(" + COLL + "//doc, " + vector + ", " + K + "))");
            narrowRootElements = compile(xquery,
                    "count(ft:query-vector(" + COLL + "//doc[@lang = 'en'], " + vector + ", " + K + "))");
            selectRootElements = compile(xquery, "count(" + COLL + "//doc)");
        }
    }

    private CompiledXQuery compile(final XQuery xquery, final String query) throws XPathException, PermissionDeniedException {
        return xquery.compile(new XQueryContext(pool), query);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (server != null) {
            server.stopDb();
        }
    }

    /** The search over the document nodes: no per-vector filter. */
    @Benchmark
    public long documentNodes() throws Exception {
        return execute(documentNodes, K);
    }

    /** The same search over the root elements, one per document: the context filter runs. */
    @Benchmark
    public long rootElements() throws Exception {
        return execute(rootElements, K);
    }

    /** A seventh of the root elements. */
    @Benchmark
    public long narrowRootElements() throws Exception {
        return execute(narrowRootElements, K);
    }

    /** Selecting the root elements alone, for comparison. */
    @Benchmark
    public long selectRootElements() throws Exception {
        return execute(selectRootElements, documents);
    }

    private long execute(final CompiledXQuery compiledQuery, final long expected) throws Exception {
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final Sequence result = pool.getXQueryService().execute(broker, compiledQuery, null);
            final long count = Long.parseLong(result.itemAt(0).getStringValue());
            if (count != expected) {
                throw new IllegalStateException("Expected " + expected + ", got " + count);
            }
            return count;
        }
    }

    private void storeCorpus() throws Exception {
        final TransactionManager transact = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            try (final Txn tx = transact.beginTransaction()) {
                final Collection coll = broker.getOrCreateCollection(tx, TEST_COLLECTION);
                broker.saveCollection(tx, coll);
                final CollectionConfigurationManager mgr = pool.getConfigurationManager();
                mgr.addConfiguration(tx, broker, coll, LUCENE_CONFIG);
                transact.commit(tx);
            }
            for (int start = 1; start <= documents; start += DOCUMENTS_PER_TRANSACTION) {
                try (final Txn tx = transact.beginTransaction()) {
                    final Collection coll = broker.getOrCreateCollection(tx, TEST_COLLECTION);
                    for (int i = start; i < start + DOCUMENTS_PER_TRANSACTION && i <= documents; i++) {
                        final String doc = "<doc n=\"" + i + "\" lang=\"" + (i % 7 == 0 ? "en" : "de") + "\"><embedding>"
                                + vectorText(i, " ") + "</embedding></doc>";
                        broker.storeDocument(tx, XmldbURI.create("d" + i + ".xml"), new StringInputSource(doc),
                                MimeType.XML_TYPE, coll);
                    }
                    transact.commit(tx);
                }
            }
        }
    }

    /** The same pseudo-random vector as VectorContextBenchmark, for a seed. */
    private static String vectorText(final double seed, final String separator) {
        final StringBuilder text = new StringBuilder();
        for (int j = 1; j <= DIMENSION; j++) {
            final double x = Math.sin(seed * 12.9898 + j * 78.233) * 43758.5453;
            if (j > 1) {
                text.append(separator);
            }
            text.append(x - Math.floor(x) - 0.5);
        }
        return text.toString();
    }
}
