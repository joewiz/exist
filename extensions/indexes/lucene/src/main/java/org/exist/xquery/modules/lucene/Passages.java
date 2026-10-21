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
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.WholeBreakIterator;
import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.AbstractFieldConfig;
import org.exist.indexing.lucene.LuceneConfig;
import org.exist.indexing.lucene.LuceneFieldConfig;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneIndexConfig;
import org.exist.indexing.lucene.LuceneIndexWorker;
import org.exist.indexing.lucene.LuceneMatch;
import org.exist.indexing.lucene.LuceneMatchListener;
import org.exist.indexing.lucene.LuceneUtil;
import org.exist.indexing.lucene.NodeText;
import org.exist.storage.NodePath;
import org.exist.util.SentenceBreaker;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.functions.map.AbstractMapType;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;
import org.xml.sax.helpers.AttributesImpl;

import javax.annotation.Nullable;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;

/**
 * Returns the passages of a stored field that best match the query a node was found by, with Lucene's
 * {@link UnifiedHighlighter}: the field's text is cut into passages, by default sentences, each passage is scored by
 * the query's matches in it, and the best are returned, in their order in the text, with their matches.
 */
public class Passages extends BasicFunction {

    private static final QName NAME = new QName("passages", LuceneModule.NAMESPACE_URI, LuceneModule.PREFIX);

    private static final String DESCRIPTION = """
            Returns the passages of the field $field of $hit, a node found by ft:query, that best match the query it \
            was found by, in their order in the field's text, each with the query's matches in it: by default the 3 \
            best sentences. The field's text is its stored value; a field that is not stored but has no expression has \
            the text of $hit, as it was indexed. If $field is empty, the passages are those of the text of $hit, as it \
            was indexed, for a query on the index rather than on a field. Returns the empty sequence if the query has \
            no matches in the text, or if there is no text: a field that is neither stored nor without an \
            expression.""";

    private static final FunctionParameterSequenceType HIT = new FunctionParameterSequenceType("hit", Type.NODE,
            Cardinality.EXACTLY_ONE, "a node found by ft:query");
    private static final FunctionParameterSequenceType FIELD = new FunctionParameterSequenceType("field", Type.STRING,
            Cardinality.ZERO_OR_ONE, "the name of a field, or the empty sequence for the indexed text of $hit");

    public static final FunctionSignature[] signatures = {
            new FunctionSignature(NAME, DESCRIPTION, new SequenceType[] {HIT, FIELD},
                    new FunctionReturnSequenceType(Type.MAP_ITEM, Cardinality.ZERO_OR_MORE, "the passages")),
            new FunctionSignature(NAME, DESCRIPTION + """
                     Each passage is a map with the keys "text", "start" and "end" (the positions of its first \
                    character and after its last in the field's value, or in the indexed text of $hit, from 1, so that substring($value, $start, \
                    $end - $start) is the passage), "score", "matches" (an array of maps with "start" and "end" in \
                    the same way) and "value" (the position of the field's value the passage is in, if the field has several values). $options is a \
                    map with the keys: "max", the number of passages (default 3); "break", "sentence" (the default), \
                    "length" (sentences joined or split to come closest to "length" characters, default 200) or \
                    "whole" (the whole value); "max-length", the most characters of a passage, which a longer passage \
                    is cut to around its matches, at word boundaries, counting each run of whitespace as one character \
                    (default: no limit); "language" and \
                    "abbreviations", as for util:sentences; and "as", \
                    "map" (the default) or "element", for exist:passage elements with start, end and score \
                    attributes and the matches in exist:match elements.""",
                    new SequenceType[] {HIT, FIELD,
                            new FunctionParameterSequenceType("options", Type.MAP_ITEM, Cardinality.EXACTLY_ONE, "The options")},
                    new FunctionReturnSequenceType(Type.ITEM, Cardinality.ZERO_OR_MORE, "the passages"))
    };

    private static final int DEFAULT_MAX = 3;
    private static final int DEFAULT_LENGTH = 200;
    /** The most characters of a field's value that are highlighted, which Lucene requires to be less than Integer.MAX_VALUE. */
    private static final int MAX_LENGTH = Integer.MAX_VALUE - 1;

