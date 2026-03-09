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
package org.exist.xquery.ft;

import org.exist.EXistException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.value.Sequence;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end integration tests for W3C XQFT 3.0 "contains text" expressions.
 * These tests exercise the full pipeline: parse → tree-walk → evaluate.
 */
public class FTContainsTest {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private Sequence executeQuery(final String query) throws EXistException, PermissionDeniedException, XPathException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final XQuery xquery = pool.getXQueryService();
        try (final DBBroker broker = pool.getBroker()) {
            return xquery.execute(broker, query, null);
        }
    }

    private boolean evalBool(final String query) throws EXistException, PermissionDeniedException, XPathException {
        final Sequence result = executeQuery(query);
        assertNotNull(result);
        assertEquals(1, result.getItemCount());
        return result.effectiveBooleanValue();
    }

    // === Basic matching ===

    @Test
    public void simpleWordMatch() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'hello'"));
    }

    @Test
    public void simpleWordNoMatch() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'goodbye'"));
    }

    @Test
    public void caseInsensitiveByDefault() throws Exception {
        // XQFT 3.0 §4.1: default case mode is implementation-defined.
        // Our implementation defaults to case-insensitive, matching XQFTTS expectations.
        assertTrue(evalBool("'Hello World' contains text 'hello'"));
    }

    @Test
    public void caseInsensitive() throws Exception {
        assertTrue(evalBool("'Hello World' contains text 'hello' using case insensitive"));
    }

    @Test
    public void phraseMatch() throws Exception {
        assertTrue(evalBool("'the quick brown fox' contains text 'quick brown' phrase"));
    }

    @Test
    public void phraseNoMatch() throws Exception {
        assertFalse(evalBool("'the quick brown fox' contains text 'brown quick' phrase"));
    }

    // === AnyallMode ===

    @Test
    public void anyWordMode() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'goodbye hello' any word"));
    }

    @Test
    public void allWordsMode() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'hello world' all words"));
    }

    @Test
    public void allWordsModeFailure() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'hello goodbye' all words"));
    }

    // === Boolean operators ===

    @Test
    public void ftand() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'hello' ftand 'world'"));
    }

    @Test
    public void ftandFailure() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'hello' ftand 'goodbye'"));
    }

    @Test
    public void ftor() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'goodbye' ftor 'hello'"));
    }

    @Test
    public void ftorFailure() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'goodbye' ftor 'farewell'"));
    }

    @Test
    public void ftnot() throws Exception {
        assertTrue(evalBool("'hello world' contains text ftnot 'goodbye'"));
    }

    @Test
    public void ftnotFailure() throws Exception {
        assertFalse(evalBool("'hello world' contains text ftnot 'hello'"));
    }

    @Test
    public void mildNot() throws Exception {
        // "hello" not in "world" — "hello" matches at pos 0, "world" matches at pos 1
        // They don't overlap, so hello's match survives
        assertTrue(evalBool("'hello world' contains text 'hello' not in 'world'"));
    }

    // === Positional filters ===

    @Test
    public void atStart() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'hello' at start"));
    }

    @Test
    public void atStartFailure() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'world' at start"));
    }

    @Test
    public void atEnd() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'world' at end"));
    }

    @Test
    public void atEndFailure() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'hello' at end"));
    }

    @Test
    public void entireContent() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'hello world' all words entire content"));
    }

    @Test
    public void entireContentFailure() throws Exception {
        assertFalse(evalBool("'hello world foo' contains text 'hello world' all words entire content"));
    }

    // === Window ===

    @Test
    public void windowMatch() throws Exception {
        assertTrue(evalBool("'the quick brown fox' contains text 'quick' ftand 'fox' window 4 words"));
    }

    @Test
    public void windowTooSmall() throws Exception {
        assertFalse(evalBool("'the quick brown fox' contains text 'quick' ftand 'fox' window 2 words"));
    }

    // === Distance ===

    @Test
    public void distanceMatch() throws Exception {
        // "quick" is at pos 1, "fox" at pos 3 → gap = 1 (brown is between)
        assertTrue(evalBool("'the quick brown fox' contains text 'quick' ftand 'fox' distance at most 2 words"));
    }

    @Test
    public void distanceTooFar() throws Exception {
        assertFalse(evalBool("'the quick brown fox' contains text 'quick' ftand 'fox' distance exactly 0 words"));
    }

    // === Wildcards ===

    @Test
    public void wildcards() throws Exception {
        assertTrue(evalBool("'hello world' contains text 'hel.*' using wildcards"));
    }

    @Test
    public void wildcardsNoMatch() throws Exception {
        assertFalse(evalBool("'hello world' contains text 'xyz.*' using wildcards"));
    }

    // === With XML nodes ===

    @Test
    public void xmlNodeMatch() throws Exception {
        assertTrue(evalBool("<title>Hello World</title> contains text 'Hello'"));
    }

    @Test
    public void xmlFilterExpression() throws Exception {
        final Sequence result = executeQuery(
            "let $books := (<book><title>XQuery in Action</title></book>," +
            "               <book><title>Java Programming</title></book>," +
            "               <book><title>XML and XQuery</title></book>)" +
            "return $books[title contains text 'XQuery']"
        );
        assertEquals(2, result.getItemCount());
    }

    // === FLWOR with contains text ===

    @Test
    public void flworWithContainsText() throws Exception {
        final Sequence result = executeQuery(
            "for $w in ('hello', 'goodbye', 'world') " +
            "where $w contains text 'hello' ftor 'world' " +
            "return $w"
        );
        assertEquals(2, result.getItemCount());
    }

    // === Case modes ===

    @Test
    public void lowercaseMode() throws Exception {
        assertTrue(evalBool("'Hello World' contains text 'hello' using lowercase"));
    }

    @Test
    public void uppercaseMode() throws Exception {
        assertTrue(evalBool("'Hello World' contains text 'HELLO' using uppercase"));
    }

    // === FTTimes ===

    @Test
    public void timesAtMostZeroOccurrences() throws Exception {
        // "goodbye" doesn't appear in "hello world", which satisfies "at most 1 times"
        assertTrue(evalBool("'hello world' contains text 'goodbye' occurs at most 1 times"));
    }

    @Test
    public void timesAtMostOneOccurrence() throws Exception {
        // "hello" appears exactly 1 time, which satisfies "at most 1 times"
        assertTrue(evalBool("'hello world' contains text 'hello' occurs at most 1 times"));
    }

    @Test
    public void timesAtMostExceeded() throws Exception {
        // "hello" appears 2 times, which does NOT satisfy "at most 1 times"
        assertFalse(evalBool("'hello hello world' contains text 'hello' occurs at most 1 times"));
    }

    // === FTOr with empty sequence ===

    @Test
    public void ftorEmptySequence() throws Exception {
        // {()} (empty sequence) should match vacuously, so ftor always succeeds
        assertTrue(evalBool("'hello world' contains text {()} ftor 'goodbye'"));
    }

    @Test
    public void ftorEmptySequenceBothMiss() throws Exception {
        // {()} matches vacuously, so even without a word match, ftor succeeds
        assertTrue(evalBool("'hello world' contains text {()} ftor 'xyz'"));
    }

    // === declare ft-option ===

    @Test
    public void declareFtOption() throws Exception {
        assertTrue(evalBool(
            "declare ft-option using case sensitive;\n" +
            "'Hello World' contains text 'Hello'"
        ));
    }

    @Test
    public void declareFtOptionCaseSensitiveRejects() throws Exception {
        // With case sensitive declared, 'hello' (lowercase) should NOT match 'Hello'
        assertFalse(evalBool(
            "declare ft-option using case sensitive;\n" +
            "'Hello World' contains text 'hello'"
        ));
    }

    // === contains text with comparison ===

    @Test
    public void containsTextEqComparison() throws Exception {
        // "contains text" has higher precedence than "eq"
        assertFalse(evalBool(
            "'Hello World' contains text 'Hello' eq fn:false()"
        ));
    }
}
