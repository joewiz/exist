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

import javax.annotation.Nullable;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts the text of an indexed node into chunks that an embedding model reads in full.
 *
 * <p>The text comes as units, in reading order, e.g. the string values of the node's children: the units are only
 * hints where a chunk may end, every unit's text is chunked. Consecutive units are packed into one chunk as long as
 * their tokens fit, so that headings and short paragraphs join their neighbors; a unit with more tokens than fit is
 * split, preferably at the end of a sentence, and its pieces overlap by a few tokens.</p>
 *
 * <p>A chunk's offsets refer to the units' text joined without separators, which, with the default units, the
 * children of the node, is the node's string value.</p>
 */
public final class VectorChunker {

    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Tokenizes a text as the embedding model does.
     */
    @FunctionalInterface
    public interface Tokenizer {

        /**
         * @param text a text
         * @return the end offset of each token in {@code text}, or null if the text can't be tokenized
         */
        @Nullable int[] tokenEnds(String text);
    }

    /**
     * A chunk of the units' text.
     *
     * @param start the offset of its first character in the units' text
     * @param end the offset after its last character
     * @param text its text, with runs of whitespace replaced by a space
     */
    public record Chunk(int start, int end, String text) {
    }

    private final Tokenizer tokenizer;
    private final int maxTokens;
    private final int overlap;

    /**
     * @param tokenizer the model's tokenizer; where it can't tokenize, words are counted as tokens
     * @param maxTokens the most tokens of a chunk's text
     * @param overlap the tokens that consecutive pieces of a split unit share
     */
    public VectorChunker(final Tokenizer tokenizer, final int maxTokens, final int overlap) {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1: " + maxTokens);
        }
        this.tokenizer = tokenizer;
        this.maxTokens = maxTokens;
        this.overlap = Math.max(0, Math.min(overlap, maxTokens / 2));
    }

    /**
     * Cuts the units' text into chunks.
     *
     * @param units the texts of the units, in reading order
     * @return the chunks, in reading order; none if there is no text
     */
    public List<Chunk> chunk(final List<String> units) {
        final StringBuilder all = new StringBuilder();
        final List<int[]> spans = new ArrayList<>();
        int start = -1;
        int end = -1;
        int tokens = 0;
        for (final String unit : units) {
            final int base = all.length();
            all.append(unit);
            if (unit.isBlank()) {
                continue;
            }
            final int[] ends = tokenEnds(unit);
            if (ends.length == 0) {
                continue;
            }
            final int unitStart = base + leadingWhitespace(unit);
            final int unitEnd = base + unit.length() - trailingWhitespace(unit);
            if (ends.length > maxTokens) {
                if (start >= 0) {
                    spans.add(new int[] {start, end});
                    start = -1;
                }
                for (final int[] piece : split(unit, ends)) {
                    spans.add(new int[] {base + piece[0], base + piece[1]});
                }
                continue;
            }
            if (start >= 0 && tokens + ends.length > maxTokens) {
                spans.add(new int[] {start, end});
                start = -1;
            }
            if (start < 0) {
                start = unitStart;
                tokens = 0;
            }
            end = unitEnd;
            tokens += ends.length;
        }
        if (start >= 0) {
            spans.add(new int[] {start, end});
        }

        final List<Chunk> chunks = new ArrayList<>(spans.size());
        for (final int[] span : spans) {
            final String text = WHITESPACE.matcher(all.substring(span[0], span[1])).replaceAll(" ").strip();
            if (!text.isEmpty()) {
                chunks.add(new Chunk(span[0], span[1], text));
            }
        }
        return chunks;
    }

    private int[] tokenEnds(final String text) {
        final int[] ends = tokenizer.tokenEnds(text);
        if (ends != null) {
            return ends;
        }
        final List<Integer> words = new ArrayList<>();
        final Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.end());
        }
        return words.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Splits a unit with more tokens than fit into pieces, ending a piece at the end of a sentence where one is in its
     * second half.
     *
     * @return the start and end offset of each piece in the unit
     */
    private List<int[]> split(final String unit, final int[] ends) {
        final TreeSet<Integer> sentenceEnds = sentenceEnds(unit);
        final List<int[]> pieces = new ArrayList<>();
        int first = 0;
        while (first < ends.length) {
            final int limit = Math.min(ends.length, first + maxTokens);
            int last = limit;
            if (limit < ends.length) {
                final Integer sentenceEnd = sentenceEnds.floor(ends[limit - 1]);
                if (sentenceEnd != null) {
                    final int tokensToSentenceEnd = tokensEndingBy(ends, sentenceEnd);
                    if (tokensToSentenceEnd > first + maxTokens / 2) {
                        last = tokensToSentenceEnd;
                    }
                }
            }
            final int pieceStart = first == 0 ? leadingWhitespace(unit) : skipWhitespace(unit, ends[first - 1]);
            pieces.add(new int[] {pieceStart, ends[last - 1]});
            if (last >= ends.length) {
                break;
            }
            first = Math.max(first + 1, last - overlap);
        }
        return pieces;
    }

    private static TreeSet<Integer> sentenceEnds(final String text) {
        final TreeSet<Integer> sentenceEnds = new TreeSet<>();
        final BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(text);
        for (int boundary = sentences.next(); boundary != BreakIterator.DONE; boundary = sentences.next()) {
            // a boundary follows the whitespace after the sentence
            int sentenceEnd = boundary;
            while (sentenceEnd > 0 && Character.isWhitespace(text.charAt(sentenceEnd - 1))) {
                sentenceEnd--;
            }
            sentenceEnds.add(sentenceEnd);
        }
        return sentenceEnds;
    }

    /** The number of tokens that end at or before the offset. */
    private static int tokensEndingBy(final int[] ends, final int offset) {
        final int i = Arrays.binarySearch(ends, offset);
        return i >= 0 ? i + 1 : -i - 1;
    }

    private static int skipWhitespace(final String text, final int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int leadingWhitespace(final String text) {
        return skipWhitespace(text, 0);
    }

    private static int trailingWhitespace(final String text) {
        int i = text.length();
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return text.length() - i;
    }
}
