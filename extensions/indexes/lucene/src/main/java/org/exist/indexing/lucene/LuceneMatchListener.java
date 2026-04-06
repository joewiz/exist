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
package org.exist.indexing.lucene;

import org.exist.dom.persistent.IStoredNode;
import org.exist.dom.QName;
import org.exist.dom.persistent.NodeHandle;
import org.exist.dom.persistent.Match;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NewArrayNodeSet;
import org.exist.dom.persistent.NodeSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.queries.spans.SpanQuery;
import org.exist.indexing.AbstractMatchListener;
import org.exist.numbering.NodeId;
import org.exist.stax.ExtendedXMLStreamReader;
import org.exist.stax.IEmbeddedXMLStreamReader;
import org.exist.storage.DBBroker;
import org.exist.storage.IndexSpec;
import org.exist.storage.NodePath;
import org.exist.storage.NodePath2;
import org.exist.util.serializer.AttrList;
import org.xml.sax.SAXException;

import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.*;

public class LuceneMatchListener extends AbstractMatchListener {

    private static final Logger LOG = LogManager.getLogger(LuceneMatchListener.class);

    private Match match;
    private Set<Query> queries;
    private Map<NodeId, Offset> nodesWithMatch;
    private final LuceneIndex index;
    private LuceneConfig config;
    private DBBroker broker;
    /** NodeId we already scanned in reset(); avoid double-scan in startElement. */
    private NodeId scannedInResetForNodeId;

    public LuceneMatchListener(final LuceneIndex index, final DBBroker broker, final NodeProxy proxy) {
        this.index = index;
        reset(broker, proxy);
    }

    public boolean hasMatches(final NodeProxy proxy) {
        Match nextMatch = proxy.getMatches();
        while (nextMatch != null) {
            if (nextMatch.getIndexId().equals(LuceneIndex.ID)) {
                return true;
            }
            nextMatch = nextMatch.getNextMatch();
        }
        return false;
    }

    protected void reset(final DBBroker broker, final NodeProxy proxy) {
        this.broker = broker;
        this.match = proxy.getMatches();
        setNextInChain(null);

        final IndexSpec indexConf = proxy.getOwnerDocument().getCollection().getIndexConfiguration(broker);
        if (indexConf != null) {
            config = (LuceneConfig) indexConf.getCustomIndexSpec(LuceneIndex.ID);
        } else {
            config = LuceneConfig.DEFAULT_CONFIG;
        }

        getQueries();
        nodesWithMatch = new TreeMap<>();
        /* Check if an index is defined on an ancestor of the current node.
        * If yes, scan the ancestor to get the offset of the first character
        * in the current node. For example, if the indexed node is &lt;a>abc&lt;b>de&lt;/b></a>
        * and we query for //a[text:ngram-contains(., 'de')]/b, proxy will be a &lt;b> node, but
        * the offsets of the matches are relative to the start of &lt;a>.
        */
        NodeSet ancestors = null;
        Match nextMatch = this.match;
        while (nextMatch != null) {
            if (proxy.getNodeId().isDescendantOf(nextMatch.getNodeId())) {
                if (ancestors == null) {
                    ancestors = new NewArrayNodeSet();
                }
                ancestors.add(new NodeProxy(proxy.getExpression(), proxy.getOwnerDocument(), nextMatch.getNodeId()));
            }
            nextMatch = nextMatch.getNextMatch();
        }

        scannedInResetForNodeId = null;
        if (ancestors != null && !ancestors.isEmpty()) {
            for (final NodeProxy p : ancestors) {
                scanMatches(p);
            }
        } else {
            /* #4835: When proxy is the matching node (no ancestors), scan it directly.
             * Otherwise nodesWithMatch stays empty until startElement, but when serializing
             * multiple nodes the listener may be reused with stale state from a previous node. */
            Match m = this.match;
            while (m != null) {
                if (m.getNodeId().equals(proxy.getNodeId())) {
                    scanMatches(proxy);
                    scannedInResetForNodeId = proxy.getNodeId();
                    break;
                }
                m = m.getNextMatch();
            }
        }
    }

    @Override
    public void startElement(final QName qname, final AttrList attribs) throws SAXException {
        Match nextMatch = match;
        final NodeHandle current = getCurrentNode();
        // check if there are any matches in the current element
        // if yes, push a NodeOffset object to the stack to track
        // the node contents
        while (nextMatch != null && current != null) {
            if (nextMatch.getNodeId().equals(current.getNodeId())) {
                if (scannedInResetForNodeId == null || !scannedInResetForNodeId.equals(current.getNodeId())) {
                    scanMatches(new NodeProxy(null, current));
                }
                break;
            }
            nextMatch = nextMatch.getNextMatch();
        }
        super.startElement(qname, attribs);
    }

