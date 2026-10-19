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

/**
 * Provider of vector embeddings from text. Implementations may use ONNX, HTTP APIs, etc.
 */
public interface VectorEmbeddingProvider {

  /**
   * Returns the embedding dimension for this provider.
   *
   * @return dimension (e.g. 384 for all-MiniLM-L6-v2)
   */
  int getDimension();

  /**
   * Embeds the given text into a dense float vector.
   *
   * @param text input text (non-null, may be empty)
   * @return embedding vector of length {@link #getDimension()}, or null if embedding failed
   */
  float[] embed(String text);

  /**
   * Embeds the given text, with optional hint for query vs document context.
   * Some APIs (e.g. Cohere) use different input types for search queries vs documents.
   * Default implementation ignores the hint and delegates to {@link #embed(String)}.
   *
   * @param text input text (non-null, may be empty)
   * @param forQuery true when embedding a search query (e.g. vector:embed for ft:query-vector)
   * @return embedding vector of length {@link #getDimension()}, or null if embedding failed
   */
  default float[] embed(String text, boolean forQuery) {
    return embed(text);
  }

  /**
   * Returns the most tokens the model reads from one text, including any special tokens it adds;
   * the rest of a longer text is ignored. Used to cut texts into chunks the model reads in full.
   *
   * @return the maximum number of tokens, or 0 if unknown
   */
  default int getMaxTokens() {
    return 0;
  }

  /**
   * Tokenizes the text as the model does, but without truncating it and without special tokens.
   *
   * @param text input text
   * @return the end offset in {@code text} (in UTF-16 code units) of each token, in order, or null if
   *     this provider can't tokenize
   */
  @Nullable
  default int[] tokenEnds(final String text) {
    return null;
  }

  /**
   * Embeds several texts, as one batch where the provider supports it, which is much faster than
   * embedding them one at a time.
   *
   * @param texts input texts
   * @return one vector per text, in order, an element being null where embedding that text failed
   */
  default List<float[]> embedAll(final List<String> texts) {
    final List<float[]> vectors = new ArrayList<>(texts.size());
    for (final String text : texts) {
      vectors.add(embed(text));
    }
    return vectors;
  }

  /**
   * Releases any resources held by this provider (e.g. native model handles, an HTTP
   * client's connection pool). Called when a provider is evicted from
   * {@link VectorEmbeddingService}'s cache; implementations that hold resources should
   * guard against a concurrent {@link #embed} call still in flight on another thread.
   * Default implementation does nothing, for providers that hold nothing beyond ordinary,
   * GC-managed heap objects.
   */
  default void close() {
    // intentionally empty: see javadoc above
  }
}
