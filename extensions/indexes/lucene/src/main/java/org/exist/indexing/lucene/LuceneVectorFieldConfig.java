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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.ReindexScope;
import org.exist.numbering.NodeId;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.vector.VectorStore;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.w3c.dom.Element;

import org.exist.storage.txn.Txn;
import org.exist.vector.HttpVectorProvider;
import org.exist.vector.VectorEmbeddingProvider;
import org.exist.vector.VectorEmbeddingService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuration for a vector field definition nested inside a Lucene index configuration element.
 * Stores dense float vectors for KNN search. Supports base64 (default) or text encoding.
 */
public class LuceneVectorFieldConfig extends AbstractFieldConfig {

    private static final Logger LOG = LogManager.getLogger(LuceneVectorFieldConfig.class);

    private static final String ATTR_NAME = "name";
    private static final String ATTR_DIMENSION = "dimension";
    private static final String ATTR_SIMILARITY = "similarity";
    private static final String ATTR_ENCODING = "encoding";
    private static final String ATTR_EMBEDDING = "embedding";
    private static final String ATTR_MODEL = "model";
    private static final String ATTR_MODEL_PATH = "model-path";

    private static final String ENCODING_BASE64 = "base64";
    private static final String ENCODING_TEXT = "text";
    private static final String EMBEDDING_LOCAL = "local";
    private static final String EMBEDDING_HTTP = "http";
    private static final String VECTOR_STORE_DB = "db";
    private static final String ATTR_QUANTIZE = "quantize";
    private static final String QUANTIZE_INT8 = "int8";
    private static final String ATTR_CHUNK = "chunk";
    private static final String ATTR_CHUNK_SIZE = "chunk-size";
    private static final String ATTR_CHUNK_OVERLAP = "chunk-overlap";
    private static final String ATTR_CHUNK_UNITS = "chunk-units";
    private static final String ATTR_CHUNK_CONTEXT = "chunk-context";
    private static final String CHUNK_TOKENS = "tokens";
    private static final String DEFAULT_CHUNK_UNITS = "node()";
    /** The tokens a model is assumed to read when its provider doesn't say. */
    private static final int DEFAULT_MAX_TOKENS = 128;
    /** The special tokens a model adds to a text, e.g. [CLS] and [SEP]. */
    private static final int SPECIAL_TOKENS = 2;
    /** The fewest tokens left for a chunk's own text after its context header. */
    private static final int MIN_CHUNK_TOKENS = 16;

    private static final Pattern WS = Pattern.compile("\\s+");

    /** Vector field name from config. */
    protected final String fieldName;
    /** Vector dimension (1–1024). */
    protected final int dimension;
    /** Similarity function for KNN (cosine, euclidean, dot_product). */
    protected final VectorSimilarityFunction similarity;
    /** True if encoding is base64, false for text. */
    protected final boolean useBase64;
    /** True when embedding="local" or embedding="http" (compute via ONNX or HTTP API). */
    protected final boolean embeddingLocal;
    /** Model ID when embedding=local (e.g. all-MiniLM-L6-v2). */
    @Nullable protected final String modelId;
    /** Model path or HuggingFace URL when embedding=local. */
    @Nullable protected final String modelPathOrUrl;
    /** Parent Lucene config (for vector-store setting). */
    private final LuceneConfig luceneConfig;

    /** Whether each node's text is cut into chunks, each embedded and indexed on its own, see {@link #chunk}. */
    private final boolean chunked;
    /** The most tokens of a chunk including its context header, or 0 for as many as the model reads. */
    private final int chunkSize;
    private final int chunkOverlap;
    @Nullable private final String chunkUnitsCode;
    @Nullable private final String chunkContextCode;
    @Nullable private CompiledXQuery chunkUnitsQuery = null;
    @Nullable private CompiledXQuery chunkContextQuery = null;

    /** Per-invocation context for storing computed vectors in vector.dbx. */
    private static final ThreadLocal<StoreContext> STORE_CONTEXT = new ThreadLocal<>();

