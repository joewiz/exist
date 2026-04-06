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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;
import org.exist.Namespaces;
import org.exist.dom.memtree.InMemoryNodeSet;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.Match;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.*;
import org.exist.storage.NodePath;
import org.exist.xquery.*;
import org.exist.xquery.value.*;
import org.xml.sax.helpers.AttributesImpl;

import javax.annotation.Nullable;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.*;

import static org.exist.xquery.FunctionDSL.*;
import static org.exist.xquery.modules.lucene.LuceneModule.functionSignature;
import static org.exist.xquery.modules.lucene.LuceneModule.functionSignatures;

/**
 * Implements {@code ft:get-passages()} — extracts the best-scoring text passages
 * from Lucene full-text search hits with match highlighting.
 *
 * <p>Unlike {@code kwic:summarize()} which returns context around the <em>first</em>
 * match using character counting, this function scores all passages in the matched
 * node's text and returns the top N by relevance.</p>
 *
 * <p>Passages are broken by sentence boundaries (default) or at a configurable
 * character width. Each passage is returned as an {@code <exist:passage>} element
 * with match terms wrapped in {@code <exist:match>} elements.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * let $hits := //p[ft:query(., "quick brown fox")]
 * for $hit in $hits
 * return ft:get-passages($hit, 3, &lt;options width="150"/&gt;)
 * </pre>
 */
public class GetPassages extends BasicFunction {

    private static final FunctionParameterSequenceType FS_PARAM_HITS =
            optManyParam("hits", Type.NODE, "node(s) obtained from a Lucene full-text query (must carry LuceneMatch)");
    private static final FunctionParameterSequenceType FS_PARAM_MAX_PASSAGES =
            optParam("max-passages", Type.INTEGER, "maximum number of passages to return per hit (default: 3)");
    private static final FunctionParameterSequenceType FS_PARAM_OPTIONS =
            optParam("options", Type.ELEMENT, "configuration element: " +
                    "<options width='150' break='sentence'/> " +
                    "where width is the target passage width in characters (default: 150) " +
                    "and break is 'sentence' (default) or 'character'");

    static final FunctionSignature[] signatures = functionSignatures(
            "get-passages",
            "Extract the best-scoring text passages from Lucene full-text query hits. " +
            "Returns exist:passage elements with match terms wrapped in exist:match, " +
            "ranked by relevance (number of distinct matches and their frequency). " +
            "This is useful for displaying search result snippets that show the most " +
            "relevant context, not just the first match.",
            returnsOptMany(Type.ELEMENT, "exist:passage elements with score attribute, " +
                    "containing text and exist:match children"),
            arities(
                    arity(FS_PARAM_HITS),
                    arity(FS_PARAM_HITS, FS_PARAM_MAX_PASSAGES),
                    arity(FS_PARAM_HITS, FS_PARAM_MAX_PASSAGES, FS_PARAM_OPTIONS)
            )
    );

    private static final int DEFAULT_MAX_PASSAGES = 3;
    private static final int DEFAULT_WIDTH = 150;