    @Override
    public void characters(final CharSequence seq) throws SAXException {
        final NodeHandle current = getCurrentNode();
        if (current == null) {
            super.characters(seq);
            return;
        }
        final NodeId nodeId = current.getNodeId();
        Offset offset = nodesWithMatch.get(nodeId);
        if (offset == null) {
            super.characters(seq);
        } else {
            final String s = seq.toString();
            int pos = 0;
            while (offset != null) {
                int matchStart = offset.startOffset;
                int matchEnd = offset.endOffset;
                // Merge overlapping/adjacent spans
                while (offset.next != null && offset.next.startOffset <= matchEnd) {
                    offset = offset.next;
                    matchEnd = Math.max(matchEnd, offset.endOffset);
                }
                // Skip spans that start before our current position (already emitted)
                if (matchStart < pos) {
                    matchStart = pos;
                }
                if (matchStart >= matchEnd || matchStart >= s.length()) {
                    offset = offset.next;
                    continue;
                }
                if (matchStart > pos) {
                    super.characters(s.substring(pos, matchStart));
                }
                int end = Math.min(matchEnd, s.length());
                super.startElement(MATCH_ELEMENT, null);
                super.characters(s.substring(matchStart, end));
                super.endElement(MATCH_ELEMENT);
                pos = end;
                offset = offset.next;
            }
            if (pos < seq.length()) {
                super.characters(s.substring(pos));
            }
        }
    }

