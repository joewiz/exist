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
package org.exist.xquery.modules.lucene;

import org.exist.dom.INodeHandle;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.InMemoryNodeSet;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.storage.serializers.EXistOutputKeys;
import org.exist.xquery.*;
import org.exist.xquery.value.*;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.util.Properties;

import static org.exist.xquery.FunctionDSL.*;
import static org.exist.xquery.modules.lucene.LuceneModule.functionSignature;

/**
 * Implements {@code ft:highlight()} — creates an in-memory copy of nodes from
 * a Lucene full-text query with all matching terms wrapped in {@code <exist:match>}
 * elements.
 *
 * <p>This is a convenience function that provides the same functionality as
 * {@code util:expand($hits, "highlight-matches=elements expand-xincludes=no")}
 * but with a cleaner API specific to the Lucene module. It pairs naturally with
 * {@code ft:get-passages()} — use {@code ft:highlight()} when you want the full
 * element with matches highlighted, and {@code ft:get-passages()} when you want
 * only the best-scoring snippets.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * let $hits := //p[ft:query(., "quick brown fox")]
 * for $hit in $hits
 * return ft:highlight($hit)
 * </pre>
 */
public class Highlight extends BasicFunction {

    static final FunctionSignature signature = functionSignature(
            "highlight",
            "Creates an in-memory copy of the given nodes with Lucene full-text " +
            "match terms wrapped in exist:match elements. The returned nodes preserve " +
            "the full structure of the original elements. " +
            "Equivalent to util:expand($nodes, 'highlight-matches=elements expand-xincludes=no') " +
            "but provides a cleaner API for Lucene-specific highlighting.",
            returnsOptMany(org.exist.xquery.value.Type.NODE, "copies of the input nodes with " +
                    "exist:match elements wrapping matched terms"),
            optManyParam("nodes", org.exist.xquery.value.Type.NODE,
                    "node(s) from a Lucene full-text query (e.g. from ft:query)")
    );

    private static final Properties HIGHLIGHT_OPTIONS = new Properties();
    static {
        HIGHLIGHT_OPTIONS.setProperty(EXistOutputKeys.EXPAND_XINCLUDES, "no");
        HIGHLIGHT_OPTIONS.setProperty(EXistOutputKeys.HIGHLIGHT_MATCHES, "elements");
    }

    public Highlight(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        if (args[0].isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }

        context.pushDocumentContext();
        try {
            final InMemoryNodeSet result = new InMemoryNodeSet();
            final MemTreeBuilder builder = new MemTreeBuilder(this, getContext());
            final DocumentBuilderReceiver receiver = new DocumentBuilderReceiver(this, builder, true);

            for (final SequenceIterator i = args[0].iterate(); i.hasNext(); ) {
                final NodeValue next = (NodeValue) i.nextItem();
                final short nodeType = ((INodeHandle) next).getNodeType();

                builder.startDocument();
                next.toSAX(context.getBroker(), receiver, HIGHLIGHT_OPTIONS);
                builder.endDocument();

                if (Node.DOCUMENT_NODE == nodeType) {
                    result.add(builder.getDocument());
                } else {
                    result.add(builder.getDocument().getNode(1));
                }

                builder.reset(getContext());
            }
            return result;
        } catch (final SAXException e) {
            throw new XPathException(this, e);
        } finally {
            context.popDocumentContext();
        }
    }
}
