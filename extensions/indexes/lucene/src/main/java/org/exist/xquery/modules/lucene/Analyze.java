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
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.FieldText;
import org.exist.indexing.lucene.LuceneConfig;
import org.exist.indexing.lucene.LuceneConfigs;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneIndexConfig;
import org.exist.indexing.lucene.LuceneIndexWorker;
import org.exist.indexing.lucene.LuceneMatchListener;
import org.exist.indexing.lucene.LuceneUtil;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.NodePath;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.map.AbstractMapType;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;
import static org.exist.xquery.FunctionDSL.arities;
import static org.exist.xquery.FunctionDSL.arity;
import static org.exist.xquery.FunctionDSL.optParam;
import static org.exist.xquery.FunctionDSL.param;
import static org.exist.xquery.FunctionDSL.returnsOptMany;
import static org.exist.xquery.modules.lucene.LuceneModule.functionSignatures;

/**
 * Shows how the Lucene index sees a node: the text it indexes for the node or one of its fields
 * ({@code ft:field-text}), and the tokens an analyzer makes of a text ({@code ft:analyze}).
 */
public class Analyze extends BasicFunction {

    private static final FunctionParameterSequenceType PARAM_NODE = param("node", Type.NODE,
            "a stored node an index applies to");
    private static final FunctionParameterSequenceType PARAM_FIELD = optParam("field", Type.STRING,
            "the name of a field of the index; if empty, the index itself");

    private static final String FIELD_TEXT_NAME = "field-text";
    static final FunctionSignature[] FS_FIELD_TEXT = functionSignatures(
            FIELD_TEXT_NAME,
            "Returns the text the Lucene index has for a node: for a field, its values, either stored or, for a "
                    + "field that isn't stored, computed from the node as indexing computes them (the field's "
                    + "condition and expression, or the node's text); without a field, the node's text as the index "
                    + "extracts it, without the elements it ignores and with spaces between those it doesn't declare "
                    + "inline. Returns the empty sequence for a node no index applies to, or that isn't stored.",
            returnsOptMany(Type.STRING, "the texts"),
            arities(
                    arity(PARAM_NODE),
                    arity(PARAM_NODE, PARAM_FIELD)
            )
    );

    private static final String ANALYZE_NAME = "analyze";
    private static final String OPTION_FOR = "for";
    private static final String OPTION_ANALYZER = "analyzer";
    static final FunctionSignature[] FS_ANALYZE = functionSignatures(
            ANALYZE_NAME,
            "Returns the tokens the analyzer of a field, or of the index, that applies to a node makes of a text, "
                    + "in order, each as a map with 'term', 'start' and 'end' (the token's characters, as positions "
                    + "in the text: substring($text, start, end - start)), 'position' (from 1; tokens at the same "
                    + "position, such as a word and its parts, share it), 'position-length' and 'type'. "
                    + "Options: 'for': 'index' (default) or 'query', to use the analyzer a query uses, which may "
                    + "differ; 'analyzer': the id of an analyzer of the collection's configuration, to use instead.",
            returnsOptMany(Type.MAP_ITEM, "the tokens"),
            arities(
                    arity(PARAM_NODE, PARAM_FIELD, param("text", Type.STRING, "the text to analyze")),
                    arity(PARAM_NODE, PARAM_FIELD, param("text", Type.STRING, "the text to analyze"),
                            optParam("options", Type.MAP_ITEM, "options: 'for' and 'analyzer'"))
            )
    );

