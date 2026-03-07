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
package org.exist.xquery.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for URI encoding utilities, with a focus on the correctness and
 * consistency of encoding/decoding across the various URIUtils methods.
 *
 * These tests help ensure that resource names survive round-trips through
 * the encoding functions used by XmldbURI and the various eXist-db APIs.
 *
 * Related: <a href="https://github.com/eXist-db/exist/issues/3795">#3795</a>
 *
 * @see URIUtils
 */
public class URIUtilsEncodingTest {

    // --- encodeForURI (RFC 3986 path segment encoding) ---

    @Test
    public void encodeForURI_preservesUnreservedChars() {
        // RFC 3986 unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
        final String unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        assertEquals(unreserved, URIUtils.encodeForURI(unreserved));
    }

    @Test
    public void encodeForURI_encodesSpace() {
        assertEquals("hello%20world", URIUtils.encodeForURI("hello world"));
    }

    @Test
    public void encodeForURI_encodesSlash() {
        assertEquals("a%2Fb", URIUtils.encodeForURI("a/b"));
    }

    @Test
    public void encodeForURI_encodesPercent() {
        assertEquals("100%25", URIUtils.encodeForURI("100%"));
    }

    @Test
    public void encodeForURI_encodesHash() {
        assertEquals("a%23b", URIUtils.encodeForURI("a#b"));
    }

    @Test
    public void encodeForURI_encodesQuestionMark() {
        assertEquals("a%3Fb", URIUtils.encodeForURI("a?b"));
    }

    @Test
    public void encodeForURI_usesUppercaseHex() {
        // RFC 3986 Section 2.1: should use uppercase A-F
        final String encoded = URIUtils.encodeForURI(" ");
        assertEquals("%20", encoded);

        final String encodedBracket = URIUtils.encodeForURI("[");
        assertEquals("%5B", encodedBracket);
    }

    @Test
    public void encodeForURI_handlesTwoByteUtf8() {
        // à = U+00E0, UTF-8: 0xC3 0xA0
        assertEquals("%C3%A0", URIUtils.encodeForURI("\u00E0"));
    }

    @Test
    public void encodeForURI_handlesThreeByteUtf8() {
        // 열 = U+C5F4, UTF-8: 0xEC 0x97 0xB4
        assertEquals("%EC%97%B4", URIUtils.encodeForURI("\uC5F4"));
    }

    @Test
    public void encodeForURI_handlesFourByteUtf8() {
        // U+1F600 (grinning face), UTF-8: 0xF0 0x9F 0x98 0x80
        assertEquals("%F0%9F%98%80", URIUtils.encodeForURI("\uD83D\uDE00"));
    }

    @Test
    public void encodeForURI_handlesEmptyString() {
        assertEquals("", URIUtils.encodeForURI(""));
    }

    @Test
    public void encodeForURI_encodesAllGenDelims() {
        // gen-delims = ":" / "/" / "?" / "#" / "[" / "]" / "@"
        assertEquals("%3A%2F%3F%23%5B%5D%40", URIUtils.encodeForURI(":/?#[]@"));
    }

    @Test
    public void encodeForURI_encodesAllSubDelims() {
        // sub-delims = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
        assertEquals("%21%24%26%27%28%29%2A%2B%2C%3B%3D", URIUtils.encodeForURI("!$&'()*+,;="));
    }

    // --- urlEncodeUtf8 / urlDecodeUtf8 ---

    @Test
    public void urlEncodeUtf8_encodesSpaceAsPercent20() {
        // Verify space is %20, not +
        final String encoded = URIUtils.urlEncodeUtf8("hello world");
        assertTrue("space should be encoded as %20, not +", encoded.contains("%20"));
        assertFalse("should not contain +", encoded.contains("+"));
    }

    @Test
    public void urlEncodeUtf8_roundTrips() {
        final String original = "café résumé";
        assertEquals(original, URIUtils.urlDecodeUtf8(URIUtils.urlEncodeUtf8(original)));
    }