    /**
     * Creates a vector field config from the given XML element.
     *
     * @param config parent Lucene config
     * @param configElement the vector-field element
     * @param namespaces namespace map for expression resolution
     * @throws org.exist.util.DatabaseConfigurationException if config is invalid
     */
    public LuceneVectorFieldConfig(final LuceneConfig config, final Element configElement,
            final Map<String, String> namespaces) throws org.exist.util.DatabaseConfigurationException {
        super(config, configElement, namespaces);
        luceneConfig = config;

        fieldName = configElement.getAttribute(ATTR_NAME);
        if (fieldName.isEmpty()) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field: attribute 'name' is required");
        }

        dimension = parseDimension(configElement);
        similarity = parseSimilarity(configElement.getAttribute(ATTR_SIMILARITY));
        useBase64 = parseEncoding(configElement);

        final String embStr = configElement.getAttribute(ATTR_EMBEDDING);
        final boolean isLocal = EMBEDDING_LOCAL.equalsIgnoreCase(embStr);
        final boolean isHttp = EMBEDDING_HTTP.equalsIgnoreCase(embStr);
        embeddingLocal = isLocal || isHttp;
        if (embeddingLocal) {
            final String[] embCfg = parseEmbeddingConfig(configElement, embStr, isHttp);
            modelId = embCfg[0];
            modelPathOrUrl = embCfg[1];
        } else {
            modelId = null;
            modelPathOrUrl = null;
        }

