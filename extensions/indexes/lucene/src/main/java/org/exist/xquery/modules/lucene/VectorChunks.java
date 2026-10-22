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
package org.exist.xquery.modules.lucene;

import com.evolvedbinary.j8fu.tuple.Tuple2;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;
import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.Match;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.AbstractFieldConfig;
import org.exist.indexing.lucene.LuceneConfig;
import org.exist.indexing.lucene.LuceneConfigs;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneIndexConfig;
import org.exist.indexing.lucene.LuceneIndexWorker;
import org.exist.indexing.lucene.LuceneMatch;
import org.exist.indexing.lucene.LuceneMatchListener;
import org.exist.indexing.lucene.LuceneVectorFieldConfig;
import org.exist.indexing.lucene.MatchLocator;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.NodePath;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.map.AbstractMapType;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.FloatValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;

/**
 * Returns the chunks of a node that a query of a chunked vector field matched, or all of its chunks scored against a
 * vector, so that a result can show the passage that matched, with a keyword query's matches marked in it.
 */
public class VectorChunks extends BasicFunction {

    private static final QName NAME = new QName("vector-chunks", LuceneModule.NAMESPACE_URI, LuceneModule.PREFIX);

    private static final String DESCRIPTION = """
            Returns the chunks of the node that a query of a chunked vector field (a vector-field with chunk="tokens") \
            matched, the best one first, as maps with the keys "field" (the vector field), "chunk" (the chunk's \
            position among the node's chunks, from 1), "start" and "end" (the offsets of its first character and after \
            its last character in the node's chunked text, counted in characters from 0, which with the default \
            chunk-units is the node's string value), "text" (the chunk's text, if it is within the node's string \
            value) and "score". Returns the empty sequence if the node was not matched by such a query.""";

    private static final FunctionParameterSequenceType NODE = new FunctionParameterSequenceType("node", Type.NODE,
            Cardinality.EXACTLY_ONE, "a node returned by ft:query-vector or ft:query-field-vector, or, with the "
            + "vector option, any node a chunked vector field indexed");

    public static final FunctionSignature[] signatures = {
            new FunctionSignature(NAME, DESCRIPTION, new SequenceType[] {NODE},
                    new FunctionReturnSequenceType(Type.MAP_ITEM, Cardinality.ZERO_OR_MORE, "the matched chunks")),
            new FunctionSignature(NAME, DESCRIPTION + """
                     $options is a map with the keys: "field", the chunked vector field whose chunks are returned \
                    (by default those of every field that matched); "vector", an array of numbers, which the node's \
                    chunks in "field" are scored against, as a search of the field would score them, so that a node \
                    found otherwise, such as by a keyword search, has chunks too: all of them are returned, the best \
                    first, read from the index without embedding anything; and "highlight", a map with the keys \
                    "field", a full-text field, "query", a query string, and "options", the query's options, as for \
                    ft:query-field, which adds to each chunk the key "marked": an exist:chunk element with the chunk's \
                    text and an exist:match element around each match. Every term or phrase of the query that occurs \
                    in the chunk is marked, even where the query as a whole doesn't match it, and the text is analyzed \
                    with the field's analyzer, so that a stemmed field marks the forms of a word it stems alike. The \
                    node needn't have been found by the query. "vector" without "field" raises EXXQDY0008, as does a \
                    vector of another dimension than the field's.""",
                    new SequenceType[] {NODE,
                            new FunctionParameterSequenceType("options", Type.MAP_ITEM, Cardinality.EXACTLY_ONE, "the options")},
                    new FunctionReturnSequenceType(Type.MAP_ITEM, Cardinality.ZERO_OR_MORE, "the chunks"))
    };

    private static final StringValue FIELD = new StringValue("field");
    private static final StringValue VECTOR = new StringValue("vector");
    private static final StringValue HIGHLIGHT = new StringValue("highlight");
    private static final StringValue QUERY = new StringValue("query");
    private static final StringValue OPTIONS = new StringValue("options");

    /** A chunk to return: its field, its position from 0, its offsets in the chunked text, and its score. */
    private record Chunk(String field, int number, int start, int end, float score) {
    }

    /** The matches of a keyword query to mark in a chunk's text: the query, its field and that field's analyzer. */
    private record Highlight(MatchLocator locator, String field, Analyzer analyzer) {
    }