    public Analyze(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final NodeValue nodeValue = (NodeValue) args[0].itemAt(0);
        if (nodeValue.getImplementationType() != NodeValue.PERSISTENT_NODE) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final NodeProxy node = (NodeProxy) nodeValue;
        final String field = args.length > 1 && !args[1].isEmpty() ? args[1].getStringValue() : null;
        final LuceneIndexWorker worker = (LuceneIndexWorker) context.getBroker().getIndexController()
                .getWorkerByIndexId(LuceneIndex.ID);
        final LuceneConfig config = LuceneConfigs.configured(context.getBroker(), node.getOwnerDocument().getCollection());
        try {
            if (isCalledAs(FIELD_TEXT_NAME)) {
                return fieldText(worker, config, node, field);
            }
            final AbstractMapType options = args.length > 3 && !args[3].isEmpty()
                    ? (AbstractMapType) args[3].itemAt(0) : null;
            return analyze(worker, config, node, field, args[2].getStringValue(), options);
        } catch (final IOException | PermissionDeniedException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, e.getMessage(), e);
        }
    }

    private Sequence fieldText(final LuceneIndexWorker worker, final LuceneConfig config, final NodeProxy node,
            @Nullable final String field) throws IOException, XPathException, PermissionDeniedException {
        final ValueSequence result = new ValueSequence();
        if (field != null) {
            for (final String value : FieldText.values(worker, context.getBroker(), config, node, field)) {
                result.add(new StringValue(this, value));
            }
        } else {
            final LuceneIndexConfig indexConfig = indexConfig(config, node);
            if (indexConfig != null) {
                result.add(new StringValue(this, FieldText.indexedText(context.getBroker(), config, indexConfig, node)));
            }
        }
        return result;
    }

    private Sequence analyze(final LuceneIndexWorker worker, final LuceneConfig config, final NodeProxy node,
            @Nullable final String field, final String text, @Nullable final AbstractMapType options)
            throws IOException, XPathException {
        final boolean forQuery = forQuery(options);
        final String analyzerId = stringOption(options, OPTION_ANALYZER);
        final Analyzer analyzer;
        final String fieldName;
        if (field != null) {
            fieldName = field;
            if (analyzerId != null) {
                analyzer = analyzerById(config, analyzerId);
            } else if (forQuery) {
                analyzer = worker.getQueryAnalyzer(config, field, null, new QueryOptions());
            } else {
                final FieldText.Config fieldConfig = FieldText.config(config, LuceneMatchListener.getPath(node), field);
                // the index's analyzer chooses the field's own, or its default for a field without one
                analyzer = fieldConfig != null ? fieldConfig.index().getAnalyzer() : config.getAnalyzer(field);
            }
        } else {
            final LuceneIndexConfig indexConfig = indexConfig(config, node);
            if (indexConfig == null) {
                return Sequence.EMPTY_SEQUENCE;
            }
            fieldName = contentField(indexConfig);
            if (analyzerId != null) {
                analyzer = analyzerById(config, analyzerId);
            } else if (forQuery) {
                analyzer = worker.getQueryAnalyzer(config, null, indexConfig.getQName(), new QueryOptions());
            } else {
                analyzer = indexConfig.getAnalyzer();
            }
        }
        return tokens(analyzer, fieldName, text);
    }

    private Sequence tokens(final Analyzer analyzer, final String field, final String text)
            throws IOException, XPathException {
        final ValueSequence result = new ValueSequence();
        try (final TokenStream stream = analyzer.tokenStream(field, text)) {
            final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            final OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
            final PositionIncrementAttribute increment = stream.addAttribute(PositionIncrementAttribute.class);
            final PositionLengthAttribute length = stream.addAttribute(PositionLengthAttribute.class);
            final TypeAttribute type = stream.addAttribute(TypeAttribute.class);
            stream.reset();
            int position = 0;
            while (stream.incrementToken()) {
                position += increment.getPositionIncrement();
                final List<Tuple2<AtomicValue, Sequence>> entries = new ArrayList<>(6);
                entries.add(Tuple(new StringValue(this, "term"), new StringValue(this, term.toString())));
                entries.add(Tuple(new StringValue(this, "start"), new IntegerValue(this, position(text, offset.startOffset()))));
                entries.add(Tuple(new StringValue(this, "end"), new IntegerValue(this, position(text, offset.endOffset()))));
                entries.add(Tuple(new StringValue(this, "position"), new IntegerValue(this, Math.max(position, 1))));
                entries.add(Tuple(new StringValue(this, "position-length"), new IntegerValue(this, length.getPositionLength())));
                entries.add(Tuple(new StringValue(this, "type"), new StringValue(this, type.type())));
                result.add(new MapType(this, context, null, entries));
            }
            stream.end();
        }
        return result;
    }

    /** The position, from 1, in characters of the XQuery string, of a UTF-16 offset in it. */
    private static int position(final String text, final int offset) {
        final int bounded = Math.max(0, Math.min(offset, text.length()));
        return text.codePointCount(0, bounded) + 1;
    }

    private boolean forQuery(@Nullable final AbstractMapType options) throws XPathException {
        final String value = stringOption(options, OPTION_FOR);
        if (value == null || "index".equals(value)) {
            return false;
        }
        if ("query".equals(value)) {
            return true;
        }
        throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'for' must be 'index' or 'query', not '" + value + "'");
    }

    private Analyzer analyzerById(final LuceneConfig config, final String id) throws XPathException {
        final Analyzer analyzer = config.getAnalyzerById(id);
        if (analyzer == null) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "No analyzer with the id '" + id + "' in the collection's configuration");
        }
        return analyzer;
    }

    @Nullable
    private String stringOption(@Nullable final AbstractMapType options, final String key) throws XPathException {
        if (options == null) {
            return null;
        }
        final Sequence value = options.get(new StringValue(this, key));
        return value == null || value.isEmpty() ? null : value.getStringValue();
    }

    /** The index that applies to the node, which indexes its text. */
    @Nullable
    private static LuceneIndexConfig indexConfig(final LuceneConfig config, final NodeProxy node) {
        final NodePath path = LuceneMatchListener.getPath(node);
        for (final Iterator<LuceneIndexConfig> i = config.getConfig(path); i != null && i.hasNext(); ) {
            final LuceneIndexConfig indexConfig = i.next();
            if (indexConfig.match(path) && indexConfig.doIndex()) {
                return indexConfig;
            }
        }
        return null;
    }

    /** The field the index's text is indexed in, as LuceneIndexWorker names it. */
    private String contentField(final LuceneIndexConfig indexConfig) {
        return indexConfig.isNamed()
                ? indexConfig.getName()
                : LuceneUtil.encodeQName(indexConfig.getQName(), context.getBroker().getBrokerPool().getSymbols());
    }
}