        final String quantize = configElement.getAttribute(ATTR_QUANTIZE);
        if (QUANTIZE_INT8.equals(quantize)) {
            LuceneIndex.INT8_VECTOR_FIELDS.add(fieldName);
        } else if (!quantize.isEmpty() && !"none".equals(quantize)) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': attribute 'quantize' must be 'int8' or 'none', got '" + quantize + "'");
        }

        final String chunk = configElement.getAttribute(ATTR_CHUNK);
        chunked = CHUNK_TOKENS.equals(chunk);
        if (!chunk.isEmpty() && !chunked) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': attribute 'chunk' must be 'tokens', got '" + chunk + "'");
        }
        if (chunked && !embeddingLocal) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': chunk=\"tokens\" requires embedding=\"local\" or \"http\"");
        }
        chunkSize = parseNonNegative(configElement, ATTR_CHUNK_SIZE);
        chunkOverlap = parseNonNegative(configElement, ATTR_CHUNK_OVERLAP);
        if (chunked) {
            final String units = configElement.getAttribute(ATTR_CHUNK_UNITS);
            chunkUnitsCode = prolog + (units.isEmpty() ? DEFAULT_CHUNK_UNITS : units);
            final String context = configElement.getAttribute(ATTR_CHUNK_CONTEXT);
            chunkContextCode = context.isEmpty() ? null : prolog + context;
        } else {
            chunkUnitsCode = null;
            chunkContextCode = null;
        }
    }

    private int parseNonNegative(final Element el, final String attr) throws org.exist.util.DatabaseConfigurationException {
        final String value = el.getAttribute(attr);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            final int n = Integer.parseInt(value.trim());
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (final NumberFormatException e) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': attribute '" + attr + "' must be a non-negative integer, got '" + value + "'");
        }
    }

    /**
     * @return whether each node's text is cut into chunks, each embedded and indexed on its own
     */
    public boolean isChunked() {
        return chunked;
    }

    /**
     * The chunks of a node's text, as {@link VectorChunker} cuts them, with the text to embed for each: the chunk's
     * text after the node's context header, if the field has one.
     *
     * @param chunks the chunks
     * @param embedTexts the text to embed for each chunk
     */
    public record ChunkedText(List<VectorChunker.Chunk> chunks, List<String> embedTexts) {
    }

    /**
     * Cuts the text of a node into chunks the field's model reads in full: the string values of the items of the
     * {@code chunk-units} expression (by default the node's children), packed and split by {@link VectorChunker}.
     *
     * @param broker the broker
     * @param node the indexed node
     * @return the chunks, none if the node has no text or the model is not available
     */
    public ChunkedText chunk(final DBBroker broker, final NodeProxy node) {
        final VectorEmbeddingProvider provider = getEmbeddingProvider();
        if (!chunked || provider == null) {
            return new ChunkedText(List.of(), List.of());
        }
        try {
            chunkUnitsQuery = chunkUnitsQuery != null ? chunkUnitsQuery : compile(broker, chunkUnitsCode);
            if (chunkContextCode != null && chunkContextQuery == null) {
                chunkContextQuery = compile(broker, chunkContextCode);
            }
            if (chunkUnitsQuery == null) {
                return new ChunkedText(List.of(), List.of());
            }
            final List<String> units = evaluateStrings(broker, chunkUnitsQuery, node);
            final String header = chunkContextQuery != null
                    ? WS.matcher(String.join(" ", evaluateStrings(broker, chunkContextQuery, node))).replaceAll(" ").strip()
                    : "";

            final int modelTokens = provider.getMaxTokens() > 0 ? provider.getMaxTokens() : DEFAULT_MAX_TOKENS;
            final int maxTokens = (chunkSize > 0 ? Math.min(chunkSize, modelTokens) : modelTokens) - SPECIAL_TOKENS;
            final int[] headerTokens = header.isEmpty() ? new int[0] : provider.tokenEnds(header);
            final int budget = Math.max(MIN_CHUNK_TOKENS, maxTokens - (headerTokens != null ? headerTokens.length + 1 : 0));

            final List<VectorChunker.Chunk> chunks = new VectorChunker(provider::tokenEnds, budget, chunkOverlap).chunk(units);
            final List<String> embedTexts = new ArrayList<>(chunks.size());
            for (final VectorChunker.Chunk chunk : chunks) {
                embedTexts.add(header.isEmpty() ? chunk.text() : header + "\n" + chunk.text());
            }
            return new ChunkedText(chunks, embedTexts);
        } catch (final PermissionDeniedException | XPathException e) {
            LOG.warn("Error evaluating the chunk expressions of vector field '{}': {}", fieldName, e.getMessage());
            return new ChunkedText(List.of(), List.of());
        }
    }

    /** The string values of the items an expression returns for a node. */
    private static List<String> evaluateStrings(final DBBroker broker, final CompiledXQuery query, final NodeProxy node)
            throws PermissionDeniedException, XPathException {
        try {
            final Sequence result = broker.getBrokerPool().getXQueryService().execute(broker, query, node);
            final List<String> strings = new ArrayList<>(result.getItemCount());
            for (final SequenceIterator i = result.iterate(); i.hasNext(); ) {
                strings.add(i.nextItem().getStringValue());
            }
            return strings;
        } finally {
            try {
                query.reset();
            } finally {
                query.getContext().reset();
            }
        }
    }

    /**
     * Embeds texts with the field's model, as batches.
     *
     * @param texts the texts
     * @return one vector per text, null where embedding failed
     */
    public List<float[]> embedAll(final List<String> texts) {
        final VectorEmbeddingProvider provider = getEmbeddingProvider();
        if (provider == null) {
            final List<float[]> none = new ArrayList<>(texts.size());
            texts.forEach(text -> none.add(null));
            return none;
        }
        return provider.embedAll(texts);
    }

    private int parseDimension(final Element el) throws org.exist.util.DatabaseConfigurationException {
        final String dimStr = el.getAttribute(ATTR_DIMENSION);
        if (dimStr.isEmpty()) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': attribute 'dimension' is required");
        }
        try {
            final int dim = Integer.parseInt(dimStr);
            if (dim <= 0 || dim > 1024) {
                throw new org.exist.util.DatabaseConfigurationException(
                        "vector-field '" + fieldName + "': dimension must be 1-1024, got " + dim);
            }
            return dim;
        } catch (NumberFormatException e) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': invalid dimension '" + dimStr + "'");
        }
    }

    private boolean parseEncoding(final Element el) throws org.exist.util.DatabaseConfigurationException {
        final String encStr = el.getAttribute(ATTR_ENCODING);
        final boolean base64 = encStr.isEmpty() || ENCODING_BASE64.equalsIgnoreCase(encStr);
        if (!base64 && !ENCODING_TEXT.equalsIgnoreCase(encStr)) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': encoding must be 'base64' or 'text', got '" + encStr + "'");
        }
        return base64;
    }

    private String[] parseEmbeddingConfig(final Element el, final String embStr, final boolean isHttp)
            throws org.exist.util.DatabaseConfigurationException {
        final String model = el.getAttribute(ATTR_MODEL).trim();
        if (model.isEmpty()) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': embedding=\"" + embStr + "\" requires attribute 'model'");
        }
        final String pathStr = el.getAttribute(ATTR_MODEL_PATH).trim();
        if (isHttp) {
            if (pathStr.isEmpty()) {
                throw new org.exist.util.DatabaseConfigurationException(
                        "vector-field '" + fieldName + "': embedding=\"http\" requires model-path (e.g. https://api.openai.com/v1)");
            }
            if (!HttpVectorProvider.isHttpApiUrl(pathStr)) {
                throw new org.exist.util.DatabaseConfigurationException(
                        "vector-field '" + fieldName + "': embedding=\"http\" requires model-path to be API URL (OpenAI or Cohere)");
            }
        }
        return new String[] { model, pathStr.isEmpty() ? "" : pathStr };
    }

    /** Parses similarity attribute to Lucene enum. */
    private static VectorSimilarityFunction parseSimilarity(final String s) throws org.exist.util.DatabaseConfigurationException {
        if (s == null || s.isEmpty()) {
            return VectorSimilarityFunction.COSINE;
        }
        return switch (s.toLowerCase()) {
            case "cosine" -> VectorSimilarityFunction.COSINE;
            case "euclidean" -> VectorSimilarityFunction.EUCLIDEAN;
            case "dot_product" -> VectorSimilarityFunction.DOT_PRODUCT;
            default -> throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field: similarity must be 'cosine', 'euclidean', or 'dot_product', got '" + s + "'");
        };
    }

    /**
     * Returns the vector field name.
     *
     * @return the field name
     */
    @Nonnull
    public String getName() {
        return fieldName;
    }

    /**
     * Returns the vector dimension.
     *
     * @return dimension (1–1024)
     */
    public int getDimension() {
        return dimension;
    }

    /**
     * Returns the similarity function for KNN.
     *
     * @return cosine, euclidean, or dot_product
     */
    public VectorSimilarityFunction getSimilarity() {
        return similarity;
    }

    /**
     * Returns the embedding model id when this field embeds text
     * (embedding="local" or "http"), or null for a raw-vector field.
     *
     * @return the model id, or null
     */
    @Nullable
    public String getModelId() {
        return modelId;
    }

    /** Evaluates the expression, parses vectors (or embeds text when embedding=local), and adds to the Lucene document. */
    @Override
    protected void processResult(final Sequence result, final Document luceneDoc) throws XPathException {
        if (embeddingLocal) {
            processResultEmbedding(result, luceneDoc);
            return;
        }
        final List<float[]> vectors = new ArrayList<>();
        for (SequenceIterator i = result.unorderedIterator(); i.hasNext(); ) {
            final String content = i.nextItem().getStringValue().trim();
            if (content.isEmpty()) {
                continue;
            }
            final float[] vec = parseVector(content);
            if (vec != null) {
                vectors.add(vec);
            }
        }
        if (vectors.isEmpty()) {
            return;
        }
        final float[] combined = vectors.size() == 1 ? vectors.getFirst() : meanPool(vectors);
        if (combined != null) {
            luceneDoc.add(new KnnFloatVectorField(fieldName, combined, similarity));
        }
    }

    private void processResultEmbedding(final Sequence result, final Document luceneDoc) throws XPathException {
        final VectorEmbeddingProvider provider = getEmbeddingProvider();
        if (provider == null) {
            return;
        }
        final List<float[]> vectors = new ArrayList<>();
        for (SequenceIterator i = result.unorderedIterator(); i.hasNext(); ) {
            final String content = i.nextItem().getStringValue().trim();
            if (content.isEmpty()) {
                continue;
            }
            final float[] vec = provider.embed(content);
            if (vec != null) {
                vectors.add(vec);
            }
        }
        if (vectors.isEmpty()) {
            return;
        }
        final float[] combined = vectors.size() == 1 ? vectors.getFirst() : meanPool(vectors);
        if (combined != null) {
            luceneDoc.add(new KnnFloatVectorField(fieldName, combined, similarity));
            storeVectorIfLocal(combined);
        }
    }

    @Override
    protected void processText(final CharSequence text, final Document luceneDoc) {
        final String content = text.toString().trim();
        if (content.isEmpty()) {
            return;
        }
        if (embeddingLocal) {
            final VectorEmbeddingProvider provider = getEmbeddingProvider();
            if (provider != null) {
                final float[] vec = provider.embed(content);
                if (vec != null) {
                    luceneDoc.add(new KnnFloatVectorField(fieldName, vec, similarity));
                    storeVectorIfLocal(vec);
                }
            }
            return;
        }
        final float[] vec = parseVector(content);
        if (vec != null) {
            luceneDoc.add(new KnnFloatVectorField(fieldName, vec, similarity));
        }
    }

    @Nullable
    private VectorEmbeddingProvider getEmbeddingProvider() {
        if (!embeddingLocal || modelId == null) {
            return null;
        }
        try {
            return VectorEmbeddingService.getInstance().getProvider(modelId, modelPathOrUrl, dimension);
        } catch (NoClassDefFoundError e) {
            LOG.warn("vector-field '{}': embedding=\"local\" requires exist-vector extension: {}", fieldName, e.getMessage());
            return null;
        }
    }

    private static final class StoreContext {
        final DBBroker broker;
        final String docPath;
        final NodeId nodeId;

        StoreContext(final DBBroker broker, final DocumentImpl document, final NodeId nodeId) {
            this.broker = broker;
            this.docPath = document.getURI().toString();
            this.nodeId = nodeId;
        }
    }

    private void storeVectorIfLocal(final float[] vec) {
        if (!VECTOR_STORE_DB.equals(luceneConfig.getVectorStore())) {
            return;
        }
        final StoreContext ctx = STORE_CONTEXT.get();
        if (ctx == null) {
            return;
        }
        try {
            final VectorStore vectorStore = ctx.broker.getBrokerPool().getVectorStore();
            if (vectorStore != null) {
                final Txn txn = ctx.broker.getCurrentTransaction();
                if (txn != null) {
                    final byte[] bytes = floatsToBytes(vec);
                    vectorStore.put(txn, ctx.docPath, ctx.nodeId, bytes);
                }
            }
        } catch (IOException e) {
            LOG.debug("Failed to store vector in vector.dbx: {}", e.getMessage());
        }
    }

    private static byte[] floatsToBytes(final float[] vec) {
        final ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(vec);
        return buf.array();
    }

    @Override
    protected void build(final DBBroker broker, final NodeProxy contextNode,
            final Document luceneDoc, final CharSequence text) {
        if (chunked) {
            // indexed as chunk documents of their own, see LuceneIndexWorker#write()
            return;
        }
        final DocumentImpl document = contextNode.getOwnerDocument();
        final NodeId nodeId = contextNode.getNodeId();
        final ReindexScope scope = broker.getIndexController().getReindexScope();
        try {
            if (scope == ReindexScope.FULLTEXT && VECTOR_STORE_DB.equals(luceneConfig.getVectorStore())) {
                tryRestoreVectorFromStore(broker, document, nodeId, luceneDoc);
                return;
            }
            if (!embeddingLocal && VECTOR_STORE_DB.equals(luceneConfig.getVectorStore())) {
                if (tryRestoreVectorFromStore(broker, document, nodeId, luceneDoc)) {
                    return;
                }
            } else if (embeddingLocal && VECTOR_STORE_DB.equals(luceneConfig.getVectorStore())) {
                STORE_CONTEXT.set(new StoreContext(broker, document, nodeId));
            }
            try {
                doBuild(broker, contextNode, luceneDoc, text);
            } finally {
                if (embeddingLocal) {
                    STORE_CONTEXT.remove();
                }
            }
        } catch (IOException e) {
            LOG.debug("vector.dbx read failed, falling back to XML: {}", e.getMessage());
            try {
                doBuild(broker, contextNode, luceneDoc, text);
            } catch (PermissionDeniedException | XPathException ex) {
                LOG.warn("Error evaluating expression for vector field '{}': {}", fieldName, ex.getMessage());
            }
        } catch (PermissionDeniedException e) {
            LOG.warn("Permission denied while evaluating expression for vector field '{}': {}", fieldName, e.getMessage());
        } catch (XPathException e) {
            LOG.warn("XPath error while evaluating expression for vector field '{}': {}", fieldName, e.getMessage());
        }
    }

    /**
     * Tries to restore a previously stored vector from vector.dbx and add it to the Lucene document.
     *
     * @return true if a valid vector was restored, false otherwise
     */
    private boolean tryRestoreVectorFromStore(final DBBroker broker, final DocumentImpl document,
            final NodeId nodeId, final Document luceneDoc) throws IOException {
        final VectorStore vectorStore = broker.getBrokerPool().getVectorStore();
        if (vectorStore == null) {
            return false;
        }
        final byte[] stored = vectorStore.get(document.getURI().toString(), nodeId);
        if (stored == null || stored.length != dimension * 4) {
            return false;
        }
        final float[] vec = bytesToFloats(stored);
        if (vec != null && allFinite(vec)) {
            luceneDoc.add(new KnnFloatVectorField(fieldName, vec, similarity));
            return true;
        }
        return false;
    }

    private static float[] bytesToFloats(final byte[] bytes) {
        if (bytes.length % 4 != 0) {
            return null;
        }
        final float[] vec = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vec);
        return vec;
    }

    /**
     * Parse embedding content to float[]. Returns null if invalid (skip, log and continue).
     */
    @Nullable
    private float[] parseVector(final String content) {
        if (useBase64) {
            return parseBase64(content);
        }
        return parseText(content);
    }

    @Nullable
    private float[] parseBase64(final String content) {
        try {
            final byte[] bytes = Base64.getDecoder().decode(content);
            if (bytes == null || bytes.length != dimension * 4) {
                LOG.debug("vector-field '{}': base64 decoded {} bytes (expected {} for dim={}), content len={}, skipping",
                    fieldName, bytes != null ? bytes.length : 0, dimension * 4, dimension, content != null ? content.length() : 0);
                return null;
            }
            final float[] vec = new float[dimension];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vec);
            if (!allFinite(vec)) {
                LOG.debug("vector-field '{}': non-finite values, skipping", fieldName);
                return null;
            }
            return vec;
        } catch (IllegalArgumentException e) {
            LOG.debug("vector-field '{}': invalid base64 (content len={}), skipping: {}", fieldName, content != null ? content.length() : 0, e.getMessage());
            return null;
        }
    }

    @Nullable
    private float[] parseText(final String content) {
        final String[] parts = WS.split(content.trim());
        if (parts.length != dimension) {
            LOG.debug("vector-field '{}': text has {} values (expected {}), skipping", fieldName, parts.length, dimension);
            return null;
        }
        final float[] vec = new float[dimension];
        try {
            for (int i = 0; i < dimension; i++) {
                vec[i] = Float.parseFloat(parts[i]);
            }
            if (!allFinite(vec)) {
                LOG.debug("vector-field '{}': non-finite values, skipping", fieldName);
                return null;
            }
            return vec;
        } catch (NumberFormatException e) {
            LOG.debug("vector-field '{}': invalid float in text, skipping: {}", fieldName, e.getMessage());
            return null;
        }
    }

    private static boolean allFinite(final float[] vec) {
        for (float v : vec) {
            if (!Float.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    private float[] meanPool(final List<float[]> vectors) {
        final float[] sum = new float[dimension];
        for (float[] v : vectors) {
            if (v.length != dimension) {
                continue;
            }
            for (int i = 0; i < dimension; i++) {
                sum[i] += v[i];
            }
        }
        final int n = vectors.size();
        for (int i = 0; i < dimension; i++) {
            sum[i] /= n;
        }
        return allFinite(sum) ? sum : null;
    }
}
