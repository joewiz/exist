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

import org.exist.EXistException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.ConfigurationHelper;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.value.Sequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A chunked vector field (chunk="tokens") embeds each node's text in chunks the model reads in full, so that a passage
 * past the model's 128 tokens is found, and ft:vector-chunks returns it. Skips when the ONNX model is not available.
 *
 * <p>Run with the model in {@code target/onnx-models/all-MiniLM-L6-v2} of the module.</p>
 */
class ChunkedVectorFieldTest {

    @RegisterExtension
    static ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static final String COLLECTION_NAME = "lucene-test-chunked-vector";
    private static final String COLLECTION = "/db/" + COLLECTION_NAME;
    private static final String MODEL = "target/onnx-models/all-MiniLM-L6-v2";

    private static final String XCONF = """
        <collection xmlns="http://exist-db.org/collection-config/1.0"><index><lucene>\
        <analyzer id="stemmed" class="org.apache.lucene.analysis.en.EnglishAnalyzer"/><text qname="div">\
        <field name="title" expression="head"/><field name="div-text"/><field name="div-stemmed" analyzer="stemmed"/><vector-field name="passages" dimension="384" similarity="cosine" embedding="local" model="all-MiniLM-L6-v2" \
        model-path="%s" chunk="tokens" chunk-overlap="8" chunk-context="head"/></text>\
        <text qname="record"><vector-field name="record-passages" dimension="384" similarity="cosine" embedding="local" \
        model="all-MiniLM-L6-v2" model-path="%s" chunk="tokens" chunk-overlap="8" chunk-units="body/node()"/></text>\
        </lucene></index></collection>"""
            .formatted(MODEL, MODEL);

    /** Trade talk long past the model's 128 tokens, then a paragraph about a damaged submarine cable. */
    private static final String FILLER = ("The delegations discussed tariff schedules for agricultural exports, quotas on "
            + "manufactured goods, and the most-favored-nation clause of the commercial treaty. ").repeat(8);
    private static final String DATA = """
        <docs>
            <div xml:id="cable"><head>Memorandum of conversation</head>
                <p>%s</p>
                <p>The submarine telegraph cable between Havana and Key West was cut by a ship's anchor, and repairs will take weeks.</p>
            </div>
            <div xml:id="wheat"><head>Telegram to the embassy</head>
                <p>Wheat and corn harvests were larger than expected, and grain prices fell sharply in the autumn.</p>
            </div>
            <record xml:id="record"><header>Metadata that the chunks leave out: file number, date, classification.</header>
                <body><p>%s</p>
                <p>The submarine telegraph cable between Havana and Key West was cut by a ship's anchor, and repairs will take weeks.</p></body>
            </record>
        </docs>""".formatted(FILLER, FILLER);

    private static final String PROLOG = """
        xquery version "3.1";
        import module namespace ft="http://exist-db.org/xquery/lucene";
        import module namespace vector="http://exist-db.org/xquery/vector";
        declare namespace exist="http://exist.sourceforge.net/NS/exist";
        declare variable $v := vector:embed("undersea telegraph line damaged", "all-MiniLM-L6-v2", "%s");
        """.formatted(MODEL);

    @BeforeAll
    static void store() throws EXistException, PermissionDeniedException, XPathException {
        assumeTrue(hasEmbeddingModel(), "ONNX model not found in " + MODEL + ": skipping chunked vector field tests");
        execute("""
            (xmldb:create-collection("/db/system", "config"),
             xmldb:create-collection("/db/system/config", "db"),
             xmldb:create-collection("/db/system/config/db", "%s"),
             xmldb:store("/db/system/config/db/%s", "collection.xconf", parse-xml('%s')),
             xmldb:create-collection("/db", "%s"),
             xmldb:store("%s", "test.xml", parse-xml('%s')))
            """.formatted(COLLECTION_NAME, COLLECTION_NAME, XCONF.replace("'", "''"), COLLECTION_NAME, COLLECTION,
                DATA.replace("'", "''")));
    }

