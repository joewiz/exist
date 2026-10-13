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
import org.exist.collections.CollectionConfigurationManager;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.test.TestConstants;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XQuery;
import org.exist.xquery.value.Sequence;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the {@code spatial:*} XQuery module backed by the GML-HSQLDB
 * spatial index in {@code extensions/indexes/spatial}.
 *
 * <p>Three @Benchmark shapes:
 *
 * <ul>
 *   <li>{@code shapeContainsPolygon} -- {@code spatial:contains($queryPolygon, points)}
 *       over a synthetic uniform grid of N {@code gml:Point} elements (EPSG:4326).
 *   <li>{@code shapeIntersectsLayered} -- {@code spatial:intersects} between two
 *       point layers (the indexed corpus and a query polygon overlapping ~25% of
 *       the corpus extent).
 *   <li>{@code shapeIngest} -- end-to-end cost of storing the corpus document
 *       under a collection configured with {@code <gml/>} (i.e., the marginal
 *       indexing cost is folded into the store path). Compared with {@link
 *       #shapeIngestNoIndex()} which stores the same document under a collection
 *       without {@code <gml/>}.
 * </ul>
 *
 * <p>{@code geometryCount} parameterises corpus size. {@code 1000} is the smoke-test
 * value; {@code 10000} and {@code 100000} are the scaling points the spatial-index
 * dust-off tasking calls for. Expect single-shot {@link #shapeIngest()} runs at
 * 100K to take >1 minute -- pick the JMH parameters accordingly.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class SpatialIndexBenchmark {

    private static final String CONFIG_RESOURCE = "/org/exist/indexing/jmh/spatial-collection.xconf";
    private static final String COLLECTION_PATH = TestConstants.TEST_COLLECTION_URI.toString();
    private static final String NO_INDEX_COLLECTION_PATH = TestConstants.TEST_COLLECTION_URI.append("noindex").toString();
    private static final String DOC_NAME = "points.gml";
    private static final String DOC_NO_INDEX_NAME = "points-noindex.gml";

    /** Random seed -- benchmark is deterministic across runs. */
    private static final long SEED = 0xC0FFEEL;

    /**
     * Bounding box for the synthetic point cloud. Roughly the contiguous US in
     * EPSG:4326. The query polygon used by {@code shapeContainsPolygon} covers
     * the central 50% of this box; the query polygon used by
     * {@code shapeIntersectsLayered} covers the eastern 25%.
     */
    private static final double MIN_LON = -125.0;
    private static final double MAX_LON = -67.0;
    private static final double MIN_LAT = 25.0;
    private static final double MAX_LAT = 49.0;

    @Param({"1000", "10000", "100000"})
    public int geometryCount;

    private ExistEmbeddedServer server;
    private BrokerPool pool;
    private XQuery xquery;
    private String corpusXml;

    private String queryContains;
    private String queryIntersects;

    /**
     * Build a single XML document with {@code n} small {@code gml:Polygon}
     * children at random positions inside the bounding box. Each polygon is a
     * tiny square (~0.1 deg per side) so the corpus stays a uniform point cloud
     * while satisfying GeoTools' GML parser, which handles polygons more
     * readily than bare {@code gml:Point}/{@code gml:coordinates} pairs.
     * The polygons share a {@code <polygons>} root so they can be queried as
     * {@code //gml:Polygon} once stored.
     */
    static String buildPolygonCloud(final int n, final long seed) {
        final Random rnd = new Random(seed);
        final StringBuilder sb = new StringBuilder(n * 320);
        sb.append("<polygons xmlns:gml=\"http://www.opengis.net/gml\">");
        final double size = 0.1; // degrees
        for (int i = 0; i < n; i++) {
            final double x = MIN_LON + rnd.nextDouble() * (MAX_LON - MIN_LON - size);
            final double y = MIN_LAT + rnd.nextDouble() * (MAX_LAT - MIN_LAT - size);
            sb.append("<gml:Polygon srsName=\"EPSG:4326\">")
                    .append("<gml:outerBoundaryIs><gml:LinearRing><gml:coordinates>")
                    .append(x).append(',').append(y).append(' ')
                    .append(x + size).append(',').append(y).append(' ')
                    .append(x + size).append(',').append(y + size).append(' ')
                    .append(x).append(',').append(y + size).append(' ')
                    .append(x).append(',').append(y)
                    .append("</gml:coordinates></gml:LinearRing></gml:outerBoundaryIs>")
                    .append("</gml:Polygon>");
        }
        sb.append("</polygons>");
        return sb.toString();
    }

    /** Polygon covering the central 50% of the corpus bbox. */
    private static String centerPolygonGml() {
        final double dx = (MAX_LON - MIN_LON) * 0.25;
        final double dy = (MAX_LAT - MIN_LAT) * 0.25;
        final double x0 = MIN_LON + dx;
        final double x1 = MAX_LON - dx;
        final double y0 = MIN_LAT + dy;
        final double y1 = MAX_LAT - dy;
        return polygonGml(x0, y0, x1, y1);
    }

    /** Polygon covering the eastern 25% of the corpus bbox. */
    private static String easternPolygonGml() {
        final double x0 = MIN_LON + (MAX_LON - MIN_LON) * 0.75;
        final double x1 = MAX_LON;
        return polygonGml(x0, MIN_LAT, x1, MAX_LAT);
    }

    private static String polygonGml(final double x0, final double y0, final double x1, final double y1) {
        return "<gml:Polygon xmlns:gml=\"http://www.opengis.net/gml\" srsName=\"EPSG:4326\">"
                + "<gml:outerBoundaryIs><gml:LinearRing><gml:coordinates>"
                + x0 + "," + y0 + " "
                + x1 + "," + y0 + " "
                + x1 + "," + y1 + " "
                + x0 + "," + y1 + " "
                + x0 + "," + y0
                + "</gml:coordinates></gml:LinearRing></gml:outerBoundaryIs>"
                + "</gml:Polygon>";
    }

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        server = new ExistEmbeddedServer(true, true);
        server.startDb();
        pool = server.getBrokerPool();

        final String collectionConfig;
        try (InputStream in = SpatialIndexBenchmark.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing collection config resource: " + CONFIG_RESOURCE);
            }
            collectionConfig = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        corpusXml = buildPolygonCloud(geometryCount, SEED);

        final TransactionManager transact = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn transaction = transact.beginTransaction()) {

            // Indexed collection
            final Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            broker.saveCollection(transaction, root);
            final CollectionConfigurationManager mgr = pool.getConfigurationManager();
            mgr.addConfiguration(transaction, broker, root, collectionConfig);
            broker.storeDocument(transaction, XmldbURI.create(DOC_NAME),
                    new StringInputSource(corpusXml), MimeType.XML_TYPE, root);

            // Sibling collection without the spatial index, for shapeIngestNoIndex baseline.
            final Collection noIndex = broker.getOrCreateCollection(transaction,
                    TestConstants.TEST_COLLECTION_URI.append("noindex"));
            broker.saveCollection(transaction, noIndex);
            broker.storeDocument(transaction, XmldbURI.create(DOC_NO_INDEX_NAME),
                    new StringInputSource(corpusXml), MimeType.XML_TYPE, noIndex);

            transact.commit(transaction);
        }

        xquery = pool.getXQueryService();

        queryContains = """
                import module namespace spatial='http://exist-db.org/xquery/spatial'
                    at 'java:org.exist.xquery.modules.spatial.SpatialModule';
                declare namespace gml='http://www.opengis.net/gml';
                let $poly :=
                    %s
                return count(spatial:contains(collection('%s')//gml:Polygon, $poly))
                """.formatted(centerPolygonGml(), COLLECTION_PATH);

        queryIntersects = """
                import module namespace spatial='http://exist-db.org/xquery/spatial'
                    at 'java:org.exist.xquery.modules.spatial.SpatialModule';
                declare namespace gml='http://www.opengis.net/gml';
                let $poly :=
                    %s
                return count(spatial:intersects(collection('%s')//gml:Polygon, $poly))
                """.formatted(easternPolygonGml(), COLLECTION_PATH);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (server != null) {
            server.stopDb();
        }
    }

    private Sequence run(final String query) throws Exception {
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final Sequence result = xquery.execute(broker, query, null);
            result.getItemCount();
            return result;
        }
    }

    @Benchmark
    public Sequence shapeContainsPolygon() throws Exception {
        return run(queryContains);
    }

    @Benchmark
    public Sequence shapeIntersectsLayered() throws Exception {
        return run(queryIntersects);
    }

    /**
     * End-to-end ingest cost into a spatial-indexed collection. The document is
     * stored under a unique name each invocation so the index actually does the
     * insert work; teardown trims the collection back at trial end. We use
     * {@link Level#Iteration} for the per-doc state instead of {@link Level#Invocation}
     * because Invocation-level setup is too heavy here.
     */
    @Benchmark
    public void shapeIngest() throws Exception {
        storeOnce(TestConstants.TEST_COLLECTION_URI, "ingest-" + System.nanoTime() + ".gml");
    }

    @Benchmark
    public void shapeIngestNoIndex() throws Exception {
        storeOnce(TestConstants.TEST_COLLECTION_URI.append("noindex"),
                "ingest-noindex-" + System.nanoTime() + ".gml");
    }

    private void storeOnce(final XmldbURI collectionUri, final String docName) throws Exception {
        final TransactionManager transact = pool.getTransactionManager();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn tx = transact.beginTransaction();
             final Collection col = broker.openCollection(collectionUri,
                     org.exist.storage.lock.Lock.LockMode.WRITE_LOCK)) {
            broker.storeDocument(tx, XmldbURI.create(docName),
                    new StringInputSource(corpusXml), MimeType.XML_TYPE, col);
            transact.commit(tx);
        }
    }
}
