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
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.WholeBreakIterator;
import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.FieldText;
import org.exist.indexing.lucene.LuceneConfig;
import org.exist.indexing.lucene.LuceneConfigs;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneIndexConfig;
import org.exist.indexing.lucene.LuceneIndexWorker;
import org.exist.indexing.lucene.LuceneMatch;
import org.exist.indexing.lucene.LuceneMatchListener;
import org.exist.indexing.lucene.LuceneUtil;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.NodePath;
import org.exist.util.SentenceBreaker;
import org.exist.xquery.AnalyzeContextInfo;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.functions.map.AbstractMapType;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.functions.util.Sentences;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReference;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.NumericValue;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;
import org.xml.sax.SAXException;
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
            best sentences. The field's text is its stored values, or, for a field that is not stored, its values \
            computed from $hit as indexing computes them (its condition, and its expression or the text of $hit as it \
            was indexed). If $field is empty, the passages are those of the text of $hit, as it was indexed, for a \
            query on the index rather than on a field. Returns the empty sequence if the query has no matches in the \
            text.""";

    private static final FunctionParameterSequenceType HIT = new FunctionParameterSequenceType("hit", Type.NODE,
            Cardinality.EXACTLY_ONE, "a node found by ft:query");
    private static final FunctionParameterSequenceType FIELD = new FunctionParameterSequenceType("field", Type.STRING,
            Cardinality.ZERO_OR_ONE, "the name of a field, or the empty sequence for the indexed text of $hit");

    public static final FunctionSignature[] signatures = {
            new FunctionSignature(NAME, DESCRIPTION, new SequenceType[] {HIT, FIELD},
                    new FunctionReturnSequenceType(Type.ELEMENT, Cardinality.ZERO_OR_MORE, "the passages")),
            new FunctionSignature(NAME, DESCRIPTION + """
                     $options is a map with the keys: "max", the number of passages (default 3); "order", "document" \
                    (the default), for the passages in their order in the text, or "score", best first; "break", \
                    "sentence" (the default), "length" (sentences joined or split to come closest to "length" \
                    characters, default 200) or "whole" (the whole value); "max-length", the most characters of a \
                    passage, which a longer passage is cut to around its matches, at word boundaries, counting each run \
                    of whitespace as one character (default: no limit); "align", where the matches are put in a \
                    passage that is cut, or in one of "length", as the share of the passage before the first match, \
                    from 0 to 1 (default 0.33); "max-analyzed", the most characters of the text in which matches are \
                    looked for (default 1000000); the options of util:sentences for the sentences: "language", "abbreviations", "cldr-abbreviations", \
                    "merge-initials", "max-initials", "merge-quoted-continuation", "dash-as-space", "break-at-newlines" \
                    and "rules"; "as", \
                    "element" (the default) or "map"; and, for elements, "mark", the name of the element each match is \
                    in instead of exist:match, such as "mark", or "highlight", a function called with the text of \
                    each match, whose result replaces it. An element is an exist:passage with "start", "end", \
                    "score", "trimmed-start" and "trimmed-end" attributes. A map has the keys "text", "start" and "end" (the positions of its first \
                    character and after its last in the field's value, or in the indexed text of $hit, from 1, so that \
                    substring($value, $start, $end - $start) is the passage), "score", "matches" (an array of maps \
                    with "start" and "end" in the same way), "trimmed-start" and "trimmed-end" (true if "max-length" cut the \
                    passage short at its start or its end, not merely removed whitespace: it starts after, or ends before, its sentence, or the unit of \
                    "length" or "whole"; false at a sentence's boundary and at the start or end of the text) and \
                    "value" (the position of the field's value the passage is in, if the field has several values).""",
                    new SequenceType[] {HIT, FIELD,
                            new FunctionParameterSequenceType("options", Type.MAP_ITEM, Cardinality.EXACTLY_ONE, "The options")},
                    new FunctionReturnSequenceType(Type.ITEM, Cardinality.ZERO_OR_MORE, "the passages"))
    };

    private static final int DEFAULT_MAX = 3;
    private static final int DEFAULT_LENGTH = 200;
    /** The most characters of a value in which matches are looked for, by default: Elasticsearch's default too. */
    private static final int DEFAULT_MAX_ANALYZED = 1_000_000;
    /** The share of a cut passage before its first match, by default: Solr's default too. */
    private static final double DEFAULT_ALIGN = 0.33;

    private static final StringValue MAX = new StringValue("max");
    private static final StringValue BREAK = new StringValue("break");
    private static final StringValue LENGTH = new StringValue("length");
    private static final StringValue MAX_LENGTH_OPTION = new StringValue("max-length");
    private static final StringValue MAX_ANALYZED = new StringValue("max-analyzed");
    private static final StringValue ORDER = new StringValue("order");
    private static final StringValue ALIGN = new StringValue("align");
    private static final StringValue MARK = new StringValue("mark");
    private static final StringValue HIGHLIGHT = new StringValue("highlight");
    private static final StringValue AS = new StringValue("as");

    /** The text to find passages in: the field's values, the field to highlight them as, and its analyzer. */
    private record Source(String field, Analyzer analyzer, List<String> values) {
    }

    /** A passage of one of the field's values. */
    private record Found(int value, String content, int start, int end, float score, int[] matchStarts, int[] matchEnds,
            boolean trimmedStart, boolean trimmedEnd) {
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
        final Output output = output(options);
        final int max = intOption(options, MAX, DEFAULT_MAX);

        final Source source = source(hit, field);
        if (source == null) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final List<Found> best = best(findPassages(source, match, options, max), max, output.byScore());
        if (!output.asElements()) {
            return toMaps(best, source.values().size() > 1);
        }
        return toElements(best, output.mark(), output.highlight() == null ? null : highlighted(best, output.highlight()));
    }

    /** How the passages are returned. */
    private record Output(boolean asElements, boolean byScore, @Nullable String mark, @Nullable FunctionReference highlight) {
    }

    private Output output(@Nullable final AbstractMapType options) throws XPathException {
        final boolean asElements = choice(options, AS, "element", "map");
        final boolean byScore = !choice(options, ORDER, "document", "score");
        final String mark = stringOption(options, MARK, null);
        final FunctionReference highlight = functionOption(options, HIGHLIGHT);
        if (!asElements && (mark != null || highlight != null)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Options 'mark' and 'highlight' are for elements, not maps");
        }
        if (mark != null && highlight != null) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Options 'mark' and 'highlight' cannot both be given");
        }
        return new Output(asElements, byScore, mark, highlight);
    }

    /** The best passages of all the values, best first or in their order in the text. */
    private static List<Found> best(final List<Found> found, final int max, final boolean byScore) {
        final Comparator<Found> bestFirst = Comparator.comparingDouble((Found f) -> f.score()).reversed();
        return found.stream()
                .sorted(bestFirst)
                .limit(max)
                .sorted(byScore ? bestFirst : Comparator.comparingInt(Found::value).thenComparingInt(Found::start))
                .toList();
    }

    /**
     * The best passages of each of the field's values.
     */
    private List<Found> findPassages(final Source source, final LuceneMatch match, final AbstractMapType options,
            final int max) throws XPathException {
        final List<Found> found = new ArrayList<>();
        final SentenceBreaker sentences = Sentences.sentenceBreaker(this, options);
        final int maxLength = intOption(options, MAX_LENGTH_OPTION, 0);
        if (maxLength < 0) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'max-length' must not be negative");
        }
        final int maxAnalyzed = intOption(options, MAX_ANALYZED, DEFAULT_MAX_ANALYZED);
        if (maxAnalyzed < 1) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'max-analyzed' must be positive");
        }
        final double align = doubleOption(options, ALIGN, DEFAULT_ALIGN);
        if (align < 0 || align > 1) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option 'align' must be from 0 to 1");
        }
        final Supplier<BreakIterator> breaks = breakIterator(options, sentences, (float) align);
        final org.apache.lucene.search.Query query = highlightable(match.getQuery());
        for (int v = 0; v < source.values().size(); v++) {
            final String content = analyzed(source.values().get(v), maxAnalyzed);
            if (content != null && !content.isEmpty()) {
                final UnifiedHighlighter highlighter = UnifiedHighlighter.builderWithoutSearcher(source.analyzer())
                        .withBreakIterator(breaks)
                        // the text is cut to max-analyzed already: Lucene cuts only values it loads itself
                        .withMaxLength(Integer.MAX_VALUE - 1)
                        .withMaxNoHighlightPassages(0)
                        .withHandleMultiTermQuery(true)
                        .withHighlightPhrasesStrictly(true)
                        .withFormatter(new Collector(v, sentences, maxLength, align, found))
                        .build();
                try {
                    highlighter.highlightWithoutSearcher(source.field(), query, content, max);
                } catch (final IOException e) {
                    throw new XPathException(this, LuceneModule.EXXQDYFT0002, "Error highlighting " + source.field() + ": " + e.getMessage(), e);
                }
            }
        }
        return found;
    }

    /**
     * The text of the field: its stored values, or, if it is not stored, its values computed from the hit as
     * indexing computes them; or, if no field is given, the text of the hit as it was indexed. Null if there is no
     * text.
     */
    @Nullable
    private Source source(final NodeProxy hit, @Nullable final String field) throws XPathException {
        try {
            return readSource(hit, field);
        } catch (final IOException | PermissionDeniedException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, "Error reading " + (field == null ? "the indexed text" : "field " + field) + ": " + e.getMessage(), e);
        }
    }

    @Nullable
    private Source readSource(final NodeProxy hit, @Nullable final String field)
            throws IOException, XPathException, PermissionDeniedException {
        final LuceneIndexWorker index = (LuceneIndexWorker) context.getBroker().getIndexController().getWorkerByIndexId(LuceneIndex.ID);
        final LuceneConfig config = LuceneConfigs.configured(context.getBroker(), hit.getOwnerDocument().getCollection());
        if (config == null) {
            return null;
        }
        if (field != null) {
            final List<String> values = FieldText.values(index, context.getBroker(), config, hit, field);
            return values.isEmpty() ? null : new Source(field, config.getAnalyzer(field), values);
        }
        final NodePath path = LuceneMatchListener.getPath(hit);
        for (final Iterator<LuceneIndexConfig> i = config.getConfig(path); i.hasNext(); ) {
            final LuceneIndexConfig indexConfig = i.next();
            if (indexConfig.doIndex()) {
                final String text = FieldText.indexedText(context.getBroker(), config, indexConfig, hit);
                return new Source(contentField(indexConfig), config.getAnalyzer(path), List.of(text));
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

    /**
     * Collects the passages the highlighter finds in a value, trimmed to their text, extended to the end of the
     * sentence a match runs into, and cut to the maximum length, if there is one.
     */
    private static final class Collector extends PassageFormatter {
        private final int value;
        private final SentenceBreaker sentences;
        private final int maxLength;
        private final double align;
        private final List<Found> found;

        private Collector(final int value, final SentenceBreaker sentences, final int maxLength, final double align,
                final List<Found> found) {
            this.value = value;
            this.sentences = sentences;
            this.maxLength = maxLength;
            this.align = align;
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
                // the sentence, or the unit of "length" or "whole", before max-length cuts it
                final int unitStart = start;
                final int unitEnd = end;
                if (maxLength > 0 && end - start > maxLength && matchStarts.length > 0) {
                    final int[] window = cut(text, start, end, matchStarts, matchEnds, maxLength, align);
                    start = window[0];
                    end = window[1];
                }
                found.add(new Found(value, text, start, end, passage.getScore(), matchStarts, matchEnds,
                        start > unitStart, end < unitEnd));
            }
            return null;
        }

        /**
         * A window of at most maxLength characters of the passage from start to end, as it is shown, with each run of
         * whitespace as one character: around as many of its matches, from the first, as fit whole, with the share
         * align of the rest before them, and moved in to word boundaries.
         */
        private static int[] cut(final String text, final int start, final int end, final int[] matchStarts,
                final int[] matchEnds, final int maxLength, final double align) {
            final int[] shown = shownLengths(text, start, end);
            final int first = shown[matchStarts[0] - start];
            int last = Math.min(shown[matchEnds[0] - start], first + maxLength);
            for (final int matchEnd : matchEnds) {
                if (shown[matchEnd - start] <= first + maxLength) {
                    last = Math.max(last, shown[matchEnd - start]);
                }
            }
            final int before = (int) Math.round((maxLength - (last - first)) * align);
            final int to = Math.min(shown[end - start], Math.max(0, first - before) + maxLength);
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
     * match: such clauses are dropped, and the clauses that must match become clauses that may. The drill-down query
     * eXist wraps the query in for facets is unwrapped: its dimensions are fields of their own.
     */
    static org.apache.lucene.search.Query highlightable(final org.apache.lucene.search.Query query) {
        if (query instanceof DrillDownQuery drillDown) {
            return highlightable(drillDown.getBaseQuery());
        }
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

    private Supplier<BreakIterator> breakIterator(final AbstractMapType options, final SentenceBreaker sentences,
            final float align) throws XPathException {
        final String kind = stringOption(options, BREAK, "sentence");
        return switch (kind) {
            case "sentence" -> sentences::toBreakIterator;
            case "length" -> {
                final int length = intOption(options, LENGTH, DEFAULT_LENGTH);
                yield () -> LengthGoalBreakIterator.createClosestToLength(sentences.toBreakIterator(), length, align);
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
                    Tuple(new StringValue(this, "trimmed-start"), BooleanValue.valueOf(passage.trimmedStart())),
                    Tuple(new StringValue(this, "trimmed-end"), BooleanValue.valueOf(passage.trimmedEnd())),
                    Tuple(new StringValue(this, "matches"), new ArrayType(this, context, matches))));
            if (severalValues) {
                entries.add(Tuple(new StringValue(this, "value"), new IntegerValue(this, passage.value() + 1)));
            }
            result.add(new MapType(this, context, null, entries));
        }
        return result;
    }

    /**
     * The result of the highlight function for each match in each passage, computed before the passages' document is
     * built, as the function may build nodes of its own.
     */
    private List<List<Sequence>> highlighted(final List<Found> passages, final FunctionReference highlight)
            throws XPathException {
        final List<List<Sequence>> results = new ArrayList<>(passages.size());
        for (final Found passage : passages) {
            final List<Sequence> matches = new ArrayList<>(passage.matchStarts().length);
            for (int m = 0; m < passage.matchStarts().length; m++) {
                final int matchStart = Math.max(passage.start(), passage.matchStarts()[m]);
                final int matchEnd = Math.min(passage.end(), passage.matchEnds()[m]);
                matches.add(matchStart >= matchEnd ? Sequence.EMPTY_SEQUENCE : highlight.evalFunction(null, null,
                        new Sequence[] {new StringValue(this, passage.content().substring(matchStart, matchEnd))}));
            }
            results.add(matches);
        }
        return results;
    }

    private Sequence toElements(final List<Found> passages, @Nullable final String mark,
            @Nullable final List<List<Sequence>> highlighted) throws XPathException {
        context.pushDocumentContext();
        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            builder.startDocument();
            final List<Integer> nodes = new ArrayList<>(passages.size());
            for (int p = 0; p < passages.size(); p++) {
                final Found passage = passages.get(p);
                final AttributesImpl attributes = new AttributesImpl();
                attributes.addAttribute("", "start", "start", "CDATA", Integer.toString(position(passage.content(), passage.start())));
                attributes.addAttribute("", "end", "end", "CDATA", Integer.toString(position(passage.content(), passage.end())));
                attributes.addAttribute("", "score", "score", "CDATA", Float.toString(passage.score()));
                attributes.addAttribute("", "trimmed-start", "trimmed-start", "CDATA", Boolean.toString(passage.trimmedStart()));
                attributes.addAttribute("", "trimmed-end", "trimmed-end", "CDATA", Boolean.toString(passage.trimmedEnd()));
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
                    final String matchText = passage.content().substring(matchStart, matchEnd);
                    if (highlighted != null) {
                        copy(highlighted.get(p).get(m), builder);
                    } else {
                        if (mark != null) {
                            builder.startElement("", mark, mark, null);
                        } else {
                            builder.startElement(Namespaces.EXIST_NS, "match", "exist:match", null);
                        }
                        builder.characters(matchText);
                        builder.endElement();
                    }
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

    /** The start of a value in which matches are looked for: at most maxAnalyzed characters, without splitting a pair. */
    @Nullable
    private static String analyzed(@Nullable final String value, final int maxAnalyzed) {
        if (value == null || value.length() <= maxAnalyzed) {
            return value;
        }
        final int end = Character.isLowSurrogate(value.charAt(maxAnalyzed)) ? maxAnalyzed - 1 : maxAnalyzed;
        return value.substring(0, end);
    }

    /** Copies a highlight function's result into the passage: nodes as they are, atomic values as text. */
    private void copy(final Sequence items, final MemTreeBuilder builder) throws XPathException {
        final DocumentBuilderReceiver receiver = new DocumentBuilderReceiver(this, builder);
        for (final SequenceIterator i = items.iterate(); i.hasNext(); ) {
            final Item item = i.nextItem();
            if (Type.subTypeOf(item.getType(), Type.NODE)) {
                try {
                    item.copyTo(context.getBroker(), receiver);
                } catch (final SAXException e) {
                    throw new XPathException(this, ErrorCodes.XPTY0004, "Cannot copy the highlight function's result: " + e.getMessage(), e);
                }
            } else {
                builder.characters(item.getStringValue());
            }
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

    /** Whether a string option is its default rather than its alternative; any other value is an error. */
    private boolean choice(final AbstractMapType options, final StringValue key, final String defaultValue,
            final String alternative) throws XPathException {
        final String value = stringOption(options, key, defaultValue);
        if (!value.equals(defaultValue) && !value.equals(alternative)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be '"
                    + defaultValue + "' or '" + alternative + "', got '" + value + "'");
        }
        return value.equals(defaultValue);
    }

    private double doubleOption(final AbstractMapType options, final StringValue key, final double defaultValue)
            throws XPathException {
        if (options == null || options.get(key).isEmpty()) {
            return defaultValue;
        }
        final Sequence value = options.get(key);
        if (!value.hasOne() || !Type.subTypeOf(value.itemAt(0).getType(), Type.NUMERIC)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be a number");
        }
        return ((NumericValue) value.itemAt(0)).getDouble();
    }

    @Nullable
    private FunctionReference functionOption(final AbstractMapType options, final StringValue key) throws XPathException {
        if (options == null || options.get(key).isEmpty()) {
            return null;
        }
        final Sequence value = options.get(key);
        if (!value.hasOne() || !Type.subTypeOf(value.itemAt(0).getType(), Type.FUNCTION)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be a function");
        }
        final FunctionReference function = (FunctionReference) value.itemAt(0);
        function.analyze(new AnalyzeContextInfo());
        return function;
    }

    private static String stringOption(final AbstractMapType options, final StringValue key, final String defaultValue) throws XPathException {
        if (options == null || options.get(key).isEmpty()) {
            return defaultValue;
        }
        return options.get(key).getStringValue();
    }
}
