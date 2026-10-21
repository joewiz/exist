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
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The block starts of a node: the children of elements without text of their own, at offsets in its string value.
 */
class BlocksTest {

    private static Document parse(final String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    @Test
    void childrenOfAnElementWithoutTextAreBlocks() throws Exception {
        // string value: "Report" "The treaty." "Done."
        final Document doc = parse("<div><head>Report</head><p>The treaty.</p><p>Done.</p></div>");
        assertArrayEquals(new int[] {6, 17}, Blocks.starts(doc.getDocumentElement()));
    }

    @Test
    void elementsInMixedContentAreInline() throws Exception {
        final Document doc = parse("<p>The <persName>Secretary</persName> left. <hi>Then</hi> he returned.</p>");
        assertArrayEquals(new int[] {}, Blocks.starts(doc.getDocumentElement()));
    }

    @Test
    void whitespaceDoesNotMakeText() throws Exception {
        // string value: "\n  " "Memo" "\n  " "Text." "\n"
        final Document doc = parse("<div>\n  <head>Memo</head>\n  <p>Text.</p>\n</div>");
        assertArrayEquals(new int[] {3, 10}, Blocks.starts(doc.getDocumentElement()));
    }

    @Test
    void nestedBlocks() throws Exception {
        // string value: "Title" "One." "Two." "After."
        final Document doc = parse("<div><head>Title</head><list><item>One.</item><item>Two.</item></list><p>After.</p></div>");
        assertArrayEquals(new int[] {5, 9, 13}, Blocks.starts(doc.getDocumentElement()));
    }

    @Test
    void storedFormRoundTrips() {
        final int[] starts = {6, 17, 300, 70_000};
        assertArrayEquals(starts, Blocks.decode(Blocks.encode(starts)));
        assertArrayEquals(new int[] {}, Blocks.decode(Blocks.encode(new int[] {})));
    }
}
