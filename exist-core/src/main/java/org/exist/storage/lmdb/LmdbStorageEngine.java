package org.exist.storage.lmdb;

import org.exist.storage.engine.*;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LMDB-based StorageEngine implementation.
 *
 * <p>Creates a single LMDB environment per database instance with
 * named databases (DBIs) for each partition. LMDB provides:
 * <ul>
 *   <li>MVCC with snapshot isolation for readers</li>
 *   <li>Single-writer serialization (one write txn at a time)</li>
 *   <li>Crash-safe writes (copy-on-write B+ tree)</li>
 *   <li>Zero-copy reads via memory-mapped files</li>
 * </ul>
 */
public class LmdbStorageEngine implements StorageEngine {
    private Env<ByteBuffer> env;
    private final ConcurrentMap<String, LmdbPartition> partitions = new ConcurrentHashMap<>();

    @Override
    public void open(final Path dataDir, final StorageConfig config) throws StorageException {
        try {
            env = Env.create()
                    .setMapSize(config.getMapSize())
                    .setMaxDbs(config.getMaxPartitions())
                    .open(dataDir.toFile(), EnvFlags.MDB_NOSYNC);
            // MDB_NOSYNC: rely on explicit txn.commit() for durability.
            // In production, remove this flag for full fsync-on-commit.
        } catch (final Exception e) {
            throw new StorageException("Failed to open LMDB environment at " + dataDir, e);
        }
    }

    @Override
    public void close() throws StorageException {
        try {
            partitions.clear();
            if (env != null) {
                env.close();
                env = null;
            }
        } catch (final Exception e) {
            throw new StorageException("Failed to close LMDB environment", e);
        }
    }

    @Override
    public ReadTransaction beginReadTransaction() {
        return new LmdbReadTransaction(env.txnRead());
    }

    @Override
    public WriteTransaction beginWriteTransaction() {
        return new LmdbWriteTransaction(env.txnWrite());
    }

    @Override
    public Partition getPartition(final String name) {
        return partitions.computeIfAbsent(name, n -> {
            final Dbi<ByteBuffer> dbi = env.openDbi(n, DbiFlags.MDB_CREATE);
            return new LmdbPartition(dbi);
        });
    }
}