    private static final StringValue MAX = new StringValue("max");
    private static final StringValue BREAK = new StringValue("break");
    private static final StringValue LENGTH = new StringValue("length");
    private static final StringValue MAX_LENGTH_OPTION = new StringValue("max-length");
    private static final StringValue LANGUAGE = new StringValue("language");
    private static final StringValue ABBREVIATIONS = new StringValue("abbreviations");
    private static final StringValue AS = new StringValue("as");

    /** The text to find passages in: the field's values, the field to highlight them as, and its analyzer. */
    private record Source(String field, Analyzer analyzer, List<String> values) {
    }

    /** A passage of one of the field's values. */
    private record Found(int value, String content, int start, int end, float score, int[] matchStarts, int[] matchEnds) {
    }

    public Passages(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final NodeValue nodeValue = (NodeValue) args[0].itemAt(0);
        if (nodeValue.getImplementationType() != NodeValue.PERSISTENT_NODE) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final NodeProxy hit = (NodeProxy) nodeValue;
        final LuceneMatch match = Field.getMatch(hit);
        if (match == null) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final String field = args[1].isEmpty() ? null : args[1].getStringValue();
        final AbstractMapType options = args.length > 2 ? (AbstractMapType) args[2].itemAt(0) : null;
        final int max = intOption(options, MAX, DEFAULT_MAX);
        final boolean asElements = "element".equals(stringOption(options, AS, "map"));

        final Source source;
        final List<Found> found;
        try {
            source = source(hit, field);
            found = source == null ? List.of() : findPassages(source, match, options, max);
        } catch (final IOException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, "Error highlighting " + (field == null ? "the indexed text" : "field " + field) + ": " + e.getMessage(), e);
        }

