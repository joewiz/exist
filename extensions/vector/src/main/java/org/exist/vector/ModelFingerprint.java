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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The fingerprint of a model: a hash of its files' names and contents.
 */
public final class ModelFingerprint {

  private ModelFingerprint() {
  }

  /**
   * @param modelFiles the model's files, in a fixed order
   * @return the first 128 bits of the SHA-256 hash of each file's name and contents, as 32 hexadecimal digits
   * @throws IOException if a file can't be read
   */
  public static String of(final Path... modelFiles) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
    final byte[] buffer = new byte[1 << 16];
    for (final Path file : modelFiles) {
      digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
      try (InputStream in = Files.newInputStream(file)) {
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
          digest.update(buffer, 0, read);
        }
      }
    }
    final byte[] hash = digest.digest();
    final StringBuilder hex = new StringBuilder(32);
    for (int i = 0; i < 16; i++) {
      hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16)).append(Character.forDigit(hash[i] & 0xF, 16));
    }
    return hex.toString();
  }
}