    public GetPassages(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        if (args[0].isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }

        final int maxPassages = getArgumentCount() >= 2 && !args[1].isEmpty()
                ? ((IntegerValue) args[1].itemAt(0)).getInt()
                : DEFAULT_MAX_PASSAGES;

        // Inline options (override config defaults)
        int inlineWidth = -1;
        String inlineBreak = null;
        if (getArgumentCount() >= 3 && !args[2].isEmpty()) {
            final org.exist.dom.memtree.ElementImpl options =
                    (org.exist.dom.memtree.ElementImpl) args[2].itemAt(0);
            final String widthStr = options.getAttribute("width");
            if (widthStr != null && !widthStr.isEmpty()) {
                inlineWidth = Integer.parseInt(widthStr);
            }
            final String breakStr = options.getAttribute("break");
            if (breakStr != null && !breakStr.isEmpty()) {
                inlineBreak = breakStr;
            }
        }

        context.pushDocumentContext();
        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            builder.startDocument();
            final InMemoryNodeSet result = new InMemoryNodeSet(maxPassages);

            for (final SequenceIterator si = args[0].iterate(); si.hasNext(); ) {
                final Item item = si.nextItem();
                if (!(item instanceof NodeProxy proxy)) {
                    continue;
                }

                final LuceneMatch match = getLuceneMatch(proxy);
                if (match == null) {
                    continue;
                }

                extractPassages(proxy, match, maxPassages, inlineWidth, inlineBreak, builder, result);
            }

            return result;
        } catch (final IOException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, e.getMessage(), e);
        } finally {
            context.popDocumentContext();
        }
    }

    private void extractPassages(final NodeProxy proxy, final LuceneMatch match,
                                 final int maxPassages, final int inlineWidth,
                                 final String inlineBreak,
                                 final MemTreeBuilder builder, final InMemoryNodeSet result)
            throws XPathException, IOException {

        final LuceneIndexWorker indexWorker = (LuceneIndexWorker) context.getBroker()
                .getIndexController().getWorkerByIndexId(LuceneIndex.ID);
        final NodePath path = LuceneMatchListener.getPath(proxy);
        final LuceneConfig config = indexWorker.getLuceneConfig(context.getBroker(), proxy.getDocumentSet());
        final LuceneIndexConfig idxConf = config.getConfig(path).next();
        if (idxConf == null) {
            return;
        }

        // Resolve passage parameters: inline options > config defaults > hardcoded defaults
        final int width;
        if (inlineWidth > 0) {
            width = inlineWidth;
        } else if (idxConf.getPassageWidth() > 0) {
            width = idxConf.getPassageWidth();
        } else {
            width = DEFAULT_WIDTH;
        }

        final String breakType;
        if (inlineBreak != null) {
            breakType = inlineBreak;
        } else if (idxConf.getPassageBreak() != null) {
            breakType = idxConf.getPassageBreak();
        } else {
            breakType = "sentence";
        }

        // Determine field name and analyzer (same as LuceneMatchListener)
        final String contentField = idxConf.isNamed()
                ? idxConf.getName()
                : LuceneUtil.encodeQName(idxConf.getQName(),
                        context.getBroker().getBrokerPool().getSymbols());

        final Analyzer analyzer = config.getAnalyzer(path);

        // Get text content of the node
        final String text = proxy.getNode().getTextContent();
        if (text == null || text.isEmpty()) {
            return;
        }

        // Get match offsets using MemoryIndex + Matches API
        final org.apache.lucene.search.Query luceneQuery = match.getQuery();
        final org.apache.lucene.search.Query contentQuery =
                LuceneMatchListener.extractContentQuery(luceneQuery, contentField);
        if (contentQuery == null) {
            return;
        }

        final MemoryIndex memIndex = new MemoryIndex(true, true);
        memIndex.addField(contentField, text, analyzer);
        final IndexSearcher memSearcher = memIndex.createSearcher();
        final LeafReaderContext leafCtx = memSearcher.getTopReaderContext().leaves().get(0);

        final Weight weight = memSearcher.createWeight(
                memSearcher.rewrite(contentQuery), ScoreMode.COMPLETE_NO_SCORES, 1.0f);
        final Matches luceneMatches = weight.matches(leafCtx, 0);
        if (luceneMatches == null) {
            return;
        }

        final MatchesIterator mi = luceneMatches.getMatches(contentField);
        if (mi == null) {
            return;
        }

        // Collect all match offsets
        final List<int[]> matchOffsets = new ArrayList<>();
        while (mi.next()) {
            final int start = mi.startOffset();
            final int end = mi.endOffset();
            if (start >= 0 && end > start) {
                matchOffsets.add(new int[]{start, end});
            }
        }
        if (matchOffsets.isEmpty()) {
            return;
        }
        matchOffsets.sort(Comparator.comparingInt(a -> a[0]));

        // Break text into passages
        final List<int[]> passages = breakIntoPassages(text, width, breakType);

        // Score each passage by number and density of matches
        final List<ScoredPassage> scoredPassages = new ArrayList<>();
        for (final int[] passage : passages) {
            final List<int[]> passageMatches = new ArrayList<>();
            for (final int[] mo : matchOffsets) {
                // Match overlaps with passage?
                if (mo[1] > passage[0] && mo[0] < passage[1]) {
                    passageMatches.add(new int[]{
                            Math.max(mo[0], passage[0]) - passage[0],
                            Math.min(mo[1], passage[1]) - passage[0]
                    });
                }
            }
            if (!passageMatches.isEmpty()) {
                // Score: number of matches * (total matched chars / passage length)
                int matchedChars = 0;
                for (final int[] pm : passageMatches) {
                    matchedChars += pm[1] - pm[0];
                }
                final double score = passageMatches.size() *
                        (1.0 + (double) matchedChars / (passage[1] - passage[0]));
                scoredPassages.add(new ScoredPassage(passage[0], passage[1], score, passageMatches));
            }
        }

        // Sort by score descending, take top N
        scoredPassages.sort(Comparator.comparingDouble(ScoredPassage::score).reversed());
        final int limit = Math.min(maxPassages, scoredPassages.size());

        for (int i = 0; i < limit; i++) {
            final ScoredPassage sp = scoredPassages.get(i);
            final String passageText = text.substring(sp.start, sp.end);

            final AttributesImpl attrs = new AttributesImpl();
            attrs.addAttribute("", "score", "score", "CDATA", String.format("%.2f", sp.score));
            final int nodeNr = builder.startElement(Namespaces.EXIST_NS, "passage", "exist:passage", attrs);

            // Emit passage text with exist:match wrappers, merging overlaps
            int pos = 0;
            int mi2 = 0;
            while (mi2 < sp.matches.size()) {
                int mStart = sp.matches.get(mi2)[0];
                int mEnd = sp.matches.get(mi2)[1];
                // Merge overlapping spans
                while (mi2 + 1 < sp.matches.size() && sp.matches.get(mi2 + 1)[0] <= mEnd) {
                    mi2++;
                    mEnd = Math.max(mEnd, sp.matches.get(mi2)[1]);
                }
                if (mStart < pos) {
                    mStart = pos;
                }
                if (mStart >= mEnd) {
                    mi2++;
                    continue;
                }
                if (mStart > pos) {
                    builder.characters(passageText.substring(pos, mStart));
                }
                final int end = Math.min(mEnd, passageText.length());
                builder.startElement(Namespaces.EXIST_NS, "match", "exist:match", null);
                builder.characters(passageText.substring(mStart, end));
                builder.endElement();
                pos = end;
                mi2++;
            }
            if (pos < passageText.length()) {
                builder.characters(passageText.substring(pos));
            }

            builder.endElement();
            result.add(builder.getDocument().getNode(nodeNr));
        }
    }

    private List<int[]> breakIntoPassages(final String text, final int width, final String breakType) {
        final List<int[]> passages = new ArrayList<>();
        if ("character".equals(breakType)) {
            // Fixed-width passages
            for (int i = 0; i < text.length(); i += width) {
                passages.add(new int[]{i, Math.min(i + width, text.length())});
            }
        } else {
            // Sentence-based passages (default)
            final BreakIterator bi = BreakIterator.getSentenceInstance(Locale.ROOT);
            bi.setText(text);
            int start = bi.first();
            int sentStart = start;
            int sentChars = 0;
            for (int end = bi.next(); end != BreakIterator.DONE; end = bi.next()) {
                sentChars += end - start;
                if (sentChars >= width) {
                    passages.add(new int[]{sentStart, end});
                    sentStart = end;
                    sentChars = 0;
                }
                start = end;
            }
            // Add remaining text as final passage
            if (sentStart < text.length()) {
                passages.add(new int[]{sentStart, text.length()});
            }
        }
        return passages;
    }

    @Nullable
    private static LuceneMatch getLuceneMatch(final NodeProxy proxy) {
        Match m = proxy.getMatches();
        while (m != null) {
            if (m.getIndexId().equals(LuceneIndex.ID)) {
                return (LuceneMatch) m;
            }
            m = m.getNextMatch();
        }
        return null;
    }

    private record ScoredPassage(int start, int end, double score, List<int[]> matches) {}
}
