package org.exist.storage.engine;

/**
 * Configuration for a StorageEngine instance.
 */
public class StorageConfig {
    private long mapSize = 1L * 1024 * 1024 * 1024; // 1 GB default
    private int maxPartitions = 8;

    public long getMapSize() {
        return mapSize;
    }

    public StorageConfig setMapSize(final long mapSize) {
        this.mapSize = mapSize;
        return this;
    }

    public int getMaxPartitions() {
        return maxPartitions;
    }

    public StorageConfig setMaxPartitions(final int maxPartitions) {
        this.maxPartitions = maxPartitions;
        return this;
    }
}
