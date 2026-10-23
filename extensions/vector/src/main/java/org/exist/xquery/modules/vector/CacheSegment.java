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
package org.exist.xquery.modules.vector;

import org.exist.vector.EmbeddingCache;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;

import javax.annotation.Nullable;

import static org.exist.xquery.modules.vector.VectorModule.functionSignature;

/**
 * vector:cache-segment($name, $with-hits) — names the segment of the embedding cache the vectors computed from now
 * on go to, see {@link EmbeddingCache#setSegment}.
 */
public class CacheSegment extends BasicFunction {

  static final FunctionSignature[] signatures = {
      functionSignature("cache-segment",
          "Names the segment of the embedding cache (see the system property exist.vector.embedding-cache) that the "
              + "vectors computed from now on, by any query of this database, go to: a file named $name.vec in each "
              + "model's cache directory, so that a unit of data, such as a volume, gets a file of its own, to be "
              + "distributed with it. With $with-hits true(), the vectors read from the cache go to it too, so that "
              + "it has every vector of the unit. The empty sequence goes back to this process's own file. Only a "
              + "DBA may call it.",
          new FunctionReturnSequenceType(Type.EMPTY_SEQUENCE, Cardinality.EMPTY_SEQUENCE, "the empty sequence"),
          new FunctionParameterSequenceType("name", Type.STRING, Cardinality.ZERO_OR_ONE,
              "the segment's name: letters, digits, '.', '_' and '-'"),
          new FunctionParameterSequenceType("with-hits", Type.BOOLEAN, Cardinality.EXACTLY_ONE,
              "whether the vectors read from the cache go to the segment too"))
  };

  public CacheSegment(final XQueryContext context, final FunctionSignature signature) {
    super(context, signature);
  }

  @Override
  public Sequence eval(final Sequence[] args, @Nullable final Sequence contextSequence) throws XPathException {
    if (!context.getSubject().hasDbaRole()) {
      throw new XPathException(this, ErrorCodes.EXXQDY0009, "Only a DBA may name a segment of the embedding cache");
    }
    try {
      EmbeddingCache.setSegment(args[0].isEmpty() ? null : args[0].getStringValue(), args[1].effectiveBooleanValue());
    } catch (final IllegalArgumentException e) {
      throw new XPathException(this, ErrorCodes.EXXQDY0008, e.getMessage());
    }
    return Sequence.EMPTY_SEQUENCE;
  }
}
