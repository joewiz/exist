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
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void emptyElementsInMixedContentAreInline() throws Exception {
        final Document doc = parse("<p>a section (enclosed)<anchor n=\"2\"/> which I feel <pb n=\"219\"/> may matter.</p>");
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

    /** The tracker, given a node's events as the stream reader gives them, finds the same starts as the walk. */
    @Test
    void trackerAgreesWithTheWalk() throws Exception {
        for (final String xml : new String[] {
                "<div><head>Report</head><p>The treaty.</p><p>Done.</p></div>",
                "<p>The <persName>Secretary</persName> left. <hi>Then</hi> he returned.</p>",
                "<p>a section (enclosed)<anchor n=\"2\"/> which I feel <pb n=\"219\"/> may matter.</p>",
                "<div>\n  <head>Memo</head>\n  <p>Text.</p>\n</div>",
                "<div><head>Title</head><list><item>One.</item><item>Two.</item></list><p>After.</p></div>",
                "<p>Before <list><item>One.</item><item>Two.</item></list> after.</p>"}) {
            final Document doc = parse(xml);
            final Blocks.Tracker tracker = new Blocks.Tracker();
            events(doc.getDocumentElement(), tracker, new int[] {0});
            assertEquals(java.util.Arrays.toString(Blocks.starts(doc.getDocumentElement())),
                    java.util.Arrays.toString(tracker.starts()), xml);
        }
    }

    private static void events(final org.w3c.dom.Node node, final Blocks.Tracker tracker, final int[] offset) {
        tracker.startElement(offset[0]);
        for (org.w3c.dom.Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                events(child, tracker, offset);
            } else if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                tracker.text(child.getNodeValue().isBlank());
                offset[0] += child.getNodeValue().length();
            }
        }
        tracker.endElement(offset[0]);
    }

    @Test
    void storedFormRoundTrips() {
        final int[] starts = {6, 17, 300, 70_000};
        assertArrayEquals(starts, Blocks.decode(Blocks.encode(starts)));
        assertArrayEquals(new int[] {}, Blocks.decode(Blocks.encode(new int[] {})));
    }
}
