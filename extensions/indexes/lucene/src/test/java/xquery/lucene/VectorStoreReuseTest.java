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
import org.exist.xquery.value.Sequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A reindex reuses the vector vector.dbx keeps for a node when it was computed from the same text by the same model,
 * except one that computes the vectors again ("vector"); a "fulltext" reindex restores it whatever its text.
 *
 * <p>To tell a reused vector from one computed again, a test stores a vector of its own for the node, with the hashes
 * it is given, and then looks for the node with that vector: only a reused vector matches it exactly.</p>
 */
class VectorStoreReuseTest {

    @RegisterExtension
    static ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static final String MODEL = "target/onnx-models/all-MiniLM-L6-v2";
    private static final String COLLECTION = "/db/vector-store-reuse";
    private static final String XCONF = """
        <collection xmlns="http://exist-db.org/collection-config/1.0"><index><lucene><text qname="p">\
        <vector-field name="v" dimension="384" similarity="cosine" embedding="local" model="all-MiniLM-L6-v2" model-path="%s"/>\
        </text></lucene></index></collection>""".formatted(MODEL);
    /** A vector no text embeds to. */
    private static final float[] PLANTED = new float[384];
    static {
        PLANTED[0] = 1;
    }
    private static final String PLANTED_QUERY = "[1" + ", 0".repeat(383) + "]";

    @BeforeEach
    void store() throws Exception {
        assumeTrue(hasEmbeddingModel(), "ONNX model not found in " + MODEL + ": skipping");
        execute("""
            (xmldb:create-collection('/db/system/config/db', 'vector-store-reuse'),
             xmldb:store('/db/system/config/db/vector-store-reuse', 'collection.xconf', parse-xml('%s')),
             xmldb:create-collection('/db', 'vector-store-reuse'),
             xmldb:store('%s', 'd.xml', <d><p>alpha beta</p></d>))
            """.formatted(XCONF.replace("'", "''"), COLLECTION));
    }

    @Test
    void aVectorOfTheSameTextIsReused() throws Exception {
        plant(false);
        execute("xmldb:reindex('" + COLLECTION + "')");
        assertEquals("1", plantedScore(), "the stored vector was reused");
    }

    @Test
    void aVectorOfAnotherTextIsComputedAgain() throws Exception {
        plant(true);
        execute("xmldb:reindex('" + COLLECTION + "')");
        assertNotEquals("1", plantedScore(), "the node's text has another hash: its vector is computed again");
        assertNotEquals(PLANTED[0], stored().vector()[0], "and the computed vector is stored");
    }

    @Test
    void aVectorReindexComputesTheVectorsAgain() throws Exception {
        plant(false);
        execute("xmldb:reindex('" + COLLECTION + "', 'vector')");
        assertNotEquals("1", plantedScore());
    }

    @Test
    void aFulltextReindexRestoresTheStoredVector() throws Exception {
        plant(true);
        execute("xmldb:reindex('" + COLLECTION + "', 'fulltext')");
        assertEquals("1", plantedScore(), "restored as stored, whatever its text");
    }

    /** Replaces the node's stored vector by {@link #PLANTED}, with the stored hashes, or with another input hash. */
    private static void plant(final boolean otherText) throws Exception {
        final VectorStore.Entry stored = stored();
        assertNotNull(stored, "storing the document stored the node's vector");
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final TransactionManager transactions = pool.getTransactionManager();
        final NodeProxy node = node();
        try (final Txn txn = transactions.beginTransaction()) {
            pool.getVectorStore().put(txn, node.getOwnerDocument().getDocId(), node.getNodeId(), "v", 0,
                    new VectorStore.Entry(0, 0, stored.inputHash() + (otherText ? 1 : 0), stored.model(), PLANTED));
            transactions.commit(txn);
        }
    }

    private static VectorStore.Entry stored() throws Exception {
        final NodeProxy node = node();
        return existEmbeddedServer.getBrokerPool().getVectorStore().get(node.getOwnerDocument().getDocId(), node.getNodeId(), "v", 0);
    }

    /** The score of the node for the planted vector, to 6 decimals: 1 only if the node's vector is the planted one. */
    private static String plantedScore() throws Exception {
        return execute("""
            let $hit := collection('%s')//p[ft:query-field-vector('v', %s, 1)]
            return format-number(ft:score($hit), '0.######')
            """.formatted(COLLECTION, PLANTED_QUERY));
    }

    private static NodeProxy node() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final Sequence result = pool.getXQueryService().execute(broker, "collection('" + COLLECTION + "')//p", null);
            return (NodeProxy) result.itemAt(0);
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
