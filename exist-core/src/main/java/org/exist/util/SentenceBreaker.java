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
import com.ibm.icu.text.RuleBasedBreakIterator;
import com.ibm.icu.util.ULocale;

import javax.annotation.Nullable;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Splits text into sentences, with ICU's sentence rules for a language and CLDR's abbreviations for it, which are
 * the defaults, and rules a caller may turn on for its texts.
 *
 * <p>The defaults are ICU's and CLDR's, and stay so: each rule that ICU doesn't have is an option, off unless the
 * caller turns it on, whose meaning doesn't change once it is defined. A caller with other needs gives its own options,
 * or its own ICU rules, so that no one's texts depend on rules tuned for someone else's.</p>
 *
 * <p>The rules a caller may turn on:</p>
 * <ul>
 *     <li>its own abbreviations, which may contain several periods ("U.S."), unlike those ICU's filter applies;</li>
 *     <li>initials: a capital letter and a period followed by a capital is an initial ("J. F. Dulles"), and with
 *     {@code maxInitials} so are runs of such letters ("R.N. Ginsburgh");</li>
 *     <li>a quoted continuation: a question or exclamation mark followed by a lowercase word continues the sentence
 *     ("'Is this final?' he asked.");</li>
 *     <li>a dash as a space: an em or en dash directly before a letter separates words, as in "Negotiations—Mr. Bell",
 *     so that abbreviations and initials after it are recognized;</li>
 *     <li>breaking at line breaks, as ICU does, rather than treating them as whitespace, as in XML documents;</li>
 *     <li>ICU rules of its own, in the syntax of ICU's {@link RuleBasedBreakIterator}, in place of ICU's sentence
 *     rules for the language.</li>
 * </ul>
 *
 * <p>A sentence is returned as it appears in the text, without the whitespace around it, so its offsets are
 * exact.</p>
 *
 * <p>An instance is immutable and can be used by several threads; the ICU iterators it creates are not shared.</p>
 */
public final class SentenceBreaker {

    /** The characters that ICU's sentence rules treat as ending a paragraph, each one char long. */
    private static final Pattern NEWLINE = Pattern.compile("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]");
    /** An em or en dash directly before a letter. */
    private static final Pattern DASH_BEFORE_LETTER = Pattern.compile("[\\u2014\\u2013](?=\\p{L})");
    /** The most letters of a run of initials that {@link Options#maxInitials()} may allow. */
    public static final int MAX_INITIALS = 10;
    /** The most compiled rule sets kept, so that a caller's rules are compiled once, not for each call. */
    private static final int MAX_COMPILED_RULES = 64;
    private static final Map<String, RuleBasedBreakIterator> COMPILED_RULES = new ConcurrentHashMap<>();

    private final Options options;
    private final ULocale locale;
    @Nullable private final RuleBasedBreakIterator rules;

    /**
     * How text is split. {@link #DEFAULT} is ICU's and CLDR's rules for English, and line breaks as whitespace; the
     * other rules are off.
     *
     * @param language a BCP 47 language tag, which selects ICU's rules and CLDR's abbreviations
     * @param abbreviations strings that never end a sentence, e.g. "Amb." or "No."
     * @param cldrAbbreviations whether CLDR's abbreviations for the language never end a sentence either
     * @param mergeInitials whether initials followed by a capital don't end a sentence
     * @param maxInitials the most capital letters, each with a period and with no space between them, that are taken
     *     as one name's initials: 1 for "J. F.", 2 for "R.N." as well, from 1 to {@value #MAX_INITIALS}
     * @param mergeQuotedContinuation whether a question or exclamation mark, closing quotes or brackets and then a
     *     lowercase word don't end a sentence
     * @param dashAsSpace whether an em or en dash directly before a letter separates words, for the abbreviations and
     *     initials after it
     * @param breakAtNewlines whether a line break ends a sentence, as in ICU's rules; otherwise it is whitespace, as
     *     in the text of XML documents
     * @param rules ICU break rules that replace ICU's sentence rules for the language, or null for those
     */
    public record Options(String language, List<String> abbreviations, boolean cldrAbbreviations,
            boolean mergeInitials, int maxInitials, boolean mergeQuotedContinuation, boolean dashAsSpace,
            boolean breakAtNewlines, @Nullable String rules) {

        /** ICU's and CLDR's rules for English, and line breaks as whitespace. */
        public static final Options DEFAULT = new Options("en", List.of(), true, false, 1, false, false, false, null);

        public Options {
            abbreviations = List.copyOf(abbreviations);
            if (maxInitials < 1 || maxInitials > MAX_INITIALS) {
                throw new IllegalArgumentException("maxInitials must be from 1 to " + MAX_INITIALS + ": " + maxInitials);
            }
        }

        /** @return a builder that starts from these options */
        public Builder toBuilder() {
            return new Builder(this);
        }
    }

