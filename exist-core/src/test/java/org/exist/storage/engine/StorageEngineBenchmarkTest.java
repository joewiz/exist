package org.exist.storage.engine;

import org.exist.storage.lmdb.LmdbStorageEngine;
import org.exist.storage.rocksdb.RocksDbStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs benchmarks against both LMDB and RocksDB, producing a comparison table.
 * Guarded by system property: -Dexist.run.benchmarks=true
 */
@EnabledIfSystemProperty(named = "exist.run.benchmarks", matches = "true")
public class StorageEngineBenchmarkTest {

    @TempDir
    Path tempDir;

    @Test
    void compareLmdbAndRocksDb() throws Exception {
        System.out.println("\n=== Storage Engine Benchmark Comparison ===\n");

        // Run LMDB
        System.out.println("--- LMDB ---");
        final StorageEngine lmdbEngine = new LmdbStorageEngine();
        final StorageEngineBenchmark lmdbBench = new StorageEngineBenchmark(lmdbEngine, "LMDB");
        final Map<String, double[]> lmdbResults = lmdbBench.runAll(tempDir.resolve("lmdb"));

        System.out.println();

        // Run RocksDB
        System.out.println("--- RocksDB ---");
        final StorageEngine rocksEngine = new RocksDbStorageEngine();
        final StorageEngineBenchmark rocksBench = new StorageEngineBenchmark(rocksEngine, "RocksDB");
        final Map<String, double[]> rocksResults = rocksBench.runAll(tempDir.resolve("rocksdb"));

        // Print comparison table
        System.out.println("\n=== Comparison Table ===\n");
        System.out.printf("| %-20s | %15s | %15s | %7s |%n", "Benchmark", "LMDB", "RocksDB", "Winner");
        System.out.printf("|%-22s|%17s|%17s|%9s|%n", "-".repeat(22), "-".repeat(17), "-".repeat(17), "-".repeat(9));

        for (final String key : lmdbResults.keySet()) {
            final double[] lmdb = lmdbResults.get(key);
            final double[] rocks = rocksResults.getOrDefault(key, new double[]{0, 0, 0});

            if (key.equals("mixedReadWrite")) {
                // [readOps/sec, writeOps/sec]
                System.out.printf("| %-20s | %,12.0f r/s | %,12.0f r/s | %-7s |%n",
                        "mixed reads/sec", lmdb[0], rocks[0],
                        lmdb[0] > rocks[0] ? "LMDB" : "RocksDB");
                System.out.printf("| %-20s | %,12.0f w/s | %,12.0f w/s | %-7s |%n",
                        "mixed writes/sec", lmdb[1], rocks[1],
                        lmdb[1] > rocks[1] ? "LMDB" : "RocksDB");
            } else if (key.equals("memoryFootprint")) {
                System.out.printf("| %-20s | %12.1f MB | %12.1f MB | %-7s |%n",
                        key, lmdb[0], rocks[0],
                        lmdb[0] < rocks[0] ? "LMDB" : "RocksDB");
            } else {
                // [mean ms, stddev ms, ops/sec]
                final String winner = lmdb[2] > rocks[2] ? "LMDB" : "RocksDB";
                System.out.printf("| %-20s | %,12.0f o/s | %,12.0f o/s | %-7s |%n",
                        key, lmdb[2], rocks[2], winner);
            }
        }
        System.out.println();
    }
}
