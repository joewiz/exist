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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without a model's tokenizer, the chunker counts words as tokens.
 */
class VectorChunkerTest {

    private static final VectorChunker.Tokenizer WORDS = text -> null;

    @Test
    void shortUnitsArePackedTogether() {
        final List<String> units = List.of("Memorandum", "\n  ", "One two three.", " ", "Four five six.", " ", "Seven eight.");
        final List<VectorChunker.Chunk> chunks = new VectorChunker(WORDS, 6, 0).chunk(units);
        assertEquals(2, chunks.size());
        assertEquals("Memorandum One two three.", chunks.get(0).text());
        assertEquals("Four five six. Seven eight.", chunks.get(1).text());
    }

    @Test
    void offsetsReferToTheJoinedUnits() {
        final List<String> units = List.of("Alpha beta.", " ", "Gamma delta.");
        final String joined = String.join("", units);
        for (final VectorChunker.Chunk chunk : new VectorChunker(WORDS, 2, 0).chunk(units)) {
            assertEquals(chunk.text(), joined.substring(chunk.start(), chunk.end()));
        }
    }

    @Test
    void longUnitIsSplitAtSentenceEnds() {
        final String paragraph = "One two three four. Five six seven eight. Nine ten eleven twelve.";
        final List<VectorChunker.Chunk> chunks = new VectorChunker(WORDS, 6, 0).chunk(List.of(paragraph));
        assertEquals(List.of("One two three four.", "Five six seven eight.", "Nine ten eleven twelve."),
                chunks.stream().map(VectorChunker.Chunk::text).toList());
    }

    @Test
    void piecesOfALongUnitOverlap() {
        final String run = "a b c d e f g h i j";
        final List<VectorChunker.Chunk> chunks = new VectorChunker(WORDS, 4, 1).chunk(List.of(run));
        assertEquals(List.of("a b c d", "d e f g", "g h i j"), chunks.stream().map(VectorChunker.Chunk::text).toList());
    }

    @Test
    void noTextIsLost() {
        final List<String> units = List.of("Head", " ", "x ".repeat(23), "tail one", "\n", "", "last words here");
        final String joined = String.join("", units);
        final List<VectorChunker.Chunk> chunks = new VectorChunker(WORDS, 5, 0).chunk(units);
        final boolean[] covered = new boolean[joined.length()];
        for (final VectorChunker.Chunk chunk : chunks) {
            assertTrue(chunk.text().split(" ").length <= 5, chunk.text());
            for (int i = chunk.start(); i < chunk.end(); i++) {
                covered[i] = true;
            }
        }
        for (int i = 0; i < joined.length(); i++) {
            assertTrue(covered[i] || Character.isWhitespace(joined.charAt(i)), "not in any chunk: offset " + i);
        }
    }

    @Test
    void noChunksWithoutText() {
        assertEquals(List.of(), new VectorChunker(WORDS, 5, 0).chunk(List.of(" ", "\n")));
    }
}
