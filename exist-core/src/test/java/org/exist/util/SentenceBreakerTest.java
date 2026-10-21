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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK {@link BreakIterator} of a {@link SentenceBreaker}, as Lucene's highlighters use it, has the breaker's
 * sentence boundaries, after the merge rules.
 */
class SentenceBreakerTest {

    private static final String TEXT = "The Secretary (J. F. Dulles) left. \"Is this final?\" he asked. Done.";

    @Test
    void boundariesAreTheEndsOfTheSentences() {
        final BreakIterator iterator = new SentenceBreaker(SentenceBreaker.Options.DEFAULT).toBreakIterator();
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
        final BreakIterator iterator = new SentenceBreaker(SentenceBreaker.Options.DEFAULT).toBreakIterator();
        iterator.setText(TEXT);
        final int insideQuestion = TEXT.indexOf("final");
        assertEquals(TEXT.indexOf("Done"), iterator.following(insideQuestion));
        assertEquals(TEXT.indexOf("\"Is"), iterator.preceding(insideQuestion));
        assertTrue(iterator.isBoundary(TEXT.indexOf("Done")));
        assertFalse(iterator.isBoundary(TEXT.indexOf("F. Dulles")));
    }

    @Test
    void boundariesAreRelativeToTheTextsBeginIndex() {
        final BreakIterator iterator = new SentenceBreaker(SentenceBreaker.Options.DEFAULT).toBreakIterator();
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
        assertEquals(3, new SentenceBreaker(new SentenceBreaker.Options("en", List.of(), true, true, true, true))
                .sentences(text).size());
    }

    @Test
    void callersAbbreviationsWithSeveralPeriods() {
        final SentenceBreaker breaker = new SentenceBreaker(
                new SentenceBreaker.Options("en", List.of("U.S."), true, true, true, false));
        assertEquals(List.of(new SentenceBreaker.Span(0, 38), new SentenceBreaker.Span(39, 52)),
                breaker.sentences("He said the U.S. Government would act. Then he left."));
    }
}
