package org.exist.storage.engine;

/**
 * A read-only transaction providing snapshot isolation.
 * Must be closed after use.
 */
public interface ReadTransaction extends AutoCloseable {
    @Override
    void close();
}
