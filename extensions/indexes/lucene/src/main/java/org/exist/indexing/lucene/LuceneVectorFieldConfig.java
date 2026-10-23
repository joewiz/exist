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
import org.exist.storage.vector.VectorStores;
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

    /** How each node's text is cut into chunks, each embedded and indexed on its own, or null if it isn't, see {@link #chunk}. */
    @Nullable private final Chunking chunking;
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

        registerQuantization(configElement);
        chunking = parseChunking(configElement);
    }

    /**
     * The chunking of a field.
     *
     * @param size the most tokens of a chunk including its context header, or 0 for as many as the model reads
     * @param overlap the tokens that the pieces of a split unit share
     * @param unitsCode the expression of the units, with the configuration's prolog
     * @param contextCode the expression of the context header, with the configuration's prolog, or null for none
     */
    private record Chunking(int size, int overlap, String unitsCode, @Nullable String contextCode) {
    }

    private void registerQuantization(final Element configElement) throws org.exist.util.DatabaseConfigurationException {
        final String quantize = configElement.getAttribute(ATTR_QUANTIZE);
        if (QUANTIZE_INT8.equals(quantize)) {
            LuceneIndex.INT8_VECTOR_FIELDS.add(fieldName);
        } else if (!quantize.isEmpty() && !"none".equals(quantize)) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': attribute 'quantize' must be 'int8' or 'none', got '" + quantize + "'");
        }
    }

    @Nullable
    private Chunking parseChunking(final Element configElement) throws org.exist.util.DatabaseConfigurationException {
        final String chunk = configElement.getAttribute(ATTR_CHUNK);
        if (chunk.isEmpty()) {
            return null;
        }
        if (!CHUNK_TOKENS.equals(chunk)) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': attribute 'chunk' must be 'tokens', got '" + chunk + "'");
        }
        if (!embeddingLocal) {
            throw new org.exist.util.DatabaseConfigurationException(
                    "vector-field '" + fieldName + "': chunk=\"tokens\" requires embedding=\"local\" or \"http\"");
        }
        final String units = configElement.getAttribute(ATTR_CHUNK_UNITS);
        final String context = configElement.getAttribute(ATTR_CHUNK_CONTEXT);
        return new Chunking(parseNonNegative(configElement, ATTR_CHUNK_SIZE), parseNonNegative(configElement, ATTR_CHUNK_OVERLAP),
                prolog + (units.isEmpty() ? DEFAULT_CHUNK_UNITS : units), context.isEmpty() ? null : prolog + context);
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
        return chunking != null;
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
        if (chunking == null || provider == null || !compileChunkQueries(broker)) {
            return new ChunkedText(List.of(), List.of());
        }
        try {
            final List<String> units = evaluateStrings(broker, chunkUnitsQuery, node);
            final String header = chunkContextQuery != null
                    ? WS.matcher(String.join(" ", evaluateStrings(broker, chunkContextQuery, node))).replaceAll(" ").strip()
                    : "";
            final List<VectorChunker.Chunk> chunks =
                    new VectorChunker(provider::tokenEnds, chunkTokens(provider, header), chunking.overlap()).chunk(units);
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

    /**
     * The text a node's chunks were cut from, to which their offsets refer: the string values of the node's
     * {@code chunk-units}, joined without separators. Evaluated with a compiled query of its own, outside indexing.
     *
     * @param broker the broker to evaluate with, as its user
     * @param node the node the field indexed
     * @return the text, or null if the field isn't chunked
     * @throws XPathException if the units can't be evaluated
     * @throws PermissionDeniedException if the user may not evaluate them
     */
    public @Nullable String chunkedText(final DBBroker broker, final NodeProxy node)
            throws XPathException, PermissionDeniedException {
        if (chunking == null) {
            return null;
        }
        return evaluate(broker, chunking.unitsCode(), node, result -> {
            final StringBuilder text = new StringBuilder();
            for (final SequenceIterator i = result.iterate(); i.hasNext(); ) {
                text.append(i.nextItem().getStringValue());
            }
            return text.toString();
        });
    }

    /** Compiles the chunk expressions once. */
    private boolean compileChunkQueries(final DBBroker broker) {
        if (chunkUnitsQuery == null) {
            chunkUnitsQuery = compile(broker, chunking.unitsCode());
        }
        if (chunking.contextCode() != null && chunkContextQuery == null) {
            chunkContextQuery = compile(broker, chunking.contextCode());
        }
        return chunkUnitsQuery != null;
    }

    /** The most tokens of a chunk's own text: what the model reads, less its special tokens and the context header. */
    private int chunkTokens(final VectorEmbeddingProvider provider, final String header) {
        final int modelTokens = provider.getMaxTokens() > 0 ? provider.getMaxTokens() : DEFAULT_MAX_TOKENS;
        final int maxTokens = (chunking.size() > 0 ? Math.min(chunking.size(), modelTokens) : modelTokens) - SPECIAL_TOKENS;
        final int[] headerTokens = header.isEmpty() ? null : provider.tokenEnds(header);
        return Math.max(MIN_CHUNK_TOKENS, maxTokens - (headerTokens != null ? headerTokens.length + 1 : 0));
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
        final List<String> texts = new ArrayList<>();
        for (SequenceIterator i = result.unorderedIterator(); i.hasNext(); ) {
            final String content = i.nextItem().getStringValue().trim();
            if (!content.isEmpty()) {
                texts.add(content);
            }
        }
        final float[] vector = vectorOf(provider, texts);
        if (vector != null) {
            luceneDoc.add(new KnnFloatVectorField(fieldName, vector, similarity));
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
                final float[] vec = vectorOf(provider, List.of(content));
                if (vec != null) {
                    luceneDoc.add(new KnnFloatVectorField(fieldName, vec, similarity));
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

    /** The node a vector is computed for, and the reindex's scope, while it is built. */
    private record StoreContext(DBBroker broker, int docId, NodeId nodeId, ReindexScope scope) {
    }

    /**
     * The vector of a node's texts, embedded one by one and averaged. It is the one vector.dbx keeps for the node, if
     * it was computed from the same texts by the same model, unless the reindex is one that computes the vectors
     * again ({@link ReindexScope#VECTOR}); otherwise it is computed, and kept in vector.dbx.
     *
     * @param provider the model
     * @param texts the texts
     * @return the vector, or null if none could be computed
     */
    @Nullable
    private float[] vectorOf(final VectorEmbeddingProvider provider, final List<String> texts) {
        if (texts.isEmpty()) {
            return null;
        }
        final StoreContext ctx = STORE_CONTEXT.get();
        final VectorStore store = ctx == null ? null : ctx.broker().getBrokerPool().getVectorStore();
        final long inputHash = VectorStores.inputHash(texts);
        final long model = VectorStores.model(provider.getFingerprint());
        if (store != null && model != 0 && ctx.scope() != ReindexScope.VECTOR) {
            final float[] stored = storedVector(store, ctx, inputHash, model);
            if (stored != null) {
                return stored;
            }
        }
        final float[] computed = computeVector(provider, texts);
        if (computed != null && store != null) {
            keep(store, ctx, new VectorStore.Entry(0, 0, inputHash, model, computed));
        }
        return computed;
    }

    /** The vector vector.dbx keeps for the node, if it was computed from the same input by the same model. */
    @Nullable
    private float[] storedVector(final VectorStore store, final StoreContext ctx, final long inputHash, final long model) {
        try {
            final VectorStore.Entry stored = store.get(ctx.docId(), ctx.nodeId(), fieldName, 0);
            if (stored != null && stored.inputHash() == inputHash && stored.model() == model
                    && stored.vector().length == dimension && allFinite(stored.vector())) {
                return stored.vector();
            }
        } catch (final IOException e) {
            LOG.debug("Could not read a vector from vector.dbx: {}", e.getMessage());
        }
        return null;
    }

    /** The texts embedded one by one and averaged, or null if none could be. */
    @Nullable
    private float[] computeVector(final VectorEmbeddingProvider provider, final List<String> texts) {
        final List<float[]> vectors = new ArrayList<>(texts.size());
        for (final String text : texts) {
            final float[] vector = provider.embed(text);
            if (vector != null) {
                vectors.add(vector);
            }
        }
        if (vectors.isEmpty()) {
            return null;
        }
        return vectors.size() == 1 ? vectors.getFirst() : meanPool(vectors);
    }

    /** Keeps the node's vector in vector.dbx, in the current transaction. */
    private void keep(final VectorStore store, final StoreContext ctx, final VectorStore.Entry entry) {
        final Txn txn = ctx.broker().getCurrentTransaction();
        if (txn == null) {
            return;
        }
        try {
            store.put(txn, ctx.docId(), ctx.nodeId(), fieldName, 0, entry);
        } catch (final IOException e) {
            LOG.debug("Could not keep a vector in vector.dbx: {}", e.getMessage());
        }
    }

    @Override
    protected void build(final DBBroker broker, final NodeProxy contextNode,
            final Document luceneDoc, final CharSequence text) {
        if (chunking != null) {
            // indexed as chunk documents of their own, see LuceneIndexWorker#write()
            return;
        }
        final DocumentImpl document = contextNode.getOwnerDocument();
        final NodeId nodeId = contextNode.getNodeId();
        final ReindexScope scope = broker.getIndexController().getReindexScope();
        try {
            // a "fulltext" reindex restores the stored vector, and computes it only if there is none
            if (VECTOR_STORE_DB.equals(luceneConfig.getVectorStore()) && (scope == ReindexScope.FULLTEXT || !embeddingLocal)
                    && tryRestoreVectorFromStore(broker, document, nodeId, luceneDoc)) {
                return;
            }
            if (embeddingLocal && VECTOR_STORE_DB.equals(luceneConfig.getVectorStore())) {
                STORE_CONTEXT.set(new StoreContext(broker, document.getDocId(), nodeId, scope));
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
        final VectorStore.Entry stored = vectorStore.get(document.getDocId(), nodeId, fieldName, 0);
        if (stored == null || stored.vector().length != dimension) {
            return false;
        }
        final float[] vec = stored.vector();
        if (allFinite(vec)) {
            luceneDoc.add(new KnnFloatVectorField(fieldName, vec, similarity));
            return true;
        }
        return false;
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
