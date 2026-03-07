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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;
import org.exist.Namespaces;
import org.exist.dom.memtree.MemTreeBuilder;

public class PlainTextHighlighter {

    private final Query query;

    public PlainTextHighlighter(final Query query) {
        this.query = query;
    }

    public void highlight(final String content, final List<Offset> offsets, final MemTreeBuilder builder) {
        if (offsets == null || offsets.isEmpty()) {
            builder.characters(content);
        } else {
            int lastOffset = 0;
            int i = 0;
            while (i < offsets.size()) {
                int matchStart = offsets.get(i).startOffset();
                int matchEnd = offsets.get(i).endOffset();
                // Merge overlapping/adjacent spans
                while (i + 1 < offsets.size() && offsets.get(i + 1).startOffset() <= matchEnd) {
                    i++;
                    matchEnd = Math.max(matchEnd, offsets.get(i).endOffset());
                }
                if (matchStart < lastOffset) {
                    matchStart = lastOffset;
                }
                if (matchStart >= matchEnd || matchStart >= content.length()) {
                    i++;
                    continue;
                }
                if (matchStart > lastOffset) {
                    builder.characters(content.substring(lastOffset, matchStart));
                }
                final int end = Math.min(matchEnd, content.length());
                builder.startElement(Namespaces.EXIST_NS, "match", "exist:match", null);
                builder.characters(content.substring(matchStart, end));
                builder.endElement();
                lastOffset = end;
                i++;
            }
            if (lastOffset < content.length()) {
                builder.characters(content.substring(lastOffset));
            }
        }
    }

    public List<Offset> getOffsets(final String content, final Analyzer analyzer, final String fieldName) throws IOException {
        if (content == null || content.isEmpty()) {
            return null;
        }

        final MemoryIndex memIndex = new MemoryIndex(true, true);
        memIndex.addField(fieldName, content, analyzer);
        final IndexSearcher memSearcher = memIndex.createSearcher();
        final LeafReaderContext leafCtx = memSearcher.getTopReaderContext().leaves().get(0);

        final Query contentQuery = LuceneMatchListener.extractContentQuery(query, fieldName);
        if (contentQuery == null) {
            return null;
        }

        final Weight weight = memSearcher.createWeight(
                memSearcher.rewrite(contentQuery), ScoreMode.COMPLETE_NO_SCORES, 1.0f);
        final Matches matches = weight.matches(leafCtx, 0);
        if (matches == null) {
            return null;
        }

        final MatchesIterator mi = matches.getMatches(fieldName);
        if (mi == null) {
            return null;
        }

        final List<Offset> offsets = new ArrayList<>();
        while (mi.next()) {
            final int start = mi.startOffset();
            final int end = mi.endOffset();
            if (start >= 0 && end > start) {
                offsets.add(new Offset(start, end));
            }
        }
        if (offsets.isEmpty()) {
            return null;
        }
        offsets.sort(Comparator.comparingInt(Offset::startOffset));
        return offsets;
    }

    public static class Offset {
        protected int startOffset, endOffset;

        Offset(final int start, final int end) {
            this.startOffset = start;
            this.endOffset = end;
        }

        public int startOffset() { return startOffset; }
        public int endOffset() { return endOffset; }
    }
}
