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

import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.util.BytesRef;
import org.exist.dom.persistent.NodeProxy;
import org.exist.stax.IEmbeddedXMLStreamReader;
import org.exist.storage.DBBroker;
import org.w3c.dom.Node;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.ObjIntConsumer;
import java.util.stream.IntStream;

/**
 * Where a node's blocks start: the paragraphs, headings, list items and the like that a sentence never runs across.
 *
 * <p>A block is a child of an element without text of its own, only elements and whitespace, such as a TEI div
 * with its head and paragraphs. In an element with text, mixed content, the elements are inline: a name or an
 * emphasis within a sentence, or an empty element such as a page break or a note's anchor. No configuration is
 * needed, and the text indexed is unchanged.</p>
 *
 * <p>The starts are offsets in the node's string value, the text of its descendants, as a field with an expression
 * indexes it, and are stored beside the field's value, in the field named by {@link #fieldName(String)}.</p>
 */
public final class Blocks {

    private static final String SUFFIX = "#blocks";

    private Blocks() {
    }

    /**
     * The field in which the block starts of a field's values are stored.
     *
     * @param field the field
     * @return the name of the field of its block starts
     */
    public static String fieldName(final String field) {
        return field + SUFFIX;
    }

    /**
     * Finds the block starts from a node's events in document order, at offsets in whatever text the caller builds
     * from it: an element's children are blocks once the element has turned out to have elements and no text.
     */
    public static final class Tracker {
        private final Deque<Frame> open = new ArrayDeque<>();
        private final IntStream.Builder starts = IntStream.builder();
        private int end = Integer.MAX_VALUE;

        private static final class Frame {
            private final IntStream.Builder children = IntStream.builder();
            private boolean elements;
            private boolean text;
        }

        /**
         * An element starts.
         *
         * @param offset the offset in the text at which it starts
         */
        public void startElement(final int offset) {
            final Frame parent = open.peek();
            if (parent != null) {
                parent.elements = true;
                parent.children.add(offset);
            }
            open.push(new Frame());
        }

        /**
         * A text node.
         *
         * @param blank whether it is only whitespace
         */
        public void text(final boolean blank) {
            final Frame current = open.peek();
            if (current != null && !blank) {
                current.text = true;
            }
        }

        /**
         * An element ends.
         *
         * @param offset the offset in the text at which it ends
         */
        public void endElement(final int offset) {
            final Frame frame = open.pop();
            if (frame.elements && !frame.text) {
                frame.children.build().forEach(starts::add);
                // the text after an element with blocks, in its parent, starts a block too
                starts.add(offset);
            }
            if (open.isEmpty()) {
                end = offset;
            }
        }

        /**
         * The block starts found, except at the start and the end of the node.
         *
         * @return the offsets, ascending
         */
        public int[] starts() {
            return starts.build().filter(start -> start > 0 && start < end).sorted().distinct().toArray();
        }
    }

    /**
     * The offsets in a stored node's string value at which a block starts, except at 0, read as a stream of events.
     *
     * @param broker the broker to read the node with
     * @param node the node
     * @return the offsets, ascending
     * @throws IOException if the node cannot be read
     */
    public static int[] starts(final DBBroker broker, final NodeProxy node) throws IOException {
        final Tracker tracker = new Tracker();
        int level = 0;
        int offset = 0;
        try {
            final IEmbeddedXMLStreamReader reader = broker.getXMLStreamReader(node, false);
            while (reader.hasNext()) {
                final int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    tracker.startElement(offset);
                    level++;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    tracker.endElement(offset);
                    if (--level <= 0) {
                        break;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    final String text = reader.getText();
                    tracker.text(text.isBlank());
                    offset += text.length();
                }
            }
        } catch (final XMLStreamException e) {
            throw new IOException(e.getMessage(), e);
        }
        return tracker.starts();
    }

    /**
     * The offsets in the node's string value at which a block starts, except at 0.
     *
     * @param node the node
     * @return the offsets, ascending
     */
    public static int[] starts(final Node node) {
        final IntStream.Builder starts = IntStream.builder();
        walk(node, (text, offset) -> {
            if (offset > 0) {
                starts.add(offset);
            }
        });
        return starts.build().toArray();
    }

    /**
     * Calls the visitor with each text node that starts a block, and its offset in the node's string value.
     *
     * @param node the node
     * @param visitor called with each text node that starts a block and its offset
     */
    public static void walk(final Node node, final ObjIntConsumer<Node> visitor) {
        walk(node, 0, new boolean[] {false}, visitor);
    }

    private static int walk(final Node node, final int offset, final boolean[] pending, final ObjIntConsumer<Node> visitor) {
        final boolean elementOnly = node.getNodeType() == Node.ELEMENT_NODE && isElementOnly(node);
        int at = offset;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (elementOnly) {
                pending[0] = true;
            }
            switch (child.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    final String text = child.getNodeValue();
                    if (pending[0] && !text.isBlank()) {
                        visitor.accept(child, at);
                        pending[0] = false;
                    }
                    at += text.length();
                }
                case Node.ELEMENT_NODE -> at = walk(child, at, pending, visitor);
                default -> {
                    // comments and processing instructions are not in the string value
                }
            }
        }
        if (elementOnly) {
            pending[0] = true;
        }
        return at;
    }

    /**
     * Whether an element has elements and no text of its own: an empty element, such as a page break or an anchor in
     * a paragraph, is inline.
     */
    private static boolean isElementOnly(final Node element) {
        boolean elements = false;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            final short type = child.getNodeType();
            if ((type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) && !child.getNodeValue().isBlank()) {
                return false;
            }
            elements |= type == Node.ELEMENT_NODE;
        }
        return elements;
    }

    /**
     * The block starts, as stored: the differences between them, as variable-length integers.
     *
     * @param starts the offsets, ascending
     * @return the stored form
     */
    public static BytesRef encode(final int[] starts) {
        final ByteBuffersDataOutput out = new ByteBuffersDataOutput();
        try {
            out.writeVInt(starts.length);
            int previous = 0;
            for (final int start : starts) {
                out.writeVInt(start - previous);
                previous = start;
            }
        } catch (final IOException e) {
            // writing to memory does not fail
            throw new IllegalStateException(e);
        }
        return new BytesRef(out.toArrayCopy());
    }

    /**
     * The block starts, from their stored form.
     *
     * @param stored the stored form
     * @return the offsets, ascending
     */
    public static int[] decode(final BytesRef stored) {
        final ByteArrayDataInput in = new ByteArrayDataInput(stored.bytes, stored.offset, stored.length);
        final int[] starts = new int[in.readVInt()];
        int previous = 0;
        for (int i = 0; i < starts.length; i++) {
            previous += in.readVInt();
            starts[i] = previous;
        }
        return starts;
    }
}
