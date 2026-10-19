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

import org.apache.lucene.facet.Facets;
import org.apache.lucene.search.Query;
import org.exist.dom.persistent.Match;
import org.exist.numbering.NodeId;

import javax.annotation.Nullable;

/**
 * Match class containing the score of a match and a reference to
 * the query that generated it.
 */
public class LuceneMatch extends Match {

    private final int luceneDocId;
    private float score = 0.0f;
    private final Query query;

    private final LuceneIndexWorker.LuceneFacets facets;

    /** The chunk of a chunked vector field that matched, or null if the match is not of a chunk. */
    @Nullable private Chunk chunk = null;

    /**
     * The chunk of a node's text that a query of a chunked vector field matched.
     *
     * @param field the vector field
     * @param number the chunk's position among the node's chunks, from 0
     * @param start the offset of its first character in the node's chunked text
     * @param end the offset after its last character
     */
    public record Chunk(String field, int number, int start, int end) {
    }

    LuceneMatch(final int contextId, final int luceneDocId, final NodeId nodeId, final Query query, final LuceneIndexWorker.LuceneFacets facets) {
        super(contextId, nodeId, null);
        this.luceneDocId = luceneDocId;
        this.query = query;
        this.facets = facets;
    }

    private LuceneMatch(final LuceneMatch copy) {
        super(copy);
        this.score = copy.score;
        this.luceneDocId = copy.luceneDocId;
        this.query = copy.query;
        this.facets = copy.facets;
        this.chunk = copy.chunk;
    }

    @Override
    public Match createInstance(final int contextId, final NodeId nodeId, @Nullable final String matchTerm) {
        throw new UnsupportedOperationException("Not supported from Lucene Full-Text Index");
    }

    public int getLuceneDocId() {
        return luceneDocId;
    }

    @Override
    public Match newCopy() {
        return new LuceneMatch(this);
    }

    @Override
    public String getIndexId() {
        return LuceneIndex.ID;
    }

    public Query getQuery() {
        return query;
    }

    public float getScore() {
        return score;
    }

    protected void setScore(final float score) {
        this.score = score;
    }

    /**
     * @return the chunk that matched, or null if the match is not of a chunk of a chunked vector field
     */
    @Nullable
    public Chunk getChunk() {
        return chunk;
    }

    void setChunk(@Nullable final Chunk chunk) {
        this.chunk = chunk;
    }

    public Facets getFacets() {
        return this.facets.getFacets();
    }

    // DW: missing hashCode() ?
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj instanceof LuceneMatch other) {
            return luceneDocId == other.luceneDocId
                    && query.equals(other.query)
                    && super.equals(other);
        } else {
            return false;
        }
    }
}