        // the best passages of all the values, in their order in the text
        final List<Found> best = found.stream()
                .sorted(Comparator.comparingDouble((Found f) -> f.score()).reversed())
                .limit(max)
                .sorted(Comparator.comparingInt(Found::value).thenComparingInt(Found::start))
                .toList();
        final boolean severalValues = source != null && source.values().size() > 1;
        return asElements ? toElements(best) : toMaps(best, severalValues);
    }

    /**
     * The best passages of each of the field's values.
     */
    private List<Found> findPassages(final Source source, final LuceneMatch match, final AbstractMapType options,
            final int max) throws IOException, XPathException {
        final List<Found> found = new ArrayList<>();
        final SentenceBreaker sentences = sentenceBreaker(options);
        final int maxLength = intOption(options, MAX_LENGTH_OPTION, 0);
        if (maxLength < 0) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'max-length' must not be negative");
        }
        final Supplier<BreakIterator> breaks = breakIterator(options, sentences);
        final org.apache.lucene.search.Query query = highlightable(match.getQuery());
        for (int v = 0; v < source.values().size(); v++) {
            final String content = source.values().get(v);
            if (content != null && !content.isEmpty()) {
                UnifiedHighlighter.builderWithoutSearcher(source.analyzer())
                        .withBreakIterator(breaks)
                        .withMaxLength(MAX_LENGTH)
                        .withMaxNoHighlightPassages(0)
                        .withHandleMultiTermQuery(true)
                        .withHighlightPhrasesStrictly(true)
                        .withFormatter(new Collector(v, sentences, maxLength, found))
                        .build()
                        .highlightWithoutSearcher(source.field(), query, content, max);
            }
        }
        return found;
    }

    /**
     * The text of the field: its stored values; or, if it is not stored and has no expression, or if no field is
     * given, the text of the hit as it was indexed. Null if there is no text.
     */
    @Nullable
    private Source source(final NodeProxy hit, @Nullable final String field) throws IOException {
        final LuceneIndexWorker index = (LuceneIndexWorker) context.getBroker().getIndexController().getWorkerByIndexId(LuceneIndex.ID);
        final LuceneConfig config = index.getLuceneConfig(context.getBroker(), hit.getDocumentSet());
        if (config == null) {
            return null;
        }
        if (field != null) {
            final IndexableField[] stored = index.getFieldByExistDocId(hit.getOwnerDocument().getDocId(), hit.getNodeId(), field);
            if (stored.length > 0) {
                return new Source(field, config.getAnalyzer(field),
                        Arrays.stream(stored).map(IndexableField::stringValue).toList());
            }
        }
        final NodePath path = LuceneMatchListener.getPath(hit);
        for (final Iterator<LuceneIndexConfig> i = config.getConfig(path); i.hasNext(); ) {
            final LuceneIndexConfig indexConfig = i.next();
            if (field == null ? indexConfig.doIndex() : hasFieldWithoutExpression(indexConfig, field)) {
                final String text = NodeText.of(context.getBroker(), config, indexConfig, hit, (nodeId, offset) -> { });
                return field == null
                        ? new Source(contentField(indexConfig), config.getAnalyzer(path), List.of(text))
                        : new Source(field, config.getAnalyzer(field), List.of(text));
            }
        }
        return null;
    }

    private static boolean hasFieldWithoutExpression(final LuceneIndexConfig indexConfig, final String field) {
        for (final AbstractFieldConfig fieldConfig : indexConfig.getFacetsAndFields()) {
            if (fieldConfig instanceof LuceneFieldConfig luceneField && field.equals(luceneField.getName())) {
                return !luceneField.hasExpression();
            }
        }
        return false;
    }

    /** The field the index's text is indexed in, as LuceneIndexWorker names it. */
    private String contentField(final LuceneIndexConfig indexConfig) {
        return indexConfig.isNamed()
                ? indexConfig.getName()
                : LuceneUtil.encodeQName(indexConfig.getQName(), context.getBroker().getBrokerPool().getSymbols());
    }

    /**
     * Collects the passages the highlighter finds in a value, trimmed to their text, extended to the end of the
     * sentence a match runs into, and cut to the maximum length, if there is one.
     */
    private static final class Collector extends PassageFormatter {
        private final int value;
        private final SentenceBreaker sentences;
        private final int maxLength;
        private final List<Found> found;

        private Collector(final int value, final SentenceBreaker sentences, final int maxLength, final List<Found> found) {
            this.value = value;
            this.sentences = sentences;
            this.maxLength = maxLength;
            this.found = found;
        }

        @Override
        public Object format(final Passage[] passages, final String text) {
            final BreakIterator boundaries = sentences.toBreakIterator();
            boundaries.setText(text);
            for (final Passage passage : passages) {
                int start = passage.getStartOffset();
                int end = passage.getEndOffset();
                // a match that runs into the next sentence extends the passage to the match's end only: take in the
                // rest of that sentence
                if (end < text.length() && !boundaries.isBoundary(end)) {
                    final int following = boundaries.following(end);
                    end = following == BreakIterator.DONE ? text.length() : following;
                }
                // a passage ends after the whitespace that follows its last sentence
                while (start < end && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
                final int[] matchStarts = Arrays.copyOf(passage.getMatchStarts(), passage.getNumMatches());
                final int[] matchEnds = Arrays.copyOf(passage.getMatchEnds(), passage.getNumMatches());
                if (maxLength > 0 && end - start > maxLength && matchStarts.length > 0) {
                    final int[] window = cut(text, start, end, matchStarts, matchEnds, maxLength);
                    start = window[0];
                    end = window[1];
                }
                found.add(new Found(value, text, start, end, passage.getScore(), matchStarts, matchEnds));
            }
            return null;
        }

        /**
         * A window of at most maxLength characters of the passage from start to end, as it is shown, with each run of
         * whitespace as one character: centered on as many of its matches, from the first, as fit whole, and moved in
         * to word boundaries.
         */
        private static int[] cut(final String text, final int start, final int end, final int[] matchStarts,
                final int[] matchEnds, final int maxLength) {
            final int[] shown = shownLengths(text, start, end);
            final int first = shown[matchStarts[0] - start];
            int last = Math.min(shown[matchEnds[0] - start], first + maxLength);
            for (final int matchEnd : matchEnds) {
                if (shown[matchEnd - start] <= first + maxLength) {
                    last = Math.max(last, shown[matchEnd - start]);
                }
            }
            final int to = Math.min(shown[end - start], Math.max(0, first - (maxLength - (last - first)) / 2) + maxLength);
            final int from = Math.max(0, to - maxLength);
            final int matchesStart = matchStarts[0];
            final int matchesEnd = start + offsetOf(shown, last);
            return new int[] {
                    startOfWord(text, start + offsetOf(shown, from), start, matchesStart),
                    endOfWord(text, start + offsetOf(shown, to), end, matchesEnd)
            };
        }

        /** For each offset from start, the number of characters before it, from start, with whitespace collapsed. */
        private static int[] shownLengths(final String text, final int start, final int end) {
            final int[] shown = new int[end - start + 1];
            for (int i = start; i < end; i++) {
                final boolean collapsed = Character.isWhitespace(text.charAt(i)) && i > start
                        && Character.isWhitespace(text.charAt(i - 1));
                shown[i - start + 1] = shown[i - start] + (collapsed ? 0 : 1);
            }
            return shown;
        }

        /** The first offset, from start, with this many characters shown before it. */
        private static int offsetOf(final int[] shown, final int length) {
            int offset = 0;
            while (offset < shown.length - 1 && shown[offset] < length) {
                offset++;
            }
            return offset;
        }

        /** The start of the first word from offset, at most limit, skipping whitespace and a cut word. */
        private static int startOfWord(final String text, final int offset, final int start, final int limit) {
            int from = offset;
            if (from > start && !Character.isWhitespace(text.charAt(from - 1))) {
                while (from < limit && !Character.isWhitespace(text.charAt(from))) {
                    from++;
                }
            }
            while (from < limit && Character.isWhitespace(text.charAt(from))) {
                from++;
            }
            return from < text.length() && Character.isLowSurrogate(text.charAt(from)) ? from + 1 : from;
        }

        /** The end of the last word before offset, at least limit, leaving out whitespace and a cut word. */
        private static int endOfWord(final String text, final int offset, final int end, final int limit) {
            int to = offset;
            if (to < end && !Character.isWhitespace(text.charAt(to))) {
                while (to > limit && !Character.isWhitespace(text.charAt(to - 1))) {
                    to--;
                }
            }
            while (to > limit && Character.isWhitespace(text.charAt(to - 1))) {
                to--;
            }
            return to < text.length() && Character.isLowSurrogate(text.charAt(to)) ? to - 1 : to;
        }
    }

    /**
     * The query to highlight with. Without a searcher, the highlighter runs the query against the field's text alone,
     * so a clause that must match another field, such as the filter on the index's type eXist adds, would never
     * match: such clauses are dropped, and the clauses that must match become clauses that may.
     */
    static org.apache.lucene.search.Query highlightable(final org.apache.lucene.search.Query query) {
        if (!(query instanceof BooleanQuery booleanQuery)) {
            return query;
        }
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int clauses = 0;
        org.apache.lucene.search.Query only = null;
        for (final BooleanClause clause : booleanQuery.clauses()) {
            if (clause.occur() == BooleanClause.Occur.MUST || clause.occur() == BooleanClause.Occur.SHOULD) {
                only = highlightable(clause.query());
                builder.add(only, BooleanClause.Occur.SHOULD);
                clauses++;
            }
        }
        return clauses == 1 ? only : builder.build();
    }

    private static SentenceBreaker sentenceBreaker(final AbstractMapType options) throws XPathException {
        final String language = stringOption(options, LANGUAGE, SentenceBreaker.Options.DEFAULT.language());
        final List<String> abbreviations = new ArrayList<>();
        if (options != null) {
            for (final SequenceIterator i = options.get(ABBREVIATIONS).iterate(); i.hasNext(); ) {
                abbreviations.add(i.nextItem().getStringValue());
            }
        }
        return new SentenceBreaker(new SentenceBreaker.Options(language, abbreviations, true, true, true, false));
    }

    private Supplier<BreakIterator> breakIterator(final AbstractMapType options, final SentenceBreaker sentences)
            throws XPathException {
        final String kind = stringOption(options, BREAK, "sentence");
        return switch (kind) {
            case "sentence" -> sentences::toBreakIterator;
            case "length" -> {
                final int length = intOption(options, LENGTH, DEFAULT_LENGTH);
                yield () -> LengthGoalBreakIterator.createClosestToLength(sentences.toBreakIterator(), length, 0.5f);
            }
            case "whole" -> WholeBreakIterator::new;
            default -> throw new XPathException(this, ErrorCodes.XPTY0004,
                    "Option 'break' must be 'sentence', 'length' or 'whole', got '" + kind + "'");
        };
    }

    private Sequence toMaps(final List<Found> passages, final boolean severalValues) throws XPathException {
        final ValueSequence result = new ValueSequence(passages.size());
        for (final Found passage : passages) {
            final List<Sequence> matches = new ArrayList<>(passage.matchStarts().length);
            for (int m = 0; m < passage.matchStarts().length; m++) {
                if (passage.matchEnds()[m] <= passage.start() || passage.matchStarts()[m] >= passage.end()) {
                    continue;
                }
                matches.add(new MapType(this, context, null, List.of(
                        Tuple(new StringValue(this, "start"), new IntegerValue(this, position(passage.content(), passage.matchStarts()[m]))),
                        Tuple(new StringValue(this, "end"), new IntegerValue(this, position(passage.content(), passage.matchEnds()[m]))))));
            }
            final List<Tuple2<AtomicValue, Sequence>> entries = new ArrayList<>(List.of(
                    Tuple(new StringValue(this, "text"), new StringValue(this, passage.content().substring(passage.start(), passage.end()))),
                    Tuple(new StringValue(this, "start"), new IntegerValue(this, position(passage.content(), passage.start()))),
                    Tuple(new StringValue(this, "end"), new IntegerValue(this, position(passage.content(), passage.end()))),
                    Tuple(new StringValue(this, "score"), new DoubleValue(this, passage.score())),
                    Tuple(new StringValue(this, "matches"), new ArrayType(this, context, matches))));
            if (severalValues) {
                entries.add(Tuple(new StringValue(this, "value"), new IntegerValue(this, passage.value() + 1)));
            }
            result.add(new MapType(this, context, null, entries));
        }
        return result;
    }

    private Sequence toElements(final List<Found> passages) {
        context.pushDocumentContext();
        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            builder.startDocument();
            final List<Integer> nodes = new ArrayList<>(passages.size());
            for (final Found passage : passages) {
                final AttributesImpl attributes = new AttributesImpl();
                attributes.addAttribute("", "start", "start", "CDATA", Integer.toString(position(passage.content(), passage.start())));
                attributes.addAttribute("", "end", "end", "CDATA", Integer.toString(position(passage.content(), passage.end())));
                attributes.addAttribute("", "score", "score", "CDATA", Float.toString(passage.score()));
                nodes.add(builder.startElement(Namespaces.EXIST_NS, "passage", "exist:passage", attributes));
                int at = passage.start();
                for (int m = 0; m < passage.matchStarts().length; m++) {
                    final int matchStart = Math.max(at, passage.matchStarts()[m]);
                    final int matchEnd = Math.min(passage.end(), passage.matchEnds()[m]);
                    if (matchStart >= matchEnd) {
                        continue;
                    }
                    if (at < matchStart) {
                        builder.characters(passage.content().substring(at, matchStart));
                    }
                    builder.startElement(Namespaces.EXIST_NS, "match", "exist:match", null);
                    builder.characters(passage.content().substring(matchStart, matchEnd));
                    builder.endElement();
                    at = matchEnd;
                }
                if (at < passage.end()) {
                    builder.characters(passage.content().substring(at, passage.end()));
                }
                builder.endElement();
            }
            builder.endDocument();
            final ValueSequence result = new ValueSequence(nodes.size());
            for (final int node : nodes) {
                result.add(builder.getDocument().getNode(node));
            }
            return result;
        } finally {
            context.popDocumentContext();
        }
    }

    /** The position, from 1, of the character at a UTF-16 offset, as substring counts characters. */
    private static int position(final String text, final int offset) {
        return text.codePointCount(0, offset) + 1;
    }

    private int intOption(final AbstractMapType options, final StringValue key, final int defaultValue) throws XPathException {
        if (options == null || options.get(key).isEmpty()) {
            return defaultValue;
        }
        final Sequence value = options.get(key);
        if (!value.hasOne() || !Type.subTypeOf(value.itemAt(0).getType(), Type.INTEGER)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be an integer");
        }
        return value.itemAt(0).toJavaObject(Integer.class);
    }

    private static String stringOption(final AbstractMapType options, final StringValue key, final String defaultValue) throws XPathException {
        if (options == null || options.get(key).isEmpty()) {
            return defaultValue;
        }
        return options.get(key).getStringValue();
    }
}
