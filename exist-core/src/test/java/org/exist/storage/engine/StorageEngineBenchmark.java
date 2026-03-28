package org.exist.storage.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark harness for comparing StorageEngine implementations.
 * Measures store, retrieve, scan, mixed read/write, and memory footprint.
 */
public class StorageEngineBenchmark {

    private static final int SMALL_NODES = 50;
    private static final int MEDIUM_NODES = 500;
    private static final int LARGE_NODES = 5000;
    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 10;

    private final StorageEngine engine;
    private final String engineName;

    public StorageEngineBenchmark(final StorageEngine engine, final String engineName) {
        this.engine = engine;
        this.engineName = engineName;
    }

    public Map<String, double[]> runAll(final Path dataDir) throws Exception {
        Files.createDirectories(dataDir);
        engine.open(dataDir, new StorageConfig().setMapSize(512L * 1024 * 1024));

        final Map<String, double[]> results = new LinkedHashMap<>();

        results.put("storeSmallDocs", benchmarkStore(1000, SMALL_NODES));
        results.put("storeMediumDocs", benchmarkStore(100, MEDIUM_NODES));
        results.put("storeLargeDocs", benchmarkStore(10, LARGE_NODES));
        results.put("retrieveByDocId", benchmarkRetrieve(1000));
        results.put("scanAllNodes", benchmarkScan(100));
        results.put("mixedReadWrite", benchmarkMixedReadWrite(10));
        results.put("memoryFootprint", benchmarkMemory());

        engine.close();
        return results;
    }

    private double[] benchmarkStore(final int numDocs, final int nodesPerDoc) throws Exception {
        final String label = String.format("store %d docs (%d nodes each)", numDocs, nodesPerDoc);
        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];

