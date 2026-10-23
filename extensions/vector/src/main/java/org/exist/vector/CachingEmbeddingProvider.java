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
package org.exist.vector;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A provider that reads the vectors of texts it has seen from an {@link EmbeddingCache}, and asks the model it wraps
 * only for the others, adding their vectors to the cache. Tokenizing and everything else is the wrapped provider's.
 */
final class CachingEmbeddingProvider implements VectorEmbeddingProvider {

  private final VectorEmbeddingProvider provider;
  private final EmbeddingCache cache;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();

  CachingEmbeddingProvider(final VectorEmbeddingProvider provider, final EmbeddingCache cache) {
    this.provider = provider;
    this.cache = cache;
  }

  @Override
  public int getDimension() {
    return provider.getDimension();
  }

  @Override
  public float[] embed(final String text) {
    return embed(text, false);
  }

  @Override
  public float[] embed(final String text, final boolean forQuery) {
    final float[] cached = cache.get(text);
    if (cached != null) {
      hits.incrementAndGet();
      return cached;
    }
    misses.incrementAndGet();
    final float[] vector = provider.embed(text, forQuery);
    cache.put(text, vector);
    return vector;
  }

  @Override
  public int getMaxTokens() {
    return provider.getMaxTokens();
  }

  @Nullable
  @Override
  public int[] tokenEnds(final String text) {
    return provider.tokenEnds(text);
  }

  @Override
  public List<float[]> embedAll(final List<String> texts) {
    final List<float[]> vectors = new ArrayList<>(texts.size());
    final List<String> missing = new ArrayList<>();
    final List<Integer> missingAt = new ArrayList<>();
    for (int i = 0; i < texts.size(); i++) {
      final float[] cached = cache.get(texts.get(i));
      vectors.add(cached);
      if (cached == null) {
        missing.add(texts.get(i));
        missingAt.add(i);
      }
    }
    hits.addAndGet(texts.size() - (long) missing.size());
    misses.addAndGet(missing.size());
    if (!missing.isEmpty()) {
      // the others in one batch, as the model computes them fastest
      final List<float[]> computed = provider.embedAll(missing);
      for (int i = 0; i < missing.size(); i++) {
        final float[] vector = computed.get(i);
        cache.put(missing.get(i), vector);
        vectors.set(missingAt.get(i), vector);
      }
    }
    return vectors;
  }

  /** The texts whose vectors were read from the cache. */
  long getHits() {
    return hits.get();
  }

  /** The texts whose vectors the model computed. */
  long getMisses() {
    return misses.get();
  }

  @Override
  public void close() {
    provider.close();
  }
}
