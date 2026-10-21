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
package org.exist.util;

import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.FilteredBreakIteratorBuilder;
import com.ibm.icu.util.ULocale;

import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits text into sentences, with ICU's sentence rules for a language, abbreviations that don't end a sentence, and
 * two rules ICU doesn't have: a single capital letter and a period followed by a capital is an initial ("J. F.
 * Dulles"), and a question or exclamation mark followed by a lowercase word continues the sentence ("'Is this
 * final?' he asked.").
 *
 * <p>The abbreviations are those of CLDR for the language, unless turned off, and the caller's own, which may contain
 * several periods ("U.S."), unlike those ICU's filter applies. A sentence is
 * returned as it appears in the text, without the whitespace around it, so its offsets are exact.</p>
 *
 * <p>An instance is immutable and can be used by several threads; the ICU iterators it creates are not shared.</p>
 */
public final class SentenceBreaker {

    /** The characters that ICU's sentence rules treat as ending a paragraph, each one char long. */
    private static final Pattern NEWLINE = Pattern.compile("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]");

    private final Options options;
    private final ULocale locale;

    /**
     * How text is split.
     *
     * @param language a BCP 47 language tag, which selects ICU's rules and CLDR's abbreviations
     * @param abbreviations strings that never end a sentence, e.g. "Amb." or "No."
     * @param cldrAbbreviations whether CLDR's abbreviations for the language never end a sentence either
     * @param mergeInitials whether a single capital letter and a period followed by a capital doesn't end a sentence
     * @param mergeQuotedContinuation whether a question or exclamation mark, closing quotes or brackets and then a
     *     lowercase word don't end a sentence
     * @param breakAtNewlines whether a line break ends a sentence, as in ICU's rules; otherwise it is whitespace, as
     *     in the text of XML documents
     */
    public record Options(String language, List<String> abbreviations, boolean cldrAbbreviations,
            boolean mergeInitials, boolean mergeQuotedContinuation, boolean breakAtNewlines) {

        /** English, CLDR's abbreviations, both merge rules, and line breaks as whitespace. */
        public static final Options DEFAULT = new Options("en", List.of(), true, true, true, false);

        public Options {
            abbreviations = List.copyOf(abbreviations);
        }
    }

    /**
     * A sentence: its offsets in the text, in UTF-16 code units, end exclusive.
     *
     * @param start the offset of its first character
     * @param end the offset after its last character
     */
    public record Span(int start, int end) {
    }

    public SentenceBreaker(final Options options) {
        this.options = options;
        this.locale = ULocale.forLanguageTag(options.language().isEmpty() ? "en" : options.language());
    }

    /**
     * Splits text into sentences.
     *
     * @param text the text
     * @return the sentences in order, none if the text is only whitespace
     */
    public List<Span> sentences(final CharSequence text) {
        final List<Span> sentences = new ArrayList<>();
        final int[] ends = ends(text.toString());
        int start = 0;
        for (final int end : ends) {
            final Span span = trim(text, start, end);
            if (span != null) {
                sentences.add(span);
            }
            start = end;
        }
        return sentences;
    }

