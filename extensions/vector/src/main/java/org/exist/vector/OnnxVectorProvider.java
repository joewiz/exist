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

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ai.onnxruntime.OnnxValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ONNX-based vector embedding provider. Uses HuggingFace tokenizer and ONNX Runtime.
 */
public final class OnnxVectorProvider implements VectorEmbeddingProvider {

  private static final Logger LOG = LogManager.getLogger(OnnxVectorProvider.class);

  private static final String MODEL_FILE = "model.onnx";
  private static final String TOKENIZER_FILE = "tokenizer.json";

  private static final String INPUT_IDS = "input_ids";
  private static final String ATTENTION_MASK = "attention_mask";
  private static final String TOKEN_TYPE_IDS = "token_type_ids";
  private static final String LAST_HIDDEN_STATE = "last_hidden_state";
  private static final String SENTENCE_EMBEDDING = "sentence_embedding";
  private static final String TOKEN_EMBEDDINGS = "token_embeddings";
  private static final int BATCH_SIZE = 32;

  private final OrtEnvironment env;
  private final OrtSession session;
  private final HuggingFaceTokenizer tokenizer;
  // the same tokenizer, without truncation, padding or special tokens, to measure and cut texts into chunks
  @Nullable private final HuggingFaceTokenizer spanTokenizer;
  private final int dimension;
  private final String outputName;
  private final String tokenTypeIdsInputName;

  // Guards session/tokenizer against a concurrent close(): VectorEmbeddingService caches
  // providers and can evict (and close) one from a different thread than whichever is
  // mid-embed() on it (VectorEmbeddingService#evict, VectorEmbeddingProvider#close). embed()
  // holds the read lock for its duration; close() takes the write lock, so it can't run
  // until every in-flight embed() has finished, and any embed() that starts afterward sees
  // `closed` and returns null instead of touching a freed session/tokenizer.
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private volatile boolean closed = false;
  /** The model's files, for its fingerprint. */
  private final Path[] modelFiles;
  @Nullable private volatile String fingerprint;

  private OnnxVectorProvider(final OrtEnvironment env, final OrtSession session,
      final HuggingFaceTokenizer tokenizer, @Nullable final HuggingFaceTokenizer spanTokenizer, final int dimension, final String outputName,
      final String tokenTypeIdsInputName, final Path... modelFiles) {
    this.modelFiles = modelFiles;
    this.env = env;
    this.session = session;
    this.tokenizer = tokenizer;
    this.spanTokenizer = spanTokenizer;
    this.dimension = dimension;
    this.outputName = outputName;
    this.tokenTypeIdsInputName = tokenTypeIdsInputName;
  }

  @Override
  public int getDimension() {
    return dimension;
  }

  /**
   * A hash of the model's files, computed once: the model is the same, whatever its name or path, only if its files
   * are.
   */
  @Nullable
  @Override
  public String getFingerprint() {
    String computed = fingerprint;
    if (computed == null) {
      try {
        computed = ModelFingerprint.of(modelFiles);
        fingerprint = computed;
      } catch (final IOException e) {
        LOG.warn("Could not compute the fingerprint of the model's files: {}", e.getMessage());
        return null;
      }
    }
    return computed;
  }

