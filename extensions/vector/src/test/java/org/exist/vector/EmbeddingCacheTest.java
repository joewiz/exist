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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link EmbeddingCache} keeps a model's vectors across instances that share its directory, and a
 * {@link CachingEmbeddingProvider} asks the model only for texts the cache doesn't have.
 */
class EmbeddingCacheTest {

    @TempDir
    Path directory;

    @BeforeEach
    void configure() {
        System.setProperty(EmbeddingCache.PROPERTY, directory.toString());
        EmbeddingCache.reset();
    }

    @AfterEach
    void unconfigure() {
        System.clearProperty(EmbeddingCache.PROPERTY);
        EmbeddingCache.reset();
    }

    @Test
    void noCacheWithoutADirectory() {
        System.clearProperty(EmbeddingCache.PROPERTY);
        // unless the environment sets one
        if (System.getenv(EmbeddingCache.ENVIRONMENT) == null) {
            assertNull(EmbeddingCache.forModel("model", 3));
        }
    }

    @Test
    void putThenGet() {
        final EmbeddingCache cache = EmbeddingCache.forModel("model", 3);
        assertNotNull(cache);
        assertNull(cache.get("a text"));
        cache.put("a text", new float[] {1, 2, 3});
        assertArrayEquals(new float[] {1, 2, 3}, cache.get("a text"));
        assertNull(cache.get("another text"));
    }

    @Test
    void anotherProcessReadsTheVectors() {
        EmbeddingCache.forModel("model", 3).put("a text", new float[] {1, 2, 3});
        // forget the cache, as another process starting with the same directory would have it
        EmbeddingCache.reset();
        final EmbeddingCache other = EmbeddingCache.forModel("model", 3);
        assertArrayEquals(new float[] {1, 2, 3}, other.get("a text"));
        assertEquals(1, other.size());
    }

    @Test
    void twoWritersShareTheDirectory() throws IOException {
        EmbeddingCache.forModel("model", 3).put("first", new float[] {1, 1, 1});
        EmbeddingCache.reset();
        EmbeddingCache.forModel("model", 3).put("second", new float[] {2, 2, 2});
        EmbeddingCache.reset();
        try (Stream<Path> files = Files.list(directory.resolve("model-3"))) {
            assertEquals(2, files.count(), "each writer has a file of its own");
        }
        final EmbeddingCache cache = EmbeddingCache.forModel("model", 3);
        assertArrayEquals(new float[] {1, 1, 1}, cache.get("first"));
        assertArrayEquals(new float[] {2, 2, 2}, cache.get("second"));
    }

    @Test
    void aFileCutShortIsReadUpToItsLastWholeEntry() throws IOException {
        final EmbeddingCache cache = EmbeddingCache.forModel("model", 3);
        cache.put("first", new float[] {1, 1, 1});
        cache.put("second", new float[] {2, 2, 2});
        EmbeddingCache.reset();
        try (Stream<Path> files = Files.list(directory.resolve("model-3"))) {
            final Path file = files.findFirst().orElseThrow();
            // as a crash while the second entry was written could leave it
            try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(channel.size() - 5);
            }
        }
        final EmbeddingCache read = EmbeddingCache.forModel("model", 3);
        assertArrayEquals(new float[] {1, 1, 1}, read.get("first"));
        assertNull(read.get("second"));
        read.put("second", new float[] {2, 2, 2});
        assertArrayEquals(new float[] {2, 2, 2}, read.get("second"));
    }

    @Test
    void eachModelHasACacheOfItsOwn() {
        EmbeddingCache.forModel("one-model", 3).put("a text", new float[] {1, 2, 3});
        assertNull(EmbeddingCache.forModel("another-model", 3).get("a text"));
    }

    @Test
    void aVectorOfAnotherDimensionIsNotKept() {
        final EmbeddingCache cache = EmbeddingCache.forModel("model", 3);
        cache.put("a text", new float[] {1, 2});
        assertNull(cache.get("a text"));
    }

    @Test
    void theModelComputesOnlyTheMissingVectors() {
        final CountingProvider model = new CountingProvider();
        final CachingEmbeddingProvider provider = new CachingEmbeddingProvider(model, EmbeddingCache.forModel("model", 3));
        assertArrayEquals(new float[] {1, 1, 1}, provider.embed("a"));
        final List<float[]> vectors = provider.embedAll(List.of("a", "bb", "ccc"));
        assertArrayEquals(new float[] {1, 1, 1}, vectors.get(0));
        assertArrayEquals(new float[] {2, 2, 2}, vectors.get(1));
        assertArrayEquals(new float[] {3, 3, 3}, vectors.get(2));
        assertEquals(List.of("a", "bb", "ccc"), model.embedded, "'a' was computed once, then read from the cache");
        assertEquals(1, provider.getHits());
        assertEquals(3, provider.getMisses());
        provider.embedAll(List.of("a", "bb", "ccc"));
        assertEquals(3, model.embedded.size(), "all three read from the cache");
    }

    @Test
    void theFingerprintIsOfTheFilesContents() throws IOException {
        final Path model = Files.writeString(directory.resolve("model.onnx"), "weights");
        final Path tokenizer = Files.writeString(directory.resolve("tokenizer.json"), "{}");
        final String fingerprint = EmbeddingCache.fingerprint(model, tokenizer);
        assertEquals(fingerprint, EmbeddingCache.fingerprint(model, tokenizer));
        Files.writeString(model, "other weights");
        assertNotEquals(fingerprint, EmbeddingCache.fingerprint(model, tokenizer));
        assertTrue(fingerprint.matches("[0-9a-f]{32}"));
    }

    /** A model whose vector for a text is its length, in each dimension, and which records what it embedded. */
    private static final class CountingProvider implements VectorEmbeddingProvider {
        final List<String> embedded = new ArrayList<>();

        @Override
        public int getDimension() {
            return 3;
        }

        @Override
        public float[] embed(final String text) {
            embedded.add(text);
            final float length = text.length();
            return new float[] {length, length, length};
        }
    }
}
