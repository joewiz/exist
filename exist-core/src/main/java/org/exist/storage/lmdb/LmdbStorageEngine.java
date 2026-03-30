package org.exist.storage.lmdb;

import org.exist.storage.engine.*;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.EnvInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

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
 *
 * <p>LMDB constraints: max key size is 511 bytes. Single write transaction
 * at a time (enforced by Java-level ReentrantLock). Map size is fixed at
 * open time but auto-grows on MapFullException.
 */
public class LmdbStorageEngine implements StorageEngine {

    private static final Logger LOG = LogManager.getLogger(LmdbStorageEngine.class);

    private Env<ByteBuffer> env;
    private Path dataDir;
    private long mapSize;
    private int maxPartitions;
    private final ConcurrentMap<String, LmdbPartition> partitions = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    @Override
    public void open(final Path dataDir, final StorageConfig config) throws StorageException {
        this.dataDir = dataDir;
        this.mapSize = config.getMapSize();
        this.maxPartitions = config.getMaxPartitions();
        openEnv();
    }

    private void openEnv() throws StorageException {
        try {
            env = Env.create()
                    .setMapSize(mapSize)
                    .setMaxDbs(maxPartitions)
                    .open(dataDir.toFile(), EnvFlags.MDB_NOSYNC);
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
    public ReadTransaction beginReadTransaction() throws StorageException {
        try {
            return new LmdbReadTransaction(env.txnRead());
        } catch (final Exception e) {
            throw new StorageException("Failed to begin read transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public WriteTransaction beginWriteTransaction() throws StorageException {
        if (!writeLock.tryLock()) {
            throw new StorageException(
                    "Another write transaction is in progress. LMDB supports only one concurrent writer.");
        }
        try {
            return new LmdbWriteTransaction(env.txnWrite(), writeLock);
        } catch (final Exception e) {
            writeLock.unlock();
            throw new StorageException("Failed to begin write transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public Partition getPartition(final String name) throws StorageException {
        try {
            return partitions.computeIfAbsent(name, n -> {
                final Dbi<ByteBuffer> dbi = env.openDbi(n, DbiFlags.MDB_CREATE);
                return new LmdbPartition(dbi);
            });
        } catch (final Exception e) {
            throw new StorageException("Failed to open partition: " + name, e);
        }
    }

    /**
     * Grows the map size by 2x, closing and reopening the environment.
     * Called when a MapFullException is detected during a write operation.
     * Partitions must be re-obtained after this call.
     */
    void growMapSize() throws StorageException {
        final long oldSize = mapSize;
        mapSize = mapSize * 2;
        LOG.warn("LMDB map full ({}MB), growing to {}MB", oldSize / (1024 * 1024), mapSize / (1024 * 1024));
        partitions.clear();
        if (env != null) {
            env.close();
        }
        openEnv();
    }
}