    /** Builds {@link Options}, from {@link Options#DEFAULT} or other options. */
    public static final class Builder {
        private String language;
        private List<String> abbreviations;
        private boolean cldrAbbreviations;
        private boolean mergeInitials;
        private int maxInitials;
        private boolean mergeQuotedContinuation;
        private boolean dashAsSpace;
        private boolean breakAtNewlines;
        @Nullable private String rules;

        private Builder(final Options from) {
            language = from.language();
            abbreviations = from.abbreviations();
            cldrAbbreviations = from.cldrAbbreviations();
            mergeInitials = from.mergeInitials();
            maxInitials = from.maxInitials();
            mergeQuotedContinuation = from.mergeQuotedContinuation();
            dashAsSpace = from.dashAsSpace();
            breakAtNewlines = from.breakAtNewlines();
            rules = from.rules();
        }

        public Builder language(final String language) {
            this.language = language;
            return this;
        }

        public Builder abbreviations(final List<String> abbreviations) {
            this.abbreviations = abbreviations;
            return this;
        }

        public Builder cldrAbbreviations(final boolean cldrAbbreviations) {
            this.cldrAbbreviations = cldrAbbreviations;
            return this;
        }

        public Builder mergeInitials(final boolean mergeInitials) {
            this.mergeInitials = mergeInitials;
            return this;
        }

        public Builder maxInitials(final int maxInitials) {
            this.maxInitials = maxInitials;
            return this;
        }

        public Builder mergeQuotedContinuation(final boolean mergeQuotedContinuation) {
            this.mergeQuotedContinuation = mergeQuotedContinuation;
            return this;
        }

        public Builder dashAsSpace(final boolean dashAsSpace) {
            this.dashAsSpace = dashAsSpace;
            return this;
        }

        public Builder breakAtNewlines(final boolean breakAtNewlines) {
            this.breakAtNewlines = breakAtNewlines;
            return this;
        }

        public Builder rules(@Nullable final String rules) {
            this.rules = rules;
            return this;
        }

        public Options build() {
            return new Options(language, abbreviations, cldrAbbreviations, mergeInitials, maxInitials,
                    mergeQuotedContinuation, dashAsSpace, breakAtNewlines, rules);
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

    /**
     * @param options how text is split
     * @throws IllegalArgumentException if the options' rules are not valid ICU break rules
     */
    public SentenceBreaker(final Options options) {
        this.options = options;
        this.locale = ULocale.forLanguageTag(options.language().isEmpty() ? "en" : options.language());
        this.rules = options.rules() == null ? null : compile(options.rules());
    }

    /**
     * Compiles ICU break rules, once for each rule set while no more than {@value #MAX_COMPILED_RULES} are kept.
     *
     * @throws IllegalArgumentException if the rules are not valid, with ICU's message, which says where
     */
    private static RuleBasedBreakIterator compile(final String rules) {
        final RuleBasedBreakIterator compiled = COMPILED_RULES.get(rules);
        if (compiled != null) {
            return compiled;
        }
        final RuleBasedBreakIterator iterator = new RuleBasedBreakIterator(rules);
        if (COMPILED_RULES.size() >= MAX_COMPILED_RULES) {
            COMPILED_RULES.clear();
        }
        COMPILED_RULES.put(rules, iterator);
        return iterator;
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
        String seen = options.breakAtNewlines() ? text : NEWLINE.matcher(text).replaceAll(" ");
        if (options.dashAsSpace()) {
            // a dash joined to the next word, as in "Negotiations—Mr. Bell", is a space to ICU, so that its
            // abbreviations apply to the word after it; one character for one, which keeps the offsets
            seen = DASH_BEFORE_LETTER.matcher(seen).replaceAll(" ");
        }
        iterator.setText(seen);
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
        // a compiled iterator is shared: each use has its own copy, with its own text and position
        final BreakIterator base = rules != null ? (BreakIterator) rules.clone() : BreakIterator.getSentenceInstance(locale);
        return builder.wrapIteratorWithFilter(base);
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
            if (start >= 0 && text.startsWith(abbreviation, start) && (start == 0 || isWordStart(text.charAt(start - 1)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the text ends at {@code last} with initials: up to {@link Options#maxInitials()} capital letters each
     * followed by a period, as in "J. F." or "R.N.", after whitespace, an opening bracket or quote, or, with
     * {@link Options#dashAsSpace()}, a dash.
     */
    private boolean isInitial(final String text, final int last) {
        int i = last;
        int initials = 0;
        while (initials < options.maxInitials() && i >= 1 && text.charAt(i) == '.'
                && Character.isUpperCase(text.charAt(i - 1))) {
            initials++;
            i -= 2;
        }
        if (initials == 0) {
            return false;
        }
        return i < 0 || isWordStart(text.charAt(i));
    }

    /** Whether a word may start after this character: whitespace, an opening bracket or quote, or a dash as a space. */
    private boolean isWordStart(final char before) {
        return Character.isWhitespace(before) || isOpening(before) || options.dashAsSpace() && isDash(before);
    }

    private static boolean isDash(final char c) {
        return c == '—' || c == '–';
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
