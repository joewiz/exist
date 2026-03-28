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

    @Override
    void close() throws StorageException;

    ReadTransaction beginReadTransaction();

    WriteTransaction beginWriteTransaction();

    Partition getPartition(String name);
}
