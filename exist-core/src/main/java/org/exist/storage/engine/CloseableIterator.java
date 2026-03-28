package org.exist.storage.engine;

import java.util.Iterator;
import java.util.Map;

/**
 * An iterator over key-value entries that must be closed after use
 * to release underlying resources (e.g., LMDB cursors).
 */
public interface CloseableIterator extends Iterator<Map.Entry<byte[], byte[]>>, AutoCloseable {
    @Override
    void close();
}