    public VectorChunks(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final NodeValue nodeValue = (NodeValue) args[0].itemAt(0);
        if (nodeValue.getImplementationType() != NodeValue.PERSISTENT_NODE) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final NodeProxy proxy = (NodeProxy) nodeValue;
        final AbstractMapType options = args.length > 1 ? (AbstractMapType) args[1].itemAt(0) : null;
        final String field = stringOption(options, FIELD);
        final float[] vector = vectorOption(options);

        final List<Chunk> chunks = vector != null ? scoredChunks(proxy, field, vector) : matchedChunks(proxy, field);
        if (chunks.isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final Highlight highlight = highlight(proxy, options);

        final ValueSequence result = new ValueSequence(chunks.size());
        final Map<String, String> chunkedTexts = new HashMap<>();
        for (final Chunk chunk : chunks) {
            final String value = chunkedTexts.computeIfAbsent(chunk.field(), f -> chunkedText(proxy, f));
            result.add(chunkMap(chunk, value, highlight));
        }
        return result;
    }

    /** A chunk as a map, with its text and marked text if it is within the chunked text. */
    private MapType chunkMap(final Chunk chunk, final String value, @Nullable final Highlight highlight)
            throws XPathException {
        final boolean inValue = chunk.end() <= value.length() && chunk.start() <= chunk.end();
        final List<Tuple2<AtomicValue, Sequence>> entries = new ArrayList<>(7);
        entries.add(Tuple(new StringValue(this, "field"), new StringValue(this, chunk.field())));
        entries.add(Tuple(new StringValue(this, "chunk"), new IntegerValue(this, chunk.number() + 1)));
        // the offsets are in UTF-16 code units, XPath counts characters
        final int start = inValue ? value.codePointCount(0, chunk.start()) : chunk.start();
        final int end = inValue ? start + value.codePointCount(chunk.start(), chunk.end()) : chunk.end();
        entries.add(Tuple(new StringValue(this, "start"), new IntegerValue(this, start)));
        entries.add(Tuple(new StringValue(this, "end"), new IntegerValue(this, end)));
        if (inValue) {
            final String text = value.substring(chunk.start(), chunk.end());
            entries.add(Tuple(new StringValue(this, "text"), new StringValue(this, text)));
            if (highlight != null) {
                entries.add(Tuple(new StringValue(this, "marked"), marked(text, highlight)));
            }
        }
        entries.add(Tuple(new StringValue(this, "score"), new FloatValue(this, chunk.score())));
        return new MapType(this, context, null, entries);
    }

    @Nullable
    private float[] vectorOption(@Nullable final AbstractMapType options) throws XPathException {
        final Sequence value = options != null ? options.get(VECTOR) : Sequence.EMPTY_SEQUENCE;
        if (value.isEmpty()) {
            return null;
        }
        final float[] vector = AbstractVectorQueryFunction.arrayToFloats(value);
        if (vector == null) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'vector' must be an array of numbers");
        }
        return vector;
    }

    /** The chunks the node's matches of chunked vector fields name, of one field or of all, the best first. */
    private static List<Chunk> matchedChunks(final NodeProxy proxy, @Nullable final String field) {
        final List<Chunk> chunks = new ArrayList<>();
        for (Match match = proxy.getMatches(); match != null; match = match.getNextMatch()) {
            if (match.getIndexId().equals(LuceneIndex.ID) && match instanceof LuceneMatch luceneMatch
                    && luceneMatch.getChunk() != null && (field == null || field.equals(luceneMatch.getChunk().field()))) {
                final LuceneMatch.Chunk chunk = luceneMatch.getChunk();
                chunks.add(new Chunk(chunk.field(), chunk.number(), chunk.start(), chunk.end(), luceneMatch.getScore()));
            }
        }
        chunks.sort((a, b) -> Float.compare(b.score(), a.score()));
        return chunks;
    }

