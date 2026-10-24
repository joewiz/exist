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

import org.exist.xquery.BasicFunction;
import org.exist.xquery.Dependency;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.NumericValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static org.exist.xquery.FunctionDSL.manyParam;
import static org.exist.xquery.FunctionDSL.optManyParam;
import static org.exist.xquery.FunctionDSL.param;
import static org.exist.xquery.FunctionDSL.returns;
import static org.exist.xquery.FunctionDSL.returnsOpt;
import static org.exist.xquery.modules.vector.VectorModule.functionSignature;

/**
 * Operations on vectors, such as those {@code vector:embed} returns: arrays of numbers.
 *
 * <ul>
 *   <li>{@code vector:cosine($a, $b)}: cosine similarity</li>
 *   <li>{@code vector:euclidean-distance($a, $b)}: Euclidean distance</li>
 *   <li>{@code vector:dot-product($a, $b)}: dot product</li>
 *   <li>{@code vector:magnitude($v)}: Euclidean length</li>
 *   <li>{@code vector:normalize($v)}: the vector scaled to length 1</li>
 *   <li>{@code vector:mean($vectors, $weights?)}: the mean, optionally weighted</li>
 *   <li>{@code vector:dimension($v)}: the number of members</li>
 * </ul>
 *
 * <p>The names follow MarkLogic's {@code vec:} module (cosine, dot-product, magnitude, euclidean-distance, dimension,
 * normalize), which match Lucene's {@code VectorUtil} and the similarity names eXist's vector fields already use.</p>
 */
public class VectorOperations extends BasicFunction {

  private static final String FN_COSINE = "cosine";
  private static final String FN_DOT_PRODUCT = "dot-product";
  private static final String FN_MAGNITUDE = "magnitude";
  private static final String FN_NORMALIZE = "normalize";
  private static final String FN_MEAN = "mean";
  private static final String FN_EUCLIDEAN_DISTANCE = "euclidean-distance";
  private static final String FN_DIMENSION = "dimension";

  private static final FunctionParameterSequenceType FS_PARAM_A = param("a", Type.ARRAY_ITEM,
      "A vector: an array of numbers.");
  private static final FunctionParameterSequenceType FS_PARAM_B = param("b", Type.ARRAY_ITEM,
      "A vector of the same dimension as $a.");
  private static final FunctionParameterSequenceType FS_PARAM_VECTOR = param("vector", Type.ARRAY_ITEM,
      "A vector: an array of numbers.");
  private static final FunctionParameterSequenceType FS_PARAM_VECTORS = optManyParam("vectors", Type.ARRAY_ITEM,
      "Vectors of the same dimension.");
  private static final FunctionParameterSequenceType FS_PARAM_WEIGHTS = manyParam("weights", Type.DOUBLE,
      "One non-negative weight per vector, in the same order; they need not sum to 1, but must not all be 0.");

  static final FunctionSignature[] signatures = {
      functionSignature(FN_COSINE,
          "Returns the cosine similarity of two vectors: 1 when they point the same way, 0 when they are "
              + "orthogonal, -1 when opposite. Raises vector:EXVECTOR0007 if either vector has zero length.",
          returns(Type.DOUBLE, "The cosine similarity"),
          FS_PARAM_A, FS_PARAM_B),
      functionSignature(FN_DOT_PRODUCT,
          "Returns the dot product of two vectors. For vectors of length 1, as most embedding models return, "
              + "it equals their cosine similarity.",
          returns(Type.DOUBLE, "The dot product"),
          FS_PARAM_A, FS_PARAM_B),
      functionSignature(FN_MAGNITUDE,
          "Returns the Euclidean length of a vector.",
          returns(Type.DOUBLE, "The length"),
          FS_PARAM_VECTOR),
      functionSignature(FN_NORMALIZE,
          "Returns the vector scaled to length 1. Raises vector:EXVECTOR0007 if it has zero length.",
          returns(Type.ARRAY_ITEM, "The normalized vector, an array of xs:double"),
          FS_PARAM_VECTOR),
      functionSignature(FN_MEAN,
          "Returns the mean of the vectors, or the empty sequence if there are none. The result is not "
              + "normalized; apply vector:normalize to compare it by direction.",
          returnsOpt(Type.ARRAY_ITEM, "The mean vector, an array of xs:double"),
          FS_PARAM_VECTORS),
      functionSignature(FN_MEAN,
          "Returns the weighted mean of the vectors, or the empty sequence if there are none: the sum of each "
              + "vector times its weight, divided by the sum of the weights. Raises vector:EXVECTOR0008 if the "
              + "number of weights differs from the number of vectors, a weight is negative, or all are 0.",
          returnsOpt(Type.ARRAY_ITEM, "The weighted mean vector, an array of xs:double"),
          FS_PARAM_VECTORS, FS_PARAM_WEIGHTS),
      functionSignature(FN_EUCLIDEAN_DISTANCE,
          "Returns the Euclidean distance between two vectors: 0 for equal vectors. For vectors of length 1 it "
              + "falls as their cosine similarity rises: distance² = 2 − 2 × cosine.",
          returns(Type.DOUBLE, "The distance"),
          FS_PARAM_A, FS_PARAM_B),
      functionSignature(FN_DIMENSION,
          "Returns the dimension of a vector: the number of its members.",
          returns(Type.INTEGER, "The dimension"),
          FS_PARAM_VECTOR)
  };

  public VectorOperations(final XQueryContext context, final FunctionSignature signature) {
    super(context, signature);
  }

