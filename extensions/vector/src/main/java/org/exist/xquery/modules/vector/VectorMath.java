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

/**
 * The arithmetic behind the vector operations, on plain arrays. Callers check that dimensions match and that
 * vectors are not zero where it matters; these methods assume it.
 */
final class VectorMath {

  private VectorMath() {
  }

  static double dot(final double[] a, final double[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) {
      sum += a[i] * b[i];
    }
    return sum;
  }

  static double squareDistance(final double[] a, final double[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) {
      final double d = a[i] - b[i];
      sum += d * d;
    }
    return sum;
  }

  static double norm(final double[] v) {
    return Math.sqrt(dot(v, v));
  }

  /**
   * Cosine similarity, given the norms (so that a bulk caller computes the query's norm once).
   */
  static double cosine(final double[] a, final double normA, final double[] b, final double normB) {
    return dot(a, b) / (normA * normB);
  }

  static double[] normalize(final double[] v, final double norm) {
    final double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      out[i] = v[i] / norm;
    }
    return out;
  }

  /**
   * Adds {@code weight × v} to {@code sum}, in place.
   */
  static void addWeighted(final double[] sum, final double[] v, final double weight) {
    for (int i = 0; i < sum.length; i++) {
      sum[i] += weight * v[i];
    }
  }
}
