package org.exist.storage.engine;

/**
 * A read-write transaction. Only one write transaction may be active at a time.
 * Must be committed or aborted, then closed.
 */
public interface WriteTransaction extends ReadTransaction {
    void commit() throws StorageException;
    void abort();
}
