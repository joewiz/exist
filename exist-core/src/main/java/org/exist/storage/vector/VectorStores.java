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
package org.exist.storage.vector;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The hashes a {@link VectorStore} keeps with a vector, to tell whether it is still the vector of its text.
 */
public final class VectorStores {

    private VectorStores() {
    }

    /**
     * A hash of the texts a vector was computed from: the first 64 bits of the SHA-256 hash of the texts, each
     * followed by a NUL character.
     *
     * @param texts the texts, in the order they were embedded
     * @return the hash
     */
    public static long inputHash(final List<String> texts) {
        final MessageDigest digest = sha256();
        for (final String text : texts) {
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    /**
     * The fingerprint of a model as a {@link VectorStore.Entry#model()}: its first 64 bits.
     *
     * @param fingerprint the model's fingerprint, as hexadecimal digits, or null if it isn't known
     * @return the fingerprint, or 0 if it isn't known
     */
    public static long model(@Nullable final String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 16) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(fingerprint.substring(0, 16), 16);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
