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
package xquery.lucene;

import org.exist.dom.persistent.NodeProxy;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.storage.vector.VectorStore;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.ConfigurationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A chunked vector field keeps its chunks' vectors in vector.dbx, with their offsets, and a reindex reuses a chunk
 * whose text and model are unchanged, except a "vector" reindex; a "fulltext" reindex restores them as kept.
 *
 * <p>To tell a reused chunk from one embedded again, a test keeps a vector of its own for the first chunk, with the
 * hashes it is given, and scores the node's chunks against that vector: only a reused chunk matches it exactly.</p>
 */
class ChunkVectorReuseTest {

    @RegisterExtension
    static ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static final String MODEL = "target/onnx-models/all-MiniLM-L6-v2";
    private static final String COLLECTION = "/db/chunk-vector-reuse";
    private static final String FIELD = "passages";
    private static final String XCONF = """
        <collection xmlns="http://exist-db.org/collection-config/1.0"><index><lucene><text qname="div">\
        <vector-field name="passages" dimension="384" similarity="cosine" embedding="local" model="all-MiniLM-L6-v2" \
        model-path="%s" chunk="tokens" chunk-overlap="8"/></text></lucene></index></collection>""".formatted(MODEL);
    private static final String TEXT = ("The delegations discussed tariff schedules for agricultural exports, quotas on "
            + "manufactured goods, and the most-favored-nation clause of the commercial treaty. ").repeat(6);
    private static final float[] PLANTED = new float[384];
    static {
        PLANTED[0] = 1;
    }
    private static final String PLANTED_VECTOR = "[1" + ", 0".repeat(383) + "]";

    @BeforeEach
    void store() throws Exception {
        assumeTrue(hasEmbeddingModel(), "ONNX model not found in " + MODEL + ": skipping");
        execute("""
            (xmldb:create-collection('/db/system/config/db', 'chunk-vector-reuse'),
             xmldb:store('/db/system/config/db/chunk-vector-reuse', 'collection.xconf', parse-xml('%s')),
             xmldb:create-collection('/db', 'chunk-vector-reuse'),
             xmldb:store('%s', 'd.xml', <d><div><p>%s</p></div></d>))
            """.formatted(XCONF.replace("'", "''"), COLLECTION, TEXT));
    }

    @Test
    void theChunksAreKeptWithTheirOffsets() throws Exception {
        final List<VectorStore.Entry> kept = kept();
        assertTrue(kept.size() > 1, "the text has several chunks: " + kept.size());
        assertEquals(chunkCount(), String.valueOf(kept.size()), "one entry per chunk document");
        assertEquals(0, kept.get(0).start());
        assertTrue(kept.get(1).start() > 0 && kept.get(1).start() < kept.get(0).end(), "the second chunk overlaps the first");
    }

    @Test
    void anUnchangedChunkIsReused() throws Exception {
        plant(false);
        execute("xmldb:reindex('" + COLLECTION + "')");
        assertEquals("1 1", bestChunk(), "the first chunk's kept vector was reused");
    }

    @Test
    void aChunkOfAnotherTextIsEmbeddedAgain() throws Exception {
        plant(true);
        execute("xmldb:reindex('" + COLLECTION + "')");
        assertNotEquals("1 1", bestChunk());
        assertNotEquals(PLANTED[0], kept().get(0).vector()[0], "the vector embedded again is kept");
    }

    @Test
    void aVectorReindexEmbedsTheChunksAgain() throws Exception {
        plant(false);
        execute("xmldb:reindex('" + COLLECTION + "', 'vector')");
        assertNotEquals("1 1", bestChunk());
    }

    @Test
    void aFulltextReindexRestoresTheChunks() throws Exception {
        final String chunks = chunkCount();
        plant(true);
        execute("xmldb:reindex('" + COLLECTION + "', 'fulltext')");
        assertEquals("1 1", bestChunk(), "restored as kept, whatever its text");
        assertEquals(chunks, chunkCount(), "every chunk restored");
    }

    /** Replaces the first chunk's kept vector by {@link #PLANTED}, with its hashes and offsets, or another input hash. */
    private static void plant(final boolean otherText) throws Exception {
        final VectorStore.Entry first = kept().get(0);
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final TransactionManager transactions = pool.getTransactionManager();
        final NodeProxy node = node();
        try (final Txn txn = transactions.beginTransaction()) {
            pool.getVectorStore().put(txn, node.getOwnerDocument().getDocId(), node.getNodeId(), FIELD, 0,
                    new VectorStore.Entry(first.start(), first.end(), first.inputHash() + (otherText ? 1 : 0), first.model(), PLANTED));
            transactions.commit(txn);
        }
    }

    private static List<VectorStore.Entry> kept() throws Exception {
        final NodeProxy node = node();
        return existEmbeddedServer.getBrokerPool().getVectorStore().getChunks(node.getOwnerDocument().getDocId(), node.getNodeId(), FIELD);
    }

    /** The number of the node's best chunk against the planted vector, and its score to 6 decimals. */
    private static String bestChunk() throws Exception {
        return execute("""
            let $best := ft:vector-chunks(collection('%s')//div, map { 'field': '%s', 'vector': %s })[1]
            return $best?chunk || ' ' || format-number($best?score, '0.######')
            """.formatted(COLLECTION, FIELD, PLANTED_VECTOR));
    }

    private static String chunkCount() throws Exception {
        return execute("count(ft:vector-chunks(collection('%s')//div, map { 'field': '%s', 'vector': %s }))"
                .formatted(COLLECTION, FIELD, PLANTED_VECTOR));
    }

    private static NodeProxy node() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            return (NodeProxy) pool.getXQueryService().execute(broker, "collection('" + COLLECTION + "')//div", null).itemAt(0);
        }
    }

    private static String execute(final String query) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            return pool.getXQueryService().execute(broker, query, null).getStringValue();
        }
    }

    private static boolean hasEmbeddingModel() {
        final Path base = ConfigurationHelper.getExistHome().orElse(Path.of(System.getProperty("user.dir", ".")));
        final Path modelDir = base.resolve(MODEL);
        return Files.isRegularFile(modelDir.resolve("model.onnx")) && Files.isRegularFile(modelDir.resolve("tokenizer.json"));
    }
}
