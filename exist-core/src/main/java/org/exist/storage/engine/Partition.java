package org.exist.storage.engine;

import javax.annotation.Nullable;
import java.util.function.BiConsumer;

/**
 * A named partition (logical key-value namespace) within a StorageEngine.
 * Maps to an LMDB named database (DBI) or a RocksDB column family.
 */
public interface Partition {
    @Nullable
    byte[] get(ReadTransaction txn, byte[] key) throws StorageException;

    void put(WriteTransaction txn, byte[] key, byte[] value) throws StorageException;

    void delete(WriteTransaction txn, byte[] key) throws StorageException;

    /**
     * Scan entries in the range [startKey, endKey).
     * If endKey is null, scans to the end of the partition.
     */
    void scan(ReadTransaction txn, byte[] startKey, @Nullable byte[] endKey,
              BiConsumer<byte[], byte[]> visitor) throws StorageException;

    /**
     * Scan all entries in the partition.
     */
    default void scan(ReadTransaction txn, BiConsumer<byte[], byte[]> visitor) throws StorageException {
        scan(txn, new byte[0], null, visitor);
    }
}