        for (int run = 0; run < times.length; run++) {
            // Clean: reopen engine for each run
            final Partition dom = engine.getPartition("dom");
            final Partition collections = engine.getPartition("collections");

            final long start = System.nanoTime();
            for (int d = 0; d < numDocs; d++) {
                try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
                    final int docId = run * numDocs + d + 1;
                    for (int n = 0; n < nodesPerDoc; n++) {
                        final byte[] key = makeKey(docId, n);
                        final byte[] value = generateNodeData(docId, n);
                        dom.put(wtx, key, value);
                    }
                    // Store document metadata
                    final byte[] metaKey = docMetaKey(docId);
                    collections.put(wtx, metaKey, ("doc-" + docId + "-meta").getBytes(StandardCharsets.UTF_8));
                    wtx.commit();
                }
            }
            times[run] = (System.nanoTime() - start) / 1_000_000.0;
        }

        return extractMeasured(times, numDocs, label);
    }

    private double[] benchmarkRetrieve(final int numDocs) throws Exception {
        // Pre-populate
        final Partition dom = engine.getPartition("dom");
        final Partition collections = engine.getPartition("collections");
        for (int d = 0; d < numDocs; d++) {
            try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
                for (int n = 0; n < SMALL_NODES; n++) {
                    dom.put(wtx, makeKey(d + 1, n), generateNodeData(d + 1, n));
                }
                collections.put(wtx, docMetaKey(d + 1), ("meta-" + d).getBytes(StandardCharsets.UTF_8));
                wtx.commit();
            }
        }

        final Random rng = new Random(42);
        final int[] order = rng.ints(numDocs, 1, numDocs + 1).toArray();
        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];

        for (int run = 0; run < times.length; run++) {
            final long start = System.nanoTime();
            for (final int docId : order) {
                try (final ReadTransaction rtx = engine.beginReadTransaction()) {
                    // Read first node to verify doc exists
                    final byte[] val = dom.get(rtx, makeKey(docId, 0));
                    if (val == null) throw new AssertionError("Missing doc " + docId);
                }
            }
            times[run] = (System.nanoTime() - start) / 1_000_000.0;
        }

        return extractMeasured(times, numDocs, "retrieve " + numDocs + " docs");
    }

    private double[] benchmarkScan(final int numDocs) throws Exception {
        // Pre-populate if not already done
        final Partition dom = engine.getPartition("dom");
        final Random rng = new Random(99);
        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];
        final int[] docIds = rng.ints(numDocs, 1, 1001).toArray();

        for (int run = 0; run < times.length; run++) {
            final AtomicLong nodeCount = new AtomicLong(0);
            final long start = System.nanoTime();
            for (final int docId : docIds) {
                try (final ReadTransaction rtx = engine.beginReadTransaction()) {
                    dom.scan(rtx, makeKey(docId, 0), makeKey(docId + 1, 0), (k, v) -> {
                        nodeCount.incrementAndGet();
                    });
                }
            }
            times[run] = (System.nanoTime() - start) / 1_000_000.0;
        }

        return extractMeasured(times, numDocs, "scan " + numDocs + " docs");
    }

    private double[] benchmarkMixedReadWrite(final int durationSeconds) throws Exception {
        final Partition dom = engine.getPartition("dom");
        final AtomicLong readOps = new AtomicLong(0);
        final AtomicLong writeOps = new AtomicLong(0);
        final AtomicInteger errors = new AtomicInteger(0);

        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];

        for (int run = 0; run < times.length; run++) {
            readOps.set(0);
            writeOps.set(0);
            errors.set(0);

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
            final ExecutorService executor = Executors.newFixedThreadPool(5);
            final CyclicBarrier barrier = new CyclicBarrier(5);

            // 4 reader threads
            for (int r = 0; r < 4; r++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        final Random rng = new Random();
                        while (System.nanoTime() < deadline) {
                            try (final ReadTransaction rtx = engine.beginReadTransaction()) {
                                dom.get(rtx, makeKey(rng.nextInt(1000) + 1, 0));
                                readOps.incrementAndGet();
                            }
                        }
                    } catch (final Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            // 1 writer thread
            executor.submit(() -> {
                try {
                    barrier.await();
                    int seq = 0;
                    while (System.nanoTime() < deadline) {
                        try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
                            dom.put(wtx, makeKey(100000 + seq, 0), ("w-" + seq).getBytes(StandardCharsets.UTF_8));
                            wtx.commit();
                            writeOps.incrementAndGet();
                            seq++;
                        }
                    }
                } catch (final Exception e) {
                    errors.incrementAndGet();
                }
            });

            executor.shutdown();
            executor.awaitTermination(durationSeconds + 5, TimeUnit.SECONDS);

            // Store as: [readOps/sec, writeOps/sec, errors]
            times[run] = readOps.get() + writeOps.get(); // total ops for the run
        }

        // Custom reporting for mixed
        final long totalReads = readOps.get();
        final long totalWrites = writeOps.get();
        System.out.printf("  %s mixed read/write (%ds): reads=%,d/s writes=%,d/s errors=%d%n",
                engineName, durationSeconds, totalReads / durationSeconds, totalWrites / durationSeconds, errors.get());

        return new double[]{totalReads / (double) durationSeconds, totalWrites / (double) durationSeconds};
    }

    private double[] benchmarkMemory() throws Exception {
        final Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(100);
        final long before = rt.totalMemory() - rt.freeMemory();

        // Store 1000 docs
        final Partition dom = engine.getPartition("dom");
        for (int d = 0; d < 1000; d++) {
            try (final WriteTransaction wtx = engine.beginWriteTransaction()) {
                for (int n = 0; n < SMALL_NODES; n++) {
                    dom.put(wtx, makeKey(d + 50000, n), generateNodeData(d + 50000, n));
                }
                wtx.commit();
            }
        }

        System.gc();
        Thread.sleep(100);
        final long after = rt.totalMemory() - rt.freeMemory();
        final long heapDelta = after - before;

        System.out.printf("  %s memory: heap delta = %,d bytes (%.1f MB)%n",
                engineName, heapDelta, heapDelta / (1024.0 * 1024.0));

        return new double[]{heapDelta / (1024.0 * 1024.0)};
    }

    // --- Helpers ---

    private double[] extractMeasured(final double[] allTimes, final int ops, final String label) {
        // Skip warmup runs, compute mean and stddev of measured runs
        final double[] measured = new double[MEASURED_RUNS];
        System.arraycopy(allTimes, WARMUP_RUNS, measured, 0, MEASURED_RUNS);

        double sum = 0;
        for (final double t : measured) sum += t;
        final double mean = sum / measured.length;

        double variance = 0;
        for (final double t : measured) variance += (t - mean) * (t - mean);
        final double stddev = Math.sqrt(variance / measured.length);

        final double opsPerSec = ops / (mean / 1000.0);
        System.out.printf("  %s %s: %.1f ms (±%.1f ms), %.0f ops/sec%n",
                engineName, label, mean, stddev, opsPerSec);

        return new double[]{mean, stddev, opsPerSec};
    }

    static byte[] makeKey(final int docId, final int nodeId) {
        final ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(docId);
        buf.putInt(nodeId);
        return buf.array();
    }

    static byte[] docMetaKey(final int docId) {
        final ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0x03);
        buf.putInt(docId);
        return buf.array();
    }

    static byte[] generateNodeData(final int docId, final int nodeId) {
        // Simulate varied XML node data (element name, attributes, text content)
        final String data = String.format("<node doc='%d' id='%d' attr1='val%d' attr2='val%d'>Content text %d-%d with some padding to simulate real data</node>",
                docId, nodeId, nodeId * 3, nodeId * 7, docId, nodeId);
        return data.getBytes(StandardCharsets.UTF_8);
    }

    // --- Main entry point for running benchmarks ---

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: StorageEngineBenchmark <engine> <dataDir>");
            System.err.println("  engine: lmdb | rocksdb");
            System.exit(1);
        }

        final String engineType = args[0];
        final Path dataDir = Path.of(args[1]);
        Files.createDirectories(dataDir);

        final StorageEngine engine;
        if ("lmdb".equalsIgnoreCase(engineType)) {
            engine = new org.exist.storage.lmdb.LmdbStorageEngine();
        } else if ("rocksdb".equalsIgnoreCase(engineType)) {
            engine = new org.exist.storage.rocksdb.RocksDbStorageEngine();
        } else {
            throw new IllegalArgumentException("Unknown engine: " + engineType);
        }

        final StorageEngineBenchmark bench = new StorageEngineBenchmark(engine, engineType);
        bench.runAll(dataDir);
    }
}