    private void scanMatches(final NodeProxy p) {
        // Collect the text content of all descendants of p.
        // Remember the start offsets of the text nodes for later use.
        final NodePath path = getPath(p);
        @Nullable final LuceneIndexConfig idxConf = config.getConfig(path).next();
        if(idxConf == null) {
            return;  // there is no index config so there can not be any matches
        }
        final TextExtractor extractor = new DefaultTextExtractor();
        extractor.configure(config, idxConf);

        final OffsetList offsets = new OffsetList();
        int level = 0;
        int textOffset = 0;
        try {
            final IEmbeddedXMLStreamReader reader = broker.getXMLStreamReader(p, false);
            scanLoop:
            while (reader.hasNext()) {
                final int ev = reader.next();
                switch (ev) {

                    case XMLStreamConstants.END_ELEMENT:
                        if (--level < 0) {
                            break scanLoop;
                        }
                        // call extractor.endElement unless this is the root of the current fragment
                        if (level > 0) {
                            textOffset += extractor.endElement(reader.getQName());
                        }
                        /* #4835: Stop when we've closed the root element we're scanning.
                         * The reader continues to siblings; without this we'd include the whole parent. */
                        if (level == 0) {
                            break scanLoop;
                        }
                        break;

                    case XMLStreamConstants.START_ELEMENT:
                        // call extractor.startElement unless this is the root of the current fragment
                        if (level > 0) {
                            textOffset += extractor.startElement(reader.getQName());
                        }
                        ++level;
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        final NodeId nodeId = (NodeId) reader.getProperty(ExtendedXMLStreamReader.PROPERTY_NODE_ID);
                        textOffset += extractor.beforeCharacters();
                        final int consumed = extractor.characters(reader.getXMLText());
                        if (consumed > 0) {
                            offsets.add(textOffset, nodeId);
                            textOffset += consumed;
                        }
                        break;
                }
            }
        } catch (final IOException | XMLStreamException e) {
            LOG.warn("Problem found while serializing XML: {}", e.getMessage(), e);
        }

        // Compute the Lucene field name for this index configuration
        // (same logic as LuceneIndexWorker uses when indexing)
        final String contentField = idxConf.isNamed()
                ? idxConf.getName()
                : LuceneUtil.encodeQName(idxConf.getQName(), index.getBrokerPool().getSymbols());

        // Retrieve the Analyzer for the NodeProxy that was used for
        // indexing and querying.
        Analyzer analyzer = idxConf.getAnalyzer();
        if (analyzer == null) {
            // Otherwise use system default Lucene analyzer (from conf.xml)
            analyzer = index.getDefaultAnalyzer();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Analyzer: {} for path: {}", analyzer, path);
        }

        final String str = extractor.getText().toString();
        if (str.isEmpty()) {
            return;
        }

        // Use Lucene's Matches API via MemoryIndex to detect match offsets.
        // This replaces the old manual token-matching approach and correctly
        // handles ALL query types: terms, phrases, spans, proximity, fuzzy,
        // wildcards, regex, and complex boolean combinations.
        final MemoryIndex memIndex = new MemoryIndex(true, true);
        memIndex.addField(contentField, str, analyzer);

        final IndexSearcher memSearcher = memIndex.createSearcher();
        final LeafReaderContext leafCtx = memSearcher.getTopReaderContext().leaves().get(0);

        for (final Query query : queries) {
            // Extract only clauses targeting the content field, stripping
            // filters (_idx) and non-content fields (e.g. pub-year ranges)
            final Query contentQuery = extractContentQuery(query, contentField);
            if (contentQuery == null) {
                continue;
            }
            try {
                final Weight weight = memSearcher.createWeight(
                        memSearcher.rewrite(contentQuery), ScoreMode.COMPLETE_NO_SCORES, 1.0f);
                final Matches matches = weight.matches(leafCtx, 0);
                if (matches != null) {
                    final MatchesIterator mi = matches.getMatches(contentField);
                    if (mi != null) {
                        while (mi.next()) {
                            final int startOffset = mi.startOffset();
                            final int endOffset = mi.endOffset();
                            if (startOffset >= 0 && endOffset > startOffset) {
                                addMatchSpan(startOffset, endOffset, offsets, str.length());
                            }
                        }
                    }
                }
            } catch (final IOException e) {
                LOG.warn("Problem found while highlighting matches: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Extract the portions of a query that target the given content field,
     * stripping any clauses that target other fields (e.g. _idx FILTER,
     * pub-year range queries). This allows the query to be used on a
     * MemoryIndex that only contains the content field.
     *
     * @param query the original query (possibly a BooleanQuery with mixed fields)
     * @param contentField the name of the content field in the MemoryIndex
     * @return a query containing only clauses for the content field, or null if none found
     */
    public static @Nullable Query extractContentQuery(final Query query, final String contentField) {
        // Unwrap BoostQuery, preserving boost on the filtered result
        if (query instanceof BoostQuery boost) {
            final Query inner = extractContentQuery(boost.getQuery(), contentField);
            return inner != null ? new BoostQuery(inner, boost.getBoost()) : null;
        }

        if (!(query instanceof BooleanQuery bq)) {
            // Leaf query: check what field it targets
            final String field = getQueryField(query);
            if (field == null) {
                // Unknown field (e.g. MatchAllDocsQuery) — keep it
                return query;
            }
            return field.equals(contentField) ? query : null;
        }

        // BooleanQuery: recursively filter each clause
        final List<BooleanClause> kept = new ArrayList<>();
        for (final BooleanClause clause : bq.clauses()) {
            if (clause.occur() == BooleanClause.Occur.FILTER) {
                // Always strip FILTER clauses (e.g. _idx filter)
                continue;
            }
            final Query sub = extractContentQuery(clause.query(), contentField);
            if (sub != null) {
                kept.add(new BooleanClause(sub, clause.occur()));
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        if (kept.size() == 1 && kept.get(0).occur() == BooleanClause.Occur.MUST) {
            return kept.get(0).query();
        }
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (final BooleanClause clause : kept) {
            builder.add(clause);
        }
        return builder.build();
    }

    /**
     * Get the field name that a query targets, or null if it can't be determined.
     */
    private static @Nullable String getQueryField(final Query query) {
        if (query instanceof BoostQuery boost) {
            return getQueryField(boost.getQuery());
        }
        if (query instanceof TermQuery tq) {
            return tq.getTerm().field();
        }
        if (query instanceof PhraseQuery pq) {
            final org.apache.lucene.index.Term[] terms = pq.getTerms();
            return terms.length > 0 ? terms[0].field() : null;
        }
        if (query instanceof MultiTermQuery mtq) {
            return mtq.getField();
        }
        if (query instanceof SpanQuery sq) {
            return sq.getField();
        }
        // Unknown query type — can't determine field
        return null;
    }

    public static NodePath getPath(final NodeProxy proxy) {
        final NodePath2 path = new NodePath2();
        final IStoredNode<?> node = (IStoredNode<?>) proxy.getNode();
        walkAncestor(node, path);
        return path;
    }

    private static void walkAncestor(final IStoredNode node, final NodePath2 path) {
        if (node == null) {
            return;
        }
        final IStoredNode parent = node.getParentStoredNode();
        walkAncestor(parent, path);
        path.addNode(node);
    }

    /**
     * Collect unique queries from all Lucene matches on this proxy.
     * Excludes queries that only target configured Lucene fields (e.g. pub-year)
     * so that util:expand does not produce superfluous highlights for field-only matches.
     */
    private void getQueries() {
        queries = new LinkedHashSet<>();
        Match nextMatch = this.match;
        while (nextMatch != null) {
            if (nextMatch.getIndexId().equals(LuceneIndex.ID)) {
                final Query query = ((LuceneMatch) nextMatch).getQuery();
                queries.add(query);
            }
            nextMatch = nextMatch.getNextMatch();
        }
    }

    static class OffsetList {

        int[] offsets = new int[16];
        NodeId[] ids = new NodeId[16];

        int len = 0;

        void add(final int offset, final NodeId nodeId) {
            if (len == offsets.length) {
                final int[] tempOffsets = new int[len * 2];
                System.arraycopy(offsets, 0, tempOffsets, 0, len);
                offsets = tempOffsets;

                final NodeId[] tempIds = new NodeId[len * 2];
                System.arraycopy(ids, 0, tempIds, 0, len);
                ids = tempIds;
            }
            offsets[len] = offset;
            ids[len++] = nodeId;
        }

        int getIndex(final int offset) {
            for (int i = 0; i < len; i++) {
                if (offsets[i] <= offset && (i + 1 == len || offsets[i + 1] > offset)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * End offset of segment idx in the concatenated string.
         * @param idx segment index (0-based)
         * @param textLength total length of concatenated text
         */
        int getSegmentEnd(final int idx, final int textLength) {
            return idx + 1 < len ? offsets[idx + 1] : textLength;
        }

    }

    /**
     * Add a match span [startOffset, endOffset) to all text nodes it intersects.
     * Fixes #4584: when a Lucene hit spans inline elements (e.g. "ro&lt;vuji&gt;s&lt;/vuji&gt;e"),
     * all portions must get exist:match, not just the first text node.
     *
     * @param startOffset inclusive start in concatenated string
     * @param endOffset exclusive end in concatenated string
     * @param offsets offset list mapping positions to text nodes
     * @param textLength total length of concatenated text
     */
    private void addMatchSpan(final int startOffset, final int endOffset,
            final OffsetList offsets, final int textLength) {
        if (startOffset < 0 || endOffset <= startOffset) {
            return;
        }
        final int idxStart = offsets.getIndex(startOffset);
        final int idxEnd = offsets.getIndex(endOffset - 1);
        if (idxStart < 0 || idxEnd < 0) {
            return;
        }
        for (int idx = idxStart; idx <= idxEnd; idx++) {
            final NodeId nodeId = offsets.ids[idx];
            final int nodeStart = offsets.offsets[idx];
            final int nodeEnd = offsets.getSegmentEnd(idx, textLength);
            final int relStart = (idx == idxStart) ? startOffset - nodeStart : 0;
            final int relEnd = (idx == idxEnd) ? endOffset - nodeStart : nodeEnd - nodeStart;
            final Offset existing = nodesWithMatch.get(nodeId);
            if (existing != null) {
                existing.add(relStart, relEnd);
            } else {
                nodesWithMatch.put(nodeId, new Offset(relStart, relEnd));
            }
        }
    }

    private static class Offset {
        private int startOffset;
        private int endOffset;
        private Offset next = null;

        Offset(final int startOffset, final int endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        void add(final int offset, final int endOffset) {
            if (startOffset == offset && this.endOffset == endOffset) {
                return;  // exact duplicate
            }
            // Insert in sorted order by startOffset to ensure characters() can
            // walk the list sequentially without backwards jumps
            final Offset newOffset = new Offset(offset, endOffset);
            if (offset < this.startOffset) {
                // New offset goes before head — swap contents since head is stored in the map
                final int tmpStart = this.startOffset;
                final int tmpEnd = this.endOffset;
                final Offset tmpNext = this.next;
                this.startOffset = offset;
                this.endOffset = endOffset;
                this.next = new Offset(tmpStart, tmpEnd);
                this.next.next = tmpNext;
                return;
            }
            Offset prev = this;
            while (prev.next != null && prev.next.startOffset <= offset) {
                if (prev.next.startOffset == offset && prev.next.endOffset == endOffset) {
                    return;  // exact duplicate
                }
                prev = prev.next;
            }
            newOffset.next = prev.next;
            prev.next = newOffset;
        }

        private Offset getLast() {
            Offset next = this;
            while (next.next != null) {
                next = next.next;
            }
            return next;
        }

    }
}