  /**
   * Closes the ONNX session and tokenizer, blocking until any in-flight {@link #embed}
   * call finishes. Does not close {@link #env}: it is a process-wide singleton
   * ({@link OrtEnvironment#getEnvironment()}) shared with any other session in the JVM,
   * not owned by this provider.
   */
  @Override
  public void close() {
    lifecycleLock.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      closeQuietly(session, tokenizer);
      if (spanTokenizer != null) {
        spanTokenizer.close();
      }
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  /**
   * Closes whichever of session/tokenizer is non-null, logging rather than throwing on
   * failure. Shared by {@link #close()} and {@link #create}'s cleanup-on-failure path.
   */
  private static void closeQuietly(@Nullable final OrtSession session, @Nullable final HuggingFaceTokenizer tokenizer) {
    if (session != null) {
      try {
        session.close();
      } catch (final Exception e) {
        LOG.warn("Failed to close ONNX session: {}", e.getMessage());
      }
    }
    if (tokenizer != null) {
      try {
        tokenizer.close();
      } catch (final Exception e) {
        LOG.warn("Failed to close tokenizer: {}", e.getMessage());
      }
    }
  }

  @Override
  @Nullable
  public float[] embed(final String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    lifecycleLock.readLock().lock();
    try {
      if (closed) {
        return null;
      }
      final Encoding encoding = tokenizer.encode(text);
      if (encoding == null) {
        return null;
      }
      final long[] ids = encoding.getIds();
      if (ids == null || ids.length == 0) {
        return null;
      }

      final Map<String, OnnxTensor> inputs = prepareInputs(encoding);
      try (Result result = session.run(inputs)) {
        if (SENTENCE_EMBEDDING.equals(outputName)) {
          return extractSentenceEmbedding(result);
        }
        final long[] attnMask = encoding.getAttentionMask();
        final long[] mask = attnMask != null ? attnMask : createAttentionMask(ids.length);
        return meanPool(result, ids.length, mask);
      } finally {
        inputs.values().forEach(OnnxTensor::close);
      }
    } catch (final Exception e) {
      LOG.debug("Embedding failed: {}", e.getMessage());
      return null;
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  @Override
  public int getMaxTokens() {
    return tokenizer.getMaxLength() > 0 ? tokenizer.getMaxLength() : 0;
  }

  @Override
  @Nullable
  public int[] tokenEnds(final String text) {
    if (spanTokenizer == null || text == null) {
      return null;
    }
    lifecycleLock.readLock().lock();
    try {
      if (closed) {
        return null;
      }
      final Encoding encoding = spanTokenizer.encode(text, false, false);
      final ai.djl.huggingface.tokenizers.jni.CharSpan[] spans = encoding.getCharTokenSpans();
      final int[] ends = new int[spans.length];
      final boolean supplementary = text.codePointCount(0, text.length()) != text.length();
      int n = 0;
      for (final ai.djl.huggingface.tokenizers.jni.CharSpan span : spans) {
        if (span == null) {
          continue;
        }
        // the spans count code points, the callers UTF-16 code units
        ends[n++] = supplementary ? text.offsetByCodePoints(0, span.getEnd()) : span.getEnd();
      }
      return n == ends.length ? ends : java.util.Arrays.copyOf(ends, n);
    } catch (final Exception e) {
      LOG.debug("Tokenizing failed: {}", e.getMessage());
      return null;
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  @Override
  public List<float[]> embedAll(final List<String> texts) {
    final List<float[]> vectors = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
      vectors.addAll(embedBatch(texts.subList(from, Math.min(texts.size(), from + BATCH_SIZE))));
    }
    return vectors;
  }

  /**
   * Embeds the texts in one run of the model, each padded to the longest of them.
   */
  private List<float[]> embedBatch(final List<String> texts) {
    final List<float[]> vectors = new ArrayList<>(texts.size());
    lifecycleLock.readLock().lock();
    try {
      if (closed) {
        texts.forEach(text -> vectors.add(null));
        return vectors;
      }
      vectors.addAll(runBatch(tokenizer.batchEncode(texts)));
    } catch (final Exception e) {
      LOG.debug("Batch embedding failed, embedding one text at a time: {}", e.getMessage());
      vectors.clear();
    } finally {
      lifecycleLock.readLock().unlock();
    }
    if (vectors.isEmpty()) {
      texts.forEach(text -> vectors.add(embed(text)));
    }
    return vectors;
  }

  /**
   * Runs the model once over the encodings, each padded to the longest of them, and pools each one's vector.
   */
  private List<float[]> runBatch(final Encoding[] encodings) throws Exception {
    int length = 0;
    for (final Encoding encoding : encodings) {
      length = Math.max(length, encoding.getIds().length);
    }
    final int n = encodings.length;
    final long[][] ids = new long[n][length];
    final long[][] mask = new long[n][length];
    for (int i = 0; i < n; i++) {
      final long[] encodingIds = encodings[i].getIds();
      final long[] encodingMask = encodings[i].getAttentionMask() != null ? encodings[i].getAttentionMask() : createAttentionMask(encodingIds.length);
      System.arraycopy(encodingIds, 0, ids[i], 0, encodingIds.length);
      System.arraycopy(encodingMask, 0, mask[i], 0, encodingMask.length);
    }
    final Map<String, OnnxTensor> inputs = new HashMap<>();
    try {
      inputs.put(INPUT_IDS, OnnxTensor.createTensor(env, ids));
      inputs.put(ATTENTION_MASK, OnnxTensor.createTensor(env, mask));
      if (tokenTypeIdsInputName != null) {
        inputs.put(tokenTypeIdsInputName, OnnxTensor.createTensor(env, new long[n][length]));
      }
      try (Result result = session.run(inputs)) {
        return SENTENCE_EMBEDDING.equals(outputName) ? sentenceEmbeddings(result, n) : pooledEmbeddings(result, n, length, mask);
      }
    } finally {
      inputs.values().forEach(OnnxTensor::close);
    }
  }

  private List<float[]> sentenceEmbeddings(final Result result, final int n) throws Exception {
    final float[][] data = (float[][]) ((OnnxTensor) result.get(0)).getValue();
    final List<float[]> vectors = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      vectors.add(data[i].length == dimension ? data[i] : null);
    }
    return vectors;
  }

  private List<float[]> pooledEmbeddings(final Result result, final int n, final int length, final long[][] mask) throws Exception {
    final float[][][] hidden = (float[][][]) findHiddenStateTensor(result).getValue();
    final List<float[]> vectors = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      vectors.add(hidden[i].length > 0 && hidden[i][0].length == dimension ? computeMeanPool(hidden[i], length, mask[i]) : null);
    }
    return vectors;
  }

  private Map<String, OnnxTensor> prepareInputs(final Encoding encoding) throws ai.onnxruntime.OrtException {
    final long[] ids = encoding.getIds();
    final long[] attnMask = encoding.getAttentionMask();
    final long[][] attentionMask = attnMask != null ? new long[][] { attnMask } : new long[][] { createAttentionMask(ids.length) };

    final Map<String, OnnxTensor> inputs = new HashMap<>();
    inputs.put(INPUT_IDS, OnnxTensor.createTensor(env, new long[][] { ids }));
    inputs.put(ATTENTION_MASK, OnnxTensor.createTensor(env, attentionMask));
    if (tokenTypeIdsInputName != null) {
      inputs.put(tokenTypeIdsInputName, OnnxTensor.createTensor(env, new long[][] { createTokenTypeIds(ids.length) }));
    }
    return inputs;
  }

  private float[] extractSentenceEmbedding(final Result result) throws Exception {
    // The Result owns its output tensors and closes them with embed()'s try-with-resources;
    // closing the tensor here too makes onnxruntime log "Closing an already closed tensor."
    // on every call. getValue() copies into a Java array, so it is safe to read from here.
    final OnnxTensor tensor = (OnnxTensor) result.get(0);
    final float[][] data = (float[][]) tensor.getValue();
    if (data != null && data.length > 0 && data[0].length == dimension) {
      return data[0];
    }
    return null;
  }

  private static long[] createAttentionMask(final int len) {
    final long[] mask = new long[len];
    for (int i = 0; i < len; i++) {
      mask[i] = 1;
    }
    return mask;
  }

  private static long[] createTokenTypeIds(final int len) {
    final long[] ids = new long[len];
    for (int i = 0; i < len; i++) {
      ids[i] = 0;
    }
    return ids;
  }

  private float[] meanPool(final Result result, final int seqLen, final long[] attentionMask) throws Exception {
    // Owned and closed by the Result, see extractSentenceEmbedding.
    final OnnxTensor tensor = findHiddenStateTensor(result);
    final float[][][] hidden = (float[][][]) tensor.getValue();
    if (hidden == null || hidden.length == 0 || hidden[0].length == 0 || hidden[0][0].length != dimension) {
      return null;
    }
    return computeMeanPool(hidden[0], seqLen, attentionMask);
  }

  private OnnxTensor findHiddenStateTensor(final Result result) {
    for (final Map.Entry<String, OnnxValue> entry : result) {
      if (LAST_HIDDEN_STATE.equals(entry.getKey()) || TOKEN_EMBEDDINGS.equals(entry.getKey())) {
        return (OnnxTensor) entry.getValue();
      }
    }
    return (OnnxTensor) result.get(0);
  }

  @Nullable
  private float[] computeMeanPool(final float[][] tokenEmbeddings, final int seqLen, final long[] attentionMask) {
    final float[] sum = new float[dimension];
    int count = 0;
    for (int t = 0; t < seqLen && t < tokenEmbeddings.length; t++) {
      if (t < attentionMask.length && attentionMask[t] == 1) {
        for (int d = 0; d < dimension; d++) {
          sum[d] += tokenEmbeddings[t][d];
        }
        count++;
      }
    }
    if (count == 0) {
      return null;
    }
    for (int d = 0; d < dimension; d++) {
      sum[d] /= count;
    }
    return sum;
  }

  /**
   * Creates an ONNX provider from a model directory. Expects model.onnx and tokenizer.json.
   *
   * @param modelPath path to the model directory
   * @param dimension expected embedding dimension
   * @return provider or null if loading fails
   */
  @Nullable
  public static OnnxVectorProvider create(@Nonnull final Path modelPath, final int dimension) {
    final Path modelFile = modelPath.resolve(MODEL_FILE);
    final Path tokenizerFile = modelPath.resolve(TOKENIZER_FILE);
    if (!Files.isRegularFile(modelFile) || !Files.isRegularFile(tokenizerFile)) {
      LOG.warn("Model files not found: {} or {}", modelFile, tokenizerFile);
      return null;
    }

    // tokenizer/session are closed here on any failure after they're acquired -- once
    // construction succeeds, close() (called via VectorEmbeddingService#evict) takes over.
    // env is a process-wide singleton (OrtEnvironment#getEnvironment()) and is never closed.
    HuggingFaceTokenizer tokenizer = null;
    HuggingFaceTokenizer spanTokenizer = null;
    OrtSession session = null;
    try {
      tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile);
      spanTokenizer = createSpanTokenizer(tokenizerFile);
      final OrtEnvironment env = OrtEnvironment.getEnvironment();
      session = createSession(env, modelFile);
      final String tokenTypeIdsInputName = resolveTokenTypeIdsInput(session);
      final String outputName = resolveOutputName(session);

      return new OnnxVectorProvider(env, session, tokenizer, spanTokenizer, dimension, outputName, tokenTypeIdsInputName,
          modelFile, tokenizerFile);
    } catch (final Exception e) {
      LOG.warn("Failed to create OnnxVectorProvider: {}", e.getMessage());
      closeQuietly(session, tokenizer);
      if (spanTokenizer != null) {
        spanTokenizer.close();
      }
      return null;
    }
  }

  @Nullable
  private static HuggingFaceTokenizer createSpanTokenizer(final Path tokenizerFile) {
    try {
      return HuggingFaceTokenizer.newInstance(tokenizerFile,
          Map.of("truncation", "false", "padding", "false", "addSpecialTokens", "false"));
    } catch (final Exception e) {
      LOG.warn("Failed to create a tokenizer for chunking, texts will not be cut into chunks: {}", e.getMessage());
      return null;
    }
  }

  private static OrtSession createSession(final OrtEnvironment env, final Path modelFile) throws Exception {
    try (final OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      try {
        opts.addCUDA(0);
        LOG.info("Using CUDA execution provider for embedding (device 0)");
      } catch (final Throwable t) {
        LOG.debug("CUDA not available, using CPU: {}", t.getMessage());
      }
      return env.createSession(modelFile.toString(), opts);
    }
  }

  @Nullable
  private static String resolveTokenTypeIdsInput(final OrtSession session) throws Exception {
    final Set<String> inputNames = session.getInputNames();
    if (LOG.isDebugEnabled()) {
      LOG.debug("ONNX model input names: {}", inputNames);
    }
    for (final String name : inputNames) {
      if (TOKEN_TYPE_IDS.equals(name) || (name != null && name.endsWith("token_type_ids"))) {
        return name;
      }
    }
    return inputNames.size() >= 3 ? TOKEN_TYPE_IDS : null;
  }

  private static String resolveOutputName(final OrtSession session) throws Exception {
    for (final String name : session.getOutputNames()) {
      if (SENTENCE_EMBEDDING.equals(name) || TOKEN_EMBEDDINGS.equals(name)) {
        return name;
      }
      if (LAST_HIDDEN_STATE.equals(name)) {
        return LAST_HIDDEN_STATE;
      }
    }
    return SENTENCE_EMBEDDING;
  }
}
