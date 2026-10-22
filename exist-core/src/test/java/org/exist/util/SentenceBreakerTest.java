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

import org.junit.jupiter.api.Test;

import java.text.BreakIterator;
import java.text.StringCharacterIterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link SentenceBreaker} splits with ICU's and CLDR's rules by default, and with the rules a caller turns on or
 * gives. Its JDK {@link BreakIterator}, as Lucene's highlighters use it, has the breaker's sentence boundaries, after
 * the merge rules.
 */
class SentenceBreakerTest {

    private static final String TEXT = "The Secretary (J. F. Dulles) left. \"Is this final?\" he asked. Done.";

    /** The merge rules for initials and quoted continuations. */
    private static final SentenceBreaker.Options MERGES = SentenceBreaker.Options.DEFAULT.toBuilder()
            .mergeInitials(true)
            .mergeQuotedContinuation(true)
            .build();

    /** The rules a corpus of diplomatic documents needs, for its titles, names and dashes. */
    private static final SentenceBreaker.Options DIPLOMATIC = MERGES.toBuilder()
            .maxInitials(3)
            .dashAsSpace(true)
            .build();

    /**
     * ICU rules that end a sentence only at a semicolon, with the whitespace after it. A rule ends at a semicolon, so
     * the one it matches is escaped.
     */
    private static final String SEMICOLON_RULES = """
            [^\\;]* \\; [\\p{White_Space}]* ;
            [^\\;]+ ;
            """;

    @Test
    void boundariesAreTheEndsOfTheSentences() {
        final BreakIterator iterator = new SentenceBreaker(MERGES).toBreakIterator();
        iterator.setText(TEXT);
        assertEquals(0, iterator.first());
        assertEquals(TEXT.indexOf("\"Is"), iterator.next());
        assertEquals(TEXT.indexOf("Done"), iterator.next());
        assertEquals(TEXT.length(), iterator.next());
        assertEquals(BreakIterator.DONE, iterator.next());
        assertEquals(TEXT.length(), iterator.last());
    }

    @Test
    void followingAndPreceding() {
        final BreakIterator iterator = new SentenceBreaker(MERGES).toBreakIterator();
        iterator.setText(TEXT);
        final int insideQuestion = TEXT.indexOf("final");
        assertEquals(TEXT.indexOf("Done"), iterator.following(insideQuestion));
        assertEquals(TEXT.indexOf("\"Is"), iterator.preceding(insideQuestion));
        assertTrue(iterator.isBoundary(TEXT.indexOf("Done")));
        assertFalse(iterator.isBoundary(TEXT.indexOf("F. Dulles")));
    }

    @Test
    void boundariesAreRelativeToTheTextsBeginIndex() {
        final BreakIterator iterator = new SentenceBreaker(MERGES).toBreakIterator();
        final String prefixed = "xxxx" + TEXT;
        iterator.setText(new StringCharacterIterator(prefixed, 4, prefixed.length(), 4));
        assertEquals(4, iterator.first());
        assertEquals(4 + TEXT.indexOf("\"Is"), iterator.next());
    }

    @Test
    void lineBreaksAreWhitespaceByDefault() {
        final String text = "He turned to the\n    Kashmir debate.\nThen he left.";
        assertEquals(List.of(new SentenceBreaker.Span(0, 36), new SentenceBreaker.Span(37, 50)),
                new SentenceBreaker(SentenceBreaker.Options.DEFAULT).sentences(text));
        assertEquals(3, new SentenceBreaker(SentenceBreaker.Options.DEFAULT.toBuilder().breakAtNewlines(true).build())
                .sentences(text).size());
    }

    @Test
    void callersAbbreviationsWithSeveralPeriods() {
        final SentenceBreaker breaker = new SentenceBreaker(
                SentenceBreaker.Options.DEFAULT.toBuilder().abbreviations(List.of("U.S.")).build());
        assertEquals(List.of(new SentenceBreaker.Span(0, 38), new SentenceBreaker.Span(39, 52)),
                breaker.sentences("He said the U.S. Government would act. Then he left."));
    }

    /*
     * From a list of participants in the Foreign Relations of the United States, 1969–1976, vol. XXII, document 49:
     * a title, an em dash and a name, with no sentence ending anywhere in it, with the rules such a corpus turns on.
     */

    @Test
    void abbreviationAfterADash() {
        assertOneSentence("Deputy U.S. Negotiator, Panama Canal Treaty Negotiations—Mr. S. Morey Bell Prospective "
                + "Deputy Chief of Mission, U.S. Embassy Panama—Mr. Ray");
    }

