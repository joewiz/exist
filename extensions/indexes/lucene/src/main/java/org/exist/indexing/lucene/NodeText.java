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

import org.exist.dom.persistent.NodeProxy;
import org.exist.numbering.NodeId;
import org.exist.stax.ExtendedXMLStreamReader;
import org.exist.stax.IEmbeddedXMLStreamReader;
import org.exist.storage.DBBroker;

import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.function.ObjIntConsumer;

/**
 * The text of a node as the index sees it: its descendants' text, with the index configuration's inline and ignored
 * elements applied, as {@link DefaultTextExtractor} builds it for indexing.
 */
public final class NodeText {

    private NodeText() {
    }

    /**
     * The text of the node, for the index configuration that applies to it.
     *
     * @param broker the broker to read the node with
     * @param config the Lucene configuration of the node's collection
     * @param indexConfig the index configuration that applies to the node
     * @param node the node
     * @param onText called with each text node's id and the offset of its text in the result
     * @return the text
     * @throws IOException if the node cannot be read
     */
    public static String of(final DBBroker broker, final LuceneConfig config, final LuceneIndexConfig indexConfig,
            final NodeProxy node, final ObjIntConsumer<NodeId> onText) throws IOException {
        return of(broker, config, indexConfig, node, onText, null);
    }

    /**
     * The text of the node, for the index configuration that applies to it, with the starts of its blocks.
     *
     * @param broker the broker to read the node with
     * @param config the Lucene configuration of the node's collection
     * @param indexConfig the index configuration that applies to the node
     * @param node the node
     * @param onText called with each text node's id and the offset of its text in the result
     * @param blocks given the offsets in the result at which the node's elements start and end, if not null
     * @return the text
     * @throws IOException if the node cannot be read
     */
    public static String of(final DBBroker broker, final LuceneConfig config, final LuceneIndexConfig indexConfig,
            final NodeProxy node, final ObjIntConsumer<NodeId> onText, @Nullable final Blocks.Tracker blocks)
            throws IOException {
        final TextExtractor extractor = new DefaultTextExtractor();
        extractor.configure(config, indexConfig);
        int level = 0;
        int textOffset = 0;
        try {
            final IEmbeddedXMLStreamReader reader = broker.getXMLStreamReader(node, false);
            while (reader.hasNext()) {
                final int event = reader.next();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    if (blocks != null) {
                        blocks.endElement(textOffset);
                    }
                    // #4835: stop at the end of the node, as the reader goes on to its siblings
                    if (--level <= 0) {
                        break;
                    }
                    // the node's own start and end are not separators
                    textOffset += extractor.endElement(reader.getQName());
                } else if (event == XMLStreamConstants.START_ELEMENT) {
                    if (level > 0) {
                        textOffset += extractor.startElement(reader.getQName());
                    }
                    if (blocks != null) {
                        blocks.startElement(textOffset);
                    }
                    ++level;
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    final NodeId nodeId = (NodeId) reader.getProperty(ExtendedXMLStreamReader.PROPERTY_NODE_ID);
                    if (blocks != null) {
                        blocks.text(reader.getXMLText().toString().isBlank());
                    }
                    textOffset += extractor.beforeCharacters();
                    final int consumed = extractor.characters(reader.getXMLText());
                    if (consumed > 0) {
                        onText.accept(nodeId, textOffset);
                        textOffset += consumed;
                    }
                }
            }
        } catch (final XMLStreamException e) {
            throw new IOException(e.getMessage(), e);
        }
        return extractor.getText().toString();
    }
}
