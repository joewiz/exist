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
import org.exist.dom.QName;
import org.exist.dom.persistent.Match;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneMatch;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
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

import java.util.ArrayList;
import java.util.List;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;

/**
 * Returns the chunks of a node that a query of a chunked vector field matched, so that a result can show the passage
 * that matched.
 */
public class VectorChunks extends BasicFunction {

    public static final FunctionSignature signature = new FunctionSignature(
            new QName("vector-chunks", LuceneModule.NAMESPACE_URI, LuceneModule.PREFIX),
            """
            Returns the chunks of the node that a query of a chunked vector field (a vector-field with chunk="tokens") \
            matched, the best one first, as maps with the keys "field" (the vector field), "chunk" (the chunk's \
            position among the node's chunks, from 1), "start" and "end" (the offsets of its first character and after \
            its last character in the node's chunked text, counted in characters from 0, which with the default \
            chunk-units is the node's string value), "text" (the chunk's text, if it is within the node's string \
            value) and "score". Returns the empty sequence if the node was not matched by such a query.""",
            new SequenceType[] {
                    new FunctionParameterSequenceType("node", Type.NODE, Cardinality.EXACTLY_ONE,
                            "a node returned by ft:query-vector or ft:query-field-vector")
            },
            new FunctionReturnSequenceType(Type.MAP_ITEM, Cardinality.ZERO_OR_MORE, "the matched chunks"));

    public VectorChunks(final XQueryContext context) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final NodeValue nodeValue = (NodeValue) args[0].itemAt(0);
        if (nodeValue.getImplementationType() != NodeValue.PERSISTENT_NODE) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final NodeProxy proxy = (NodeProxy) nodeValue;
        final List<LuceneMatch> matches = new ArrayList<>();
        for (Match match = proxy.getMatches(); match != null; match = match.getNextMatch()) {
            if (match.getIndexId().equals(LuceneIndex.ID) && ((LuceneMatch) match).getChunk() != null) {
                matches.add((LuceneMatch) match);
            }
        }
        if (matches.isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }
        matches.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

        final String value = proxy.getStringValue();
        final ValueSequence result = new ValueSequence(matches.size());
        for (final LuceneMatch match : matches) {
            final LuceneMatch.Chunk chunk = match.getChunk();
            final boolean inValue = chunk.end() <= value.length() && chunk.start() <= chunk.end();
            final List<Tuple2<AtomicValue, Sequence>> entries = new ArrayList<>(6);
            entries.add(Tuple(new StringValue(this, "field"), new StringValue(this, chunk.field())));
            entries.add(Tuple(new StringValue(this, "chunk"), new IntegerValue(this, chunk.number() + 1)));
            // the offsets are in UTF-16 code units, XPath counts characters
            final int start = inValue ? value.codePointCount(0, chunk.start()) : chunk.start();
            final int end = inValue ? start + value.codePointCount(chunk.start(), chunk.end()) : chunk.end();
            entries.add(Tuple(new StringValue(this, "start"), new IntegerValue(this, start)));
            entries.add(Tuple(new StringValue(this, "end"), new IntegerValue(this, end)));
            if (inValue) {
                entries.add(Tuple(new StringValue(this, "text"), new StringValue(this, value.substring(chunk.start(), chunk.end()))));
            }
            entries.add(Tuple(new StringValue(this, "score"), new FloatValue(this, match.getScore())));
            result.add(new MapType(this, context, null, entries));
        }
        return result;
    }
}
