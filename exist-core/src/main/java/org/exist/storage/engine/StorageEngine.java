package org.exist.storage.engine;

import java.nio.file.Path;

/**
 * Abstract storage engine interface for eXist-db's persistent data.
 * Implementations provide transactional key-value storage with named
 * partitions (logical namespaces for different data types).
 *
 * <p>Both the LMDB and RocksDB PoCs implement this interface for
 * direct comparison.
 */
public interface StorageEngine extends AutoCloseable {
    void open(Path dataDir, StorageConfig config) throws StorageException;

    default void open(Path dataDir) throws StorageException {
        open(dataDir, new StorageConfig());
    }

    @Override
    void close() throws StorageException;

    ReadTransaction beginReadTransaction() throws StorageException;

    WriteTransaction beginWriteTransaction() throws StorageException;

    Partition getPartition(String name) throws StorageException;
}
