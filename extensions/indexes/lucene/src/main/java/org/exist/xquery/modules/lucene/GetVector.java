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

import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneIndexWorker;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.exist.xquery.FunctionDSL.optParam;
import static org.exist.xquery.FunctionDSL.param;
import static org.exist.xquery.FunctionDSL.returnsOpt;
import static org.exist.xquery.modules.lucene.LuceneModule.functionSignature;

/**
 * ft:vector($node, $field): the vector a vector field stored for a node when it was indexed, read back without
 * embedding the node's text again.
 */
public class GetVector extends BasicFunction {

    private static final FunctionParameterSequenceType FS_PARAM_NODE = optParam("node", Type.NODE,
            "A stored node indexed with the vector field.");
    private static final FunctionParameterSequenceType FS_PARAM_FIELD = param("field", Type.STRING,
            "The name of the vector field, as in the index configuration's vector-field/@name.");

    static final FunctionSignature SIGNATURE = functionSignature(
            "vector",
            "Returns the vector that the named vector field stored for $node when it was indexed, as an array of "
                    + "xs:double of the field's dimension: the full-precision vector, also for a field searched by "
                    + "quantized vectors. Returns the empty sequence if $node is empty or not a stored node, or if it "
                    + "has no vector in that field. Use it with the functions of the vector module, e.g. "
                    + "vector:similarity(ft:vector($a, 'embedding'), ft:vector($b, 'embedding')).",
            returnsOpt(Type.ARRAY_ITEM, "The stored vector, an array of xs:double"),
            FS_PARAM_NODE,
            FS_PARAM_FIELD
    );

    public GetVector(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, @Nullable final Sequence contextSequence) throws XPathException {
        if (args[0].isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final NodeValue nodeValue = (NodeValue) args[0].itemAt(0);
        if (nodeValue.getImplementationType() != NodeValue.PERSISTENT_NODE) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final NodeProxy proxy = (NodeProxy) nodeValue;
        final String field = args[1].getStringValue();
        final LuceneIndexWorker index = (LuceneIndexWorker) context.getBroker().getIndexController()
                .getWorkerByIndexId(LuceneIndex.ID);
        try {
            final float[] vector = index.getVectorByExistDocId(proxy.getOwnerDocument().getDocId(), proxy.getNodeId(), field);
            return vector == null ? Sequence.EMPTY_SEQUENCE : toArray(vector);
        } catch (final IOException e) {
            throw new XPathException(this, LuceneModule.EXXQDYFT0002, "Error reading vector field '" + field + "': "
                    + e.getMessage());
        }
    }

    private ArrayType toArray(final float[] vector) throws XPathException {
        final List<Sequence> members = new ArrayList<>(vector.length);
        for (final float v : vector) {
            members.add(new DoubleValue(this, v));
        }
        return new ArrayType(this, context, members);
    }
}
