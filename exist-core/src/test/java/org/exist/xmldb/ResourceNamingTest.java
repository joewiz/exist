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
package org.exist.xmldb;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;

import org.exist.xquery.util.URIUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;

/**
 * Tests for XmldbURI handling of resource names containing special characters.
 *
 * These tests document the current behavior of XmldbURI when constructing,
 * encoding, decoding, and manipulating URIs with characters that are
 * problematic in URI contexts (RFC 3986 reserved characters, Unicode,
 * whitespace, etc.).
 *
 * Related: <a href="https://github.com/eXist-db/exist/issues/3795">#3795</a>
 *
 * @see XmldbURI
 * @see URIUtils
 */
@RunWith(Parameterized.class)
public class ResourceNamingTest {

    /**
     * A matrix of resource names with special characters and their expected
     * percent-encoded forms when used as URI path segments.
     *
     * Each entry is: { decodedName, encodedName, description }
     */
    @Parameterized.Parameters(name = "{2}: \"{0}\"")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                // Basic ASCII names (no encoding needed)
                {"simple.xml", "simple.xml", "plain ASCII"},
                {"my-file.xml", "my-file.xml", "hyphen"},
                {"my_file.xml", "my_file.xml", "underscore"},
                {"file~1.xml", "file~1.xml", "tilde"},
                {"FILE.XML", "FILE.XML", "uppercase"},

                // Spaces
                {"my file.xml", "my%20file.xml", "space"},
                {"  leading.xml", "%20%20leading.xml", "leading spaces"},

                // Unicode: accented Latin
                {"\u00E0\u00E9\u00EE\u00F6\u00FC.xml", "%C3%A0%C3%A9%C3%AE%C3%B6%C3%BC.xml", "accented Latin"},

                // Unicode: CJK
                {"\u4E16\u754C.xml", "%E4%B8%96%E7%95%8C.xml", "CJK characters"},

                // Unicode: Korean
                {"\uC5F4.xml", "%EC%97%B4.xml", "Korean character"},

                // Unicode: Arabic
                {"\u0645\u0644\u0641.xml", "%D9%85%D9%84%D9%81.xml", "Arabic characters"},

                // Unicode: Emoji (supplementary plane)
                {"\uD83D\uDE00.xml", "%F0%9F%98%80.xml", "emoji"},

                // RFC 3986 reserved characters (gen-delims)
                {"file#1.xml", "file%231.xml", "hash"},
                {"file?1.xml", "file%3F1.xml", "question mark"},
                {"a:b.xml", "a%3Ab.xml", "colon"},
                {"a@b.xml", "a%40b.xml", "at sign"},

                // RFC 3986 reserved characters (sub-delims)
                {"a!b.xml", "a%21b.xml", "exclamation"},
                {"a$b.xml", "a%24b.xml", "dollar"},
                {"a&b.xml", "a%26b.xml", "ampersand"},
                {"a'b.xml", "a%27b.xml", "apostrophe"},
                {"a(b).xml", "a%28b%29.xml", "parentheses"},
                {"a*b.xml", "a%2Ab.xml", "asterisk"},
                {"a+b.xml", "a%2Bb.xml", "plus"},
                {"a,b.xml", "a%2Cb.xml", "comma"},
                {"a;b.xml", "a%3Bb.xml", "semicolon"},
                {"a=b.xml", "a%3Db.xml", "equals"},

                // Square brackets (used in IPv6 and XPath)
                {"t[1].xml", "t%5B1%5D.xml", "square brackets"},

                // Other problematic characters
                {"a{b}.xml", "a%7Bb%7D.xml", "curly braces"},
                {"a|b.xml", "a%7Cb.xml", "pipe"},
                {"a^b.xml", "a%5Eb.xml", "caret"},
                {"a`b.xml", "a%60b.xml", "backtick"},
                {"100%.xml", "100%25.xml", "percent sign"},

                // Backslash (Windows path separator)
                {"a\\b.xml", "a%5Cb.xml", "backslash"},

                // Mixed: the TestConstants.DECODED_SPECIAL_NAME pattern
                {"t[e s]t\u00E0\u00C5\u00F4", "t%5Be%20s%5Dt%C3%A0%C3%85%C3%B4", "mixed special"},

                // Dots (relative path components)
                {"..xml", "..xml", "double dot prefix"},
                {"a..b.xml", "a..b.xml", "double dot in middle"},
        });
    }

    private final String decoded;
    private final String encoded;
    private final String description;

    public ResourceNamingTest(final String decoded, final String encoded, final String description) {
        this.decoded = decoded;
        this.encoded = encoded;
        this.description = description;
    }

    /**
     * Tests that URIUtils.encodeForURI produces the expected percent-encoded form
     * for a resource name used as a single URI path segment.
     */
    @Test
    public void encodeForURI_producesExpectedEncoding() {
        assertEquals(encoded, URIUtils.encodeForURI(decoded));
    }

    /**
     * Tests that encoding then decoding a resource name round-trips correctly.
     */
    @Test
    public void encodeForURI_thenDecode_roundTrips() {
        final String roundTripped = URIUtils.urlDecodeUtf8(URIUtils.encodeForURI(decoded));
        assertEquals(decoded, roundTripped);
    }

    /**
     * Tests that urlEncodeUtf8 then urlDecodeUtf8 round-trips correctly.
     */
    @Test
    public void urlEncodeUtf8_thenDecode_roundTrips() {
        final String roundTripped = URIUtils.urlDecodeUtf8(URIUtils.urlEncodeUtf8(decoded));
        assertEquals(decoded, roundTripped);
    }

    /**
     * Tests that an XmldbURI created from the encoded name preserves the
     * encoded form in getRawCollectionPath and the decoded form in
     * getCollectionPath.
     */
    @Test
    public void xmldbURI_preservesEncodedAndDecodedForms() throws URISyntaxException {
        final String path = "/db/" + encoded;
        final XmldbURI uri = XmldbURI.xmldbUriFor(path);

        // getRawCollectionPath should return the percent-encoded form
        assertEquals("raw collection path", path, uri.getRawCollectionPath());

        // getCollectionPath should return the decoded form
        assertEquals("decoded collection path", "/db/" + decoded, uri.getCollectionPath());
    }

    /**
     * Tests that lastSegment() returns the encoded resource name.
     */
    @Test
    public void xmldbURI_lastSegment_returnsEncodedName() throws URISyntaxException {
        final String path = "/db/test/" + encoded;
        final XmldbURI uri = XmldbURI.xmldbUriFor(path);
        assertEquals(encoded, uri.lastSegment().toString());
    }

    /**
     * Tests that removeLastSegment() correctly strips the resource name,
     * leaving the parent collection path.
     */
    @Test
    public void xmldbURI_removeLastSegment_leavesParent() throws URISyntaxException {
        final String path = "/db/test/" + encoded;
        final XmldbURI uri = XmldbURI.xmldbUriFor(path);
        assertEquals("/db/test", uri.removeLastSegment().toString());
    }

    /**
     * Tests that append() correctly joins a collection path and resource name.
     */
    @Test
    public void xmldbURI_append_joinsCorrectly() {
        final XmldbURI parent = XmldbURI.create("/db/test");
        final XmldbURI child = XmldbURI.create(encoded);
        final XmldbURI result = parent.append(child);
        assertEquals("/db/test/" + encoded, result.toString());
    }
}