    @Test
    void initialsOfSeveralLetters() {
        assertOneSentence("Director, Joint Staff—Major General R.N. Ginsburgh, USA JCS Representative, Panama Canal "
                + "Negotiations Working Group—Colonel C.D. Corbett, USA OJCS (J–5) Coordination Representative");
    }

    @Test
    void juniorBeforeAComma() {
        assertOneSentence("Military Assistant to the Secretary of Defense—Major General John A. Wickham, Jr., USA "
                + "Director, Inter-American Region (ISA)—Major General G.M. Wallace, USA");
    }

    @Test
    void sentencesStillEndAfterTheseRules() {
        final String text = "The talks were held in Panama—Mr. Bunker led them. Mr. Bell left. Colonel C.D. Corbett "
                + "stayed.";
        assertEquals(List.of(
                        new SentenceBreaker.Span(0, text.indexOf("Mr. Bell") - 1),
                        new SentenceBreaker.Span(text.indexOf("Mr. Bell"), text.indexOf("Colonel") - 1),
                        new SentenceBreaker.Span(text.indexOf("Colonel"), text.length())),
                new SentenceBreaker(DIPLOMATIC).sentences(text));
    }

    @Test
    void defaultsAreIcusRules() {
        // without the merge rules, the initial and the quoted question end sentences
        assertEquals(5, new SentenceBreaker(SentenceBreaker.Options.DEFAULT).sentences(TEXT).size());
        // initials of several letters, after a dash, need the rules turned on
        final String text = "Negotiations Working Group—Colonel C.D. Corbett, USA";
        assertEquals(1, new SentenceBreaker(DIPLOMATIC).sentences(text).size());
        assertEquals(2, new SentenceBreaker(MERGES).sentences(text).size());
    }

    @Test
    void maxInitialsIsTheLongestRunOfInitials() {
        final String text = "He met R.N. Ginsburgh there.";
        assertEquals(2, new SentenceBreaker(MERGES).sentences(text).size());
        assertEquals(1, new SentenceBreaker(MERGES.toBuilder().maxInitials(2).build()).sentences(text).size());
        assertThrows(IllegalArgumentException.class, () -> MERGES.toBuilder().maxInitials(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> MERGES.toBuilder().maxInitials(SentenceBreaker.MAX_INITIALS + 1).build());
    }

    @Test
    void rulesReplaceIcusSentenceRules() {
        final String text = "First, a clause; then. Another; and the rest";
        assertEquals(List.of(
                        new SentenceBreaker.Span(0, text.indexOf(';') + 1),
                        new SentenceBreaker.Span(text.indexOf("then"), text.lastIndexOf(';') + 1),
                        new SentenceBreaker.Span(text.indexOf("and"), text.length())),
                new SentenceBreaker(SentenceBreaker.Options.DEFAULT.toBuilder().rules(SEMICOLON_RULES).build())
                        .sentences(text));
    }

    @Test
    void mergeRulesApplyToTheCallersRules() {
        final String text = "Ask J.; F. Dulles came; Done";
        final SentenceBreaker.Options rules = SentenceBreaker.Options.DEFAULT.toBuilder().rules(SEMICOLON_RULES).build();
        assertEquals(3, new SentenceBreaker(rules).sentences(text).size());
        assertEquals(List.of(new SentenceBreaker.Span(0, text.indexOf("Done") - 1),
                        new SentenceBreaker.Span(text.indexOf("Done"), text.length())),
                new SentenceBreaker(rules.toBuilder().abbreviations(List.of("J.;")).build()).sentences(text));
    }

    @Test
    void sameRulesTwice() {
        final SentenceBreaker.Options rules = SentenceBreaker.Options.DEFAULT.toBuilder().rules(SEMICOLON_RULES).build();
        assertEquals(new SentenceBreaker(rules).sentences("a; b"), new SentenceBreaker(rules).sentences("a; b"));
    }

    @Test
    void invalidRules() {
        final SentenceBreaker.Options rules = SentenceBreaker.Options.DEFAULT.toBuilder().rules("[unclosed ;").build();
        assertThrows(IllegalArgumentException.class, () -> new SentenceBreaker(rules));
    }

    private static void assertOneSentence(final String text) {
        assertEquals(List.of(new SentenceBreaker.Span(0, text.length())), new SentenceBreaker(DIPLOMATIC).sentences(text));
    }
}