    /** All the node's chunks in a field, scored against a vector, the best first. */
    private List<Chunk> scoredChunks(final NodeProxy proxy, @Nullable final String field, final float[] vector)
            throws XPathException {
        if (field == null) {
            throw new XPathException(this, ErrorCodes.EXXQDY0008, "Option 'vector' needs option 'field', the chunked vector field");
        }
        final LuceneIndexWorker index = (LuceneIndexWorker) context.getBroker().getIndexController().getWorkerByIndexId(LuceneIndex.ID);
        try {
            final List<Chunk> chunks = new ArrayList<>();
            for (final LuceneIndexWorker.ScoredChunk chunk : index.scoreChunks(proxy, field, vector)) {
                chunks.add(new Chunk(field, chunk.number(), chunk.start(), chunk.end(), chunk.score()));
            }
            return chunks;
        } catch (final IOException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, "Error reading the chunks of field '" + field + "': " + e.getMessage(), e);
        } catch (final XPathException e) {
            throw new XPathException(this, e.getErrorCode(), e.getMessage());
        }
    }

    /**
     * The matches to mark, from the highlight option: its query, parsed as ft:query-field parses it for the field, in
     * the configuration of the node's collection, and that field's analyzer for the chunk's text.
     */
    @Nullable
    private Highlight highlight(final NodeProxy proxy, @Nullable final AbstractMapType options) throws XPathException {
        final Sequence option = options != null ? options.get(HIGHLIGHT) : Sequence.EMPTY_SEQUENCE;
        if (option.isEmpty()) {
            return null;
        }
        if (!option.hasOne() || !Type.subTypeOf(option.itemAt(0).getType(), Type.MAP_ITEM)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'highlight' must be a map");
        }
        final AbstractMapType highlight = (AbstractMapType) option.itemAt(0);
        final String field = stringOption(highlight, FIELD);
        final String queryString = stringOption(highlight, QUERY);
        if (field == null || queryString == null) {
            throw new XPathException(this, ErrorCodes.EXXQDY0008, "Option 'highlight' needs the keys 'field' and 'query'");
        }
        final Sequence queryOptions = highlight.get(OPTIONS);
        final QueryOptions parseOptions = queryOptions.isEmpty()
                ? new QueryOptions() : QueryOptions.fromSequence(context, this, queryOptions);
        final LuceneConfig configured = LuceneConfigs.configured(context.getBroker(), proxy.getOwnerDocument().getCollection());
        final LuceneConfig config = configured != null ? configured : LuceneConfig.DEFAULT_CONFIG;
        final LuceneIndexWorker index = (LuceneIndexWorker) context.getBroker().getIndexController().getWorkerByIndexId(LuceneIndex.ID);
        final Query query = index.parseFieldQuery(config, field, queryString, parseOptions);
        return new Highlight(new MatchLocator(List.of(query)), field, config.getAnalyzer(field));
    }

    /** The chunk's text in an exist:chunk element, with an exist:match element around each match. */
    private NodeValue marked(final String text, final Highlight highlight) throws XPathException {
        final List<MatchLocator.Span> spans;
        try {
            spans = highlight.locator().locate(Set.of(highlight.field()), text, highlight.analyzer());
        } catch (final IOException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, "Error marking the matches of field '"
                    + highlight.field() + "': " + e.getMessage(), e);
        }
        context.pushDocumentContext();
        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            builder.startDocument();
            final int nodeNr = builder.startElement(Namespaces.EXIST_NS, "chunk", "exist:chunk", null);
            int position = 0;
            for (final MatchLocator.Span span : spans) {
                if (position < span.start()) {
                    builder.characters(text.substring(position, span.start()));
                }
                builder.startElement(Namespaces.EXIST_NS, "match", "exist:match", null);
                builder.characters(text.substring(span.start(), span.end()));
                builder.endElement();
                position = span.end();
            }
            if (position < text.length()) {
                builder.characters(text.substring(position));
            }
            builder.endElement();
            builder.endDocument();
            return builder.getDocument().getNode(nodeNr);
        } finally {
            context.popDocumentContext();
        }
    }

    @Nullable
    private String stringOption(@Nullable final AbstractMapType options, final StringValue key) throws XPathException {
        final Sequence value = options != null ? options.get(key) : Sequence.EMPTY_SEQUENCE;
        if (value.isEmpty()) {
            return null;
        }
        if (!value.hasOne() || !Type.subTypeOf(value.itemAt(0).getType(), Type.STRING)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be a string");
        }
        return value.getStringValue();
    }

    /**
     * The text the node's chunks of a field were cut from: the field's chunk-units, which may differ from the node's
     * string value, as when they select part of it; the node's string value if the field's configuration isn't found.
     */
    private String chunkedText(final NodeProxy node, final String field) {
        try {
            final LuceneConfig config = LuceneConfigs.configured(context.getBroker(), node.getOwnerDocument().getCollection());
            if (config == null) {
                return node.getStringValue();
            }
            final NodePath path = LuceneMatchListener.getPath(node);
            for (final Iterator<LuceneIndexConfig> i = config.getConfig(path); i != null && i.hasNext(); ) {
                for (final AbstractFieldConfig fieldConfig : i.next().getFacetsAndFields()) {
                    if (fieldConfig instanceof LuceneVectorFieldConfig vectorField && field.equals(vectorField.getName())) {
                        final String text = vectorField.chunkedText(context.getBroker(), node);
                        if (text != null) {
                            return text;
                        }
                    }
                }
            }
        } catch (final XPathException | PermissionDeniedException e) {
            LOG.warn("Could not compute the chunked text of field '{}': {}", field, e.getMessage());
        }
        return node.getStringValue();
    }
}