    /**
     * The offsets at which the sentences end, the last being the text's length, after the merge rules.
     */
    int[] ends(final String text) {
        final BreakIterator iterator = iterator();
        // ICU ends a sentence at a line break: unless that is wanted, ICU sees a space there, which keeps the offsets
        iterator.setText(options.breakAtNewlines() ? text : NEWLINE.matcher(text).replaceAll(" "));
        final List<Integer> ends = new ArrayList<>();
        for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
            if (end < text.length() && continues(text, end)) {
                continue;
            }
            ends.add(end);
        }
        if (ends.isEmpty() || ends.getLast() != text.length()) {
            ends.add(text.length());
        }
        return ends.stream().mapToInt(Integer::intValue).toArray();
    }

    private BreakIterator iterator() {
        final FilteredBreakIteratorBuilder builder = options.cldrAbbreviations()
                ? FilteredBreakIteratorBuilder.getInstance(locale)
                : FilteredBreakIteratorBuilder.getEmptyInstance();
        for (final String abbreviation : options.abbreviations()) {
            builder.suppressBreakAfter(abbreviation);
        }
        return builder.wrapIteratorWithFilter(BreakIterator.getSentenceInstance(locale));
    }

    /** Whether the sentence that ICU ends at {@code end} continues, by one of the merge rules. */
    private boolean continues(final String text, final int end) {
        final int last = lastNonSpace(text, end);
        final int next = firstNonSpace(text, end);
        if (last < 0 || next >= text.length()) {
            return false;
        }
        return endsWithAbbreviation(text, last)
                || options.mergeInitials() && isInitial(text, last) && Character.isUpperCase(text.codePointAt(next))
                || options.mergeQuotedContinuation() && endsQuestionOrExclamation(text, last)
                        && Character.isLowerCase(text.codePointAt(next));
    }

    /**
     * Whether the text ends at {@code last} with one of the caller's abbreviations, as a word of its own. ICU's filter
     * doesn't apply an abbreviation with more than one period, such as "U.S.", so they are checked here.
     */
    private boolean endsWithAbbreviation(final String text, final int last) {
        for (final String abbreviation : options.abbreviations()) {
            final int start = last + 1 - abbreviation.length();
            if (start >= 0 && text.startsWith(abbreviation, start)
                    && (start == 0 || Character.isWhitespace(text.charAt(start - 1)) || isOpening(text.charAt(start - 1)))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the text ends at {@code last} with a single capital letter and a period, as an initial does. */
    private static boolean isInitial(final String text, final int last) {
        if (text.charAt(last) != '.' || last < 1 || !Character.isUpperCase(text.charAt(last - 1))) {
            return false;
        }
        final int before = last - 2;
        return before < 0 || Character.isWhitespace(text.charAt(before)) || isOpening(text.charAt(before));
    }

    /** Whether the text ends at {@code last} with "?" or "!", possibly followed by closing quotes or brackets. */
    private static boolean endsQuestionOrExclamation(final String text, final int last) {
        int i = last;
        while (i >= 0 && isClosing(text.charAt(i))) {
            i--;
        }
        return i >= 0 && (text.charAt(i) == '?' || text.charAt(i) == '!');
    }

    private static boolean isOpening(final char c) {
        return c == '(' || c == '[' || c == '"' || c == '“' || c == '‘' || c == '\'';
    }

    private static boolean isClosing(final char c) {
        return c == ')' || c == ']' || c == '"' || c == '”' || c == '’' || c == '\'';
    }

    private static int lastNonSpace(final CharSequence text, final int end) {
        int i = end - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int firstNonSpace(final CharSequence text, final int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static Span trim(final CharSequence text, final int start, final int end) {
        final int first = firstNonSpace(text, start);
        final int last = lastNonSpace(text, end);
        return first <= last && first < end ? new Span(first, last + 1) : null;
    }

    /**
     * A {@link java.text.BreakIterator} over the sentence boundaries of this breaker, for APIs that take the JDK's,
     * such as Lucene's highlighters. Its boundaries are the ends of the sentences, after the merge rules.
     *
     * @return a new iterator
     */
    public java.text.BreakIterator toBreakIterator() {
        return new BoundaryIterator(this);
    }

    /**
     * A {@link java.text.BreakIterator} over boundaries computed for the whole text when it is set.
     */
    private static final class BoundaryIterator extends java.text.BreakIterator {
        private final SentenceBreaker breaker;
        private CharacterIterator text = new java.text.StringCharacterIterator("");
        private int[] boundaries = {0};
        private int current = 0;

        private BoundaryIterator(final SentenceBreaker breaker) {
            this.breaker = breaker;
        }

        @Override
        public void setText(final CharacterIterator newText) {
            this.text = newText;
            final StringBuilder sb = new StringBuilder(newText.getEndIndex() - newText.getBeginIndex());
            for (char c = newText.first(); c != CharacterIterator.DONE; c = newText.next()) {
                sb.append(c);
            }
            final int base = newText.getBeginIndex();
            final int[] ends = breaker.ends(sb.toString());
            boundaries = new int[ends.length + 1];
            boundaries[0] = base;
            for (int i = 0; i < ends.length; i++) {
                boundaries[i + 1] = base + ends[i];
            }
            current = 0;
        }

        @Override
        public CharacterIterator getText() {
            return text;
        }

        @Override
        public int first() {
            current = 0;
            return boundaries[current];
        }

        @Override
        public int last() {
            current = boundaries.length - 1;
            return boundaries[current];
        }

        @Override
        public int next(final int n) {
            final int target = current + n;
            if (target < 0 || target >= boundaries.length) {
                current = target < 0 ? 0 : boundaries.length - 1;
                return DONE;
            }
            current = target;
            return boundaries[current];
        }

        @Override
        public int next() {
            return next(1);
        }

        @Override
        public int previous() {
            return next(-1);
        }

        @Override
        public int following(final int offset) {
            for (int i = 0; i < boundaries.length; i++) {
                if (boundaries[i] > offset) {
                    current = i;
                    return boundaries[i];
                }
            }
            current = boundaries.length - 1;
            return DONE;
        }

        @Override
        public int preceding(final int offset) {
            for (int i = boundaries.length - 1; i >= 0; i--) {
                if (boundaries[i] < offset) {
                    current = i;
                    return boundaries[i];
                }
            }
            current = 0;
            return DONE;
        }

        @Override
        public boolean isBoundary(final int offset) {
            for (int i = 0; i < boundaries.length; i++) {
                if (boundaries[i] == offset) {
                    current = i;
                    return true;
                }
            }
            return false;
        }

        @Override
        public int current() {
            return boundaries[current];
        }

        @Override
        public Object clone() {
            final BoundaryIterator copy = new BoundaryIterator(breaker);
            copy.text = (CharacterIterator) text.clone();
            copy.boundaries = boundaries.clone();
            copy.current = current;
            return copy;
        }
    }
}