    @Test
    void passagePastTheModelsLimitIsFound() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hits := collection("%s")//div[ft:query-vector(., $v, 2)]
            for $hit in $hits order by ft:score($hit) descending return string($hit/@xml:id)
            """.formatted(COLLECTION));
        assertEquals(2, result.getItemCount(), "k counts nodes, not chunks");
        assertEquals("cable", result.itemAt(0).getStringValue());
    }

    @Test
    void matchedChunkIsTheCableParagraph() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[ft:query-vector(., $v, 2)][@xml:id = "cable"]
            let $chunk := ft:vector-chunks($hit)
            return (count($chunk), $chunk?field, $chunk?chunk > 1,
                $chunk?text, substring(string($hit), $chunk?start + 1, $chunk?end - $chunk?start) = $chunk?text,
                $chunk?score = ft:score($hit))
            """.formatted(COLLECTION));
        assertEquals("1", result.itemAt(0).getStringValue());
        assertEquals("passages", result.itemAt(1).getStringValue());
        assertEquals("true", result.itemAt(2).getStringValue(), "the cable paragraph is not in the first chunk");
        assertTrue(result.itemAt(3).getStringValue().contains("submarine telegraph cable"), result.itemAt(3).getStringValue());
        assertEquals("true", result.itemAt(4).getStringValue(), "the offsets select the chunk's text");
        assertEquals("true", result.itemAt(5).getStringValue());
    }

    /**
     * With chunk-units that select part of the node, a chunk's offsets refer to the units' text, here the body's, not
     * to the node's string value, and its text is cut from the units' text.
     */
    @Test
    void chunkTextIsCutFromTheChunkUnits() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//record[ft:query-vector(., $v, 1)]
            let $chunk := ft:vector-chunks($hit)
            let $units := string($hit/body)
            return (contains($chunk?text, "submarine telegraph cable"),
                substring($units, $chunk?start + 1, $chunk?end - $chunk?start) = $chunk?text,
                substring(string($hit), $chunk?start + 1, $chunk?end - $chunk?start) = $chunk?text)
            """.formatted(COLLECTION));
        assertEquals("true", result.itemAt(0).getStringValue(), "the chunk is the cable paragraph");
        assertEquals("true", result.itemAt(1).getStringValue(), "the offsets select the chunk in the units' text");
        assertEquals("false", result.itemAt(2).getStringValue(), "the node's string value has the header before the body");
    }

    /** A chunk carries its node's identity, but a lookup of the node's own fields must not find it. */
    @Test
    void fieldsAreReadFromTheNodesOwnDocument() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            for $hit in collection("%s")//div[ft:query-vector(., $v, 2)]
            order by ft:score($hit) descending
            return ft:field($hit, "title")
            """.formatted(COLLECTION));
        assertEquals(2, result.getItemCount());
        assertEquals("Memorandum of conversation", result.itemAt(0).getStringValue());
        assertEquals("Telegram to the embassy", result.itemAt(1).getStringValue());
    }

    /**
     * A node found by a keyword search has no vector match: with a vector, its chunks are scored against it, all of
     * them, the best first, from the vectors in the index.
     */
    @Test
    void vectorRanksTheChunksOfANodeFoundByKeyword() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[ft:query-field("div-text", "telegraph")]
            let $plain := ft:vector-chunks($hit)
            let $chunks := ft:vector-chunks($hit, map { "field": "passages", "vector": $v })
            return (count($plain), count($chunks) > 1, contains($chunks[1]?text, "submarine telegraph cable"),
                every $i in 2 to count($chunks) satisfies $chunks[$i - 1]?score >= $chunks[$i]?score,
                $chunks[1]?field)
            """.formatted(COLLECTION));
        assertEquals("0", result.itemAt(0).getStringValue(), "a keyword hit has no vector match");
        assertEquals("true", result.itemAt(1).getStringValue(), "all of the node's chunks");
        assertEquals("true", result.itemAt(2).getStringValue(), "the cable paragraph's chunk is the best");
        assertEquals("true", result.itemAt(3).getStringValue(), "the best first");
        assertEquals("passages", result.itemAt(4).getStringValue());
    }

    /** Scored against the query's vector, a node's best chunk has the score the vector search gives the node. */
    @Test
    void vectorScoresAsTheSearchDoes() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[ft:query-vector(., $v, 2)][@xml:id = "cable"]
            let $scored := ft:vector-chunks($hit, map { "field": "passages", "vector": $v })[1]
            return (abs($scored?score - ft:score($hit)) < 0.0001, $scored?chunk = ft:vector-chunks($hit)?chunk)
            """.formatted(COLLECTION));
        assertEquals("true", result.itemAt(0).getStringValue(), result.itemAt(0).getStringValue());
        assertEquals("true", result.itemAt(1).getStringValue());
    }

    /**
     * The keyword query's matches are marked in each chunk's text, every term that occurs, even where the query as a
     * whole doesn't match, as here, where "zebra" occurs nowhere and the default operator is "and".
     */
    @Test
    void highlightMarksTheTermsThatOccur() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[ft:query-vector(., $v, 2)][@xml:id = "cable"]
            let $chunk := ft:vector-chunks($hit, map {
                "highlight": map { "field": "div-text", "query": "cable zebra", "options": map { "default-operator": "and" } }
            })[1]
            return ($chunk?marked//exist:match ! string(), string($chunk?marked) = $chunk?text, $chunk?marked/local-name())
            """.formatted(COLLECTION));
        assertEquals("cable", result.itemAt(0).getStringValue());
        assertEquals("true", result.itemAt(1).getStringValue(), "the marked text is the chunk's text");
        assertEquals("chunk", result.itemAt(2).getStringValue());
    }

    /** The text is analyzed with the field's analyzer: a stemmed field marks the forms of a word it stems alike. */
    @Test
    void highlightAnalyzesWithTheFieldsAnalyzer() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[ft:query-vector(., $v, 2)][@xml:id = "cable"]
            let $marks := function($field) {
                ft:vector-chunks($hit, map { "highlight": map { "field": $field, "query": "repair cables" } })[1]
                    ?marked//exist:match ! string()
            }
            return (string-join($marks("div-text"), "|"), string-join($marks("div-stemmed"), "|"))
            """.formatted(COLLECTION));
        assertEquals("", result.itemAt(0).getStringValue(), "the plain field has neither 'repair' nor 'cables'");
        assertEquals("cable|repairs", result.itemAt(1).getStringValue());
    }

    /** A phrase is marked only where it occurs, as one match, and a wildcard where its terms do. */
    @Test
    void highlightPhrasesAndWildcards() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[ft:query-vector(., $v, 2)][@xml:id = "cable"]
            let $marks := function($query) {
                string-join(ft:vector-chunks($hit, map { "highlight": map { "field": "div-text", "query": $query } })[1]
                    ?marked//exist:match ! string(), "|")
            }
            return ($marks('"telegraph cable"'), $marks('"cable telegraph"'), $marks('tele*'))
            """.formatted(COLLECTION));
        assertEquals("telegraph cable", result.itemAt(0).getStringValue());
        assertEquals("", result.itemAt(1).getStringValue());
        assertEquals("telegraph", result.itemAt(2).getStringValue());
    }

    @Test
    void vectorNeedsAField() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[@xml:id = "cable"]
            return try { ft:vector-chunks($hit, map { "vector": $v }) } catch * { $err:code }
            """.formatted(COLLECTION));
        assertEquals("exerr:EXXQDY0008", result.itemAt(0).getStringValue());
    }

    @Test
    void vectorOfAnotherDimension() throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = execute(PROLOG + """
            let $hit := collection("%s")//div[@xml:id = "cable"]
            return try { ft:vector-chunks($hit, map { "field": "passages", "vector": [1, 2, 3] }) } catch * { $err:code }
            """.formatted(COLLECTION));
        assertEquals("exerr:EXXQDY0008", result.itemAt(0).getStringValue());
    }

    private static Sequence execute(final String query) throws EXistException, PermissionDeniedException, XPathException {
        final BrokerPool brokerPool = existEmbeddedServer.getBrokerPool();
        final XQuery xquery = brokerPool.getXQueryService();
        try (final DBBroker broker = brokerPool.get(Optional.of(brokerPool.getSecurityManager().getSystemSubject()))) {
            return xquery.execute(broker, query, null);
        }
    }

    private static boolean hasEmbeddingModel() {
        final Path base = ConfigurationHelper.getExistHome().orElse(Path.of(System.getProperty("user.dir", ".")));
        final Path modelDir = base.resolve(MODEL);
        return Files.isRegularFile(modelDir.resolve("model.onnx")) && Files.isRegularFile(modelDir.resolve("tokenizer.json"));
    }
}