  /**
   * Only what the arguments depend on, as for {@link Embed}: these functions read nothing else.
   */
  @Override
  public int getDependencies() {
    int dependencies = Dependency.NO_DEPENDENCY;
    for (int i = 0; i < getArgumentCount(); i++) {
      dependencies |= getArgument(i).getDependencies();
    }
    return dependencies;
  }

  @Override
  public Sequence eval(final Sequence[] args, @Nullable final Sequence contextSequence) throws XPathException {
    final String name = getSignature().getName().getLocalPart();
    return switch (name) {
      case FN_COSINE -> cosine(args);
      case FN_DOT_PRODUCT -> {
        final double[] a = toVector(args[0]);
        final double[] b = toVector(args[1]);
        checkDimensions(a, b);
        yield new DoubleValue(this, VectorMath.dot(a, b));
      }
      case FN_MAGNITUDE -> new DoubleValue(this, VectorMath.norm(toVector(args[0])));
      case FN_NORMALIZE -> {
        final double[] v = toVector(args[0]);
        yield toArray(VectorMath.normalize(v, nonZeroNorm(v)));
      }
      case FN_MEAN -> mean(args);
      case FN_EUCLIDEAN_DISTANCE -> {
        final double[] a = toVector(args[0]);
        final double[] b = toVector(args[1]);
        checkDimensions(a, b);
        yield new DoubleValue(this, Math.sqrt(VectorMath.squareDistance(a, b)));
      }
      case FN_DIMENSION -> new IntegerValue(this, toVector(args[0]).length);
      default -> throw new XPathException(this, "Unknown function: " + getSignature().getName());
    };
  }

  private Sequence cosine(final Sequence[] args) throws XPathException {
    final double[] a = toVector(args[0]);
    final double[] b = toVector(args[1]);
    checkDimensions(a, b);
    return new DoubleValue(this, VectorMath.cosine(a, nonZeroNorm(a), b, nonZeroNorm(b)));
  }

  private Sequence mean(final Sequence[] args) throws XPathException {
    final Sequence vectors = args[0];
    final int count = vectors.getItemCount();
    if (count == 0) {
      return Sequence.EMPTY_SEQUENCE;
    }
    final double[] weights = args.length > 1 ? toWeights(args[1], count) : null;
    double[] sum = null;
    double totalWeight = 0;
    int index = 0;
    for (final SequenceIterator i = vectors.iterate(); i.hasNext(); index++) {
      final double[] v = vectorOf(i.nextItem());
      if (sum == null) {
        sum = new double[v.length];
      } else {
        checkDimensions(sum, v);
      }
      final double weight = weights == null ? 1 : weights[index];
      VectorMath.addWeighted(sum, v, weight);
      totalWeight += weight;
    }
    for (int d = 0; d < sum.length; d++) {
      sum[d] /= totalWeight;
    }
    return toArray(sum);
  }

  private double[] toWeights(final Sequence weightsArg, final int count) throws XPathException {
    if (weightsArg.getItemCount() != count) {
      throw new XPathException(this, VectorModule.EXVECTOR0008,
          "Expected one weight per vector: " + count + " vectors, " + weightsArg.getItemCount() + " weights");
    }
    final double[] weights = new double[count];
    boolean anyPositive = false;
    int index = 0;
    for (final SequenceIterator i = weightsArg.iterate(); i.hasNext(); index++) {
      final double weight = ((NumericValue) i.nextItem()).getDouble();
      if (weight < 0 || Double.isNaN(weight)) {
        throw new XPathException(this, VectorModule.EXVECTOR0008, "Weight " + (index + 1) + " is " + weight
            + "; weights must not be negative");
      }
      anyPositive |= weight > 0;
      weights[index] = weight;
    }
    if (!anyPositive) {
      throw new XPathException(this, VectorModule.EXVECTOR0008, "All weights are 0");
    }
    return weights;
  }

  private double[] toVector(final Sequence arg) throws XPathException {
    return vectorOf(arg.itemAt(0));
  }

  private double[] vectorOf(final Item item) throws XPathException {
    if (!(item instanceof ArrayType array) || array.getSize() == 0) {
      throw new XPathException(this, VectorModule.EXVECTOR0006,
          "Expected a vector, a non-empty array of numbers, but got " + Type.getTypeName(item.getType()));
    }
    final int size = array.getSize();
    final double[] v = new double[size];
    for (int d = 0; d < size; d++) {
      final Sequence member = array.get(d);
      if (member.getItemCount() != 1 || !(member.itemAt(0) instanceof NumericValue number)) {
        throw new XPathException(this, VectorModule.EXVECTOR0006,
            "Member " + (d + 1) + " of the vector is not a single number");
      }
      v[d] = number.getDouble();
    }
    return v;
  }

  private void checkDimensions(final double[] a, final double[] b) throws XPathException {
    if (a.length != b.length) {
      throw new XPathException(this, VectorModule.EXVECTOR0005,
          "Vectors of different dimensions: " + a.length + " and " + b.length);
    }
  }

  private double nonZeroNorm(final double[] v) throws XPathException {
    final double norm = VectorMath.norm(v);
    if (norm == 0) {
      throw new XPathException(this, VectorModule.EXVECTOR0007, "The vector has zero length, so it has no direction");
    }
    return norm;
  }

  private ArrayType toArray(final double[] v) throws XPathException {
    final List<Sequence> members = new ArrayList<>(v.length);
    for (final double d : v) {
      members.add(new DoubleValue(this, d));
    }
    return new ArrayType(this, context, members);
  }
}