    @Test
    public void urlEncodeUtf8_roundTrips_withReservedChars() {
        final String original = "a:b?c#d[e]f@g";
        assertEquals(original, URIUtils.urlDecodeUtf8(URIUtils.urlEncodeUtf8(original)));
    }

    // --- urlEncodePartsUtf8 ---

    @Test
    public void urlEncodePartsUtf8_preservesSlashes() {
        final String path = "/db/test collection/résumé.xml";
        final String encoded = URIUtils.urlEncodePartsUtf8(path);
        // Slashes should be preserved
        assertTrue("should preserve slashes", encoded.contains("/db/"));
        // Spaces should be encoded
        assertFalse("should not contain literal spaces", encoded.contains(" "));
    }

    @Test
    public void urlEncodePartsUtf8_encodesEachSegmentIndependently() {
        final String path = "a b/c d/e f";
        final String encoded = URIUtils.urlEncodePartsUtf8(path);
        assertEquals("a%20b/c%20d/e%20f", encoded);
    }

    @Test
    public void urlEncodePartsUtf8_handlesEmptySegments() {
        final String path = "a//b";
        final String encoded = URIUtils.urlEncodePartsUtf8(path);
        assertEquals("a//b", encoded);
    }

    @Test
    public void urlEncodePartsUtf8_handlesTrailingSlash() {
        final String path = "/db/test/";
        final String encoded = URIUtils.urlEncodePartsUtf8(path);
        assertEquals("/db/test/", encoded);
    }

    // --- ensureUrlEncodedUtf8 ---

    @Test
    public void ensureUrlEncodedUtf8_alreadyEncoded() {
        final String encoded = "/db/test%20collection";
        assertEquals(encoded, URIUtils.ensureUrlEncodedUtf8(encoded));
    }

    @Test
    public void ensureUrlEncodedUtf8_plainPath() {
        final String path = "/db/test";
        assertEquals("/db/test", URIUtils.ensureUrlEncodedUtf8(path));
    }

    // --- iriToURI ---

    @Test
    public void iriToURI_preservesSlashesAndReservedChars() {
        final String iri = "/db/test/résumé.xml";
        final String result = URIUtils.iriToURI(iri);
        // Slashes should be preserved
        assertTrue("should contain slashes", result.contains("/db/test/"));
        // Non-ASCII should be encoded
        assertFalse("should not contain literal é", result.contains("é"));
    }

    // --- Double-encoding protection ---

    @Test
    public void encodeForURI_doubleEncoding_producesDistinctResult() {
        // If a name already contains "%20", encoding it again should produce "%2520"
        final String alreadyEncoded = "file%20name.xml";
        final String doubleEncoded = URIUtils.encodeForURI(alreadyEncoded);
        assertEquals("file%2520name.xml", doubleEncoded);
    }

    @Test
    public void urlEncodeUtf8_doubleEncoding_producesDistinctResult() {
        final String alreadyEncoded = "file%20name.xml";
        final String doubleEncoded = URIUtils.urlEncodeUtf8(alreadyEncoded);
        assertTrue("double-encoded % should become %25", doubleEncoded.contains("%2520"));
    }

    // --- Edge cases ---

    @Test
    public void encodeForURI_controlCharacters() {
        // Tab character
        assertEquals("%09", URIUtils.encodeForURI("\t"));
        // Null character
        assertEquals("%00", URIUtils.encodeForURI("\0"));
        // DEL
        assertEquals("%7F", URIUtils.encodeForURI("\u007F"));
    }

    @Test
    public void encodeForURI_longResourceName() {
        // 255-character filename (common filesystem limit)
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 251; i++) {
            sb.append('a');
        }
        sb.append(".xml");
        final String longName = sb.toString();
        assertEquals(longName, URIUtils.encodeForURI(longName));
    }
}
