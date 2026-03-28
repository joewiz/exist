package org.exist.storage.engine;

import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.LockException;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmarks eXist-db's native B+tree storage engine using the DBBroker API.
 * Same test data and methodology as StorageEngineBenchmark for direct comparison.
 *
 * Guarded by -Dexist.run.benchmarks=true
 */
public class NativeStorageBenchmark {

    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 10;
    private static final String BENCH_COLLECTION = "/db/bench";

    @ClassRule
    public static final ExistEmbeddedServer server = new ExistEmbeddedServer(true, true);

    @Test
    public void runAllBenchmarks() throws Exception {
        Assume.assumeTrue("Benchmarks disabled", "true".equals(System.getProperty("exist.run.benchmarks")));
        System.out.println("\n--- Native B+tree ---");

        // Create benchmark collection
        createCollection(BENCH_COLLECTION);

        benchmarkStoreSmall();
        benchmarkStoreMedium();
        benchmarkStoreLarge();
        benchmarkRetrieve();
        benchmarkScan();
        benchmarkMixedReadWrite();
        benchmarkMemory();
    }

    private void benchmarkStoreSmall() throws Exception {
        final int numDocs = 1000;
        final int nodesPerDoc = 50;
        runStoreBenchmark(numDocs, nodesPerDoc, "store 1000 docs (50 nodes each)");
    }

    private void benchmarkStoreMedium() throws Exception {
        final int numDocs = 100;
        final int nodesPerDoc = 500;
        runStoreBenchmark(numDocs, nodesPerDoc, "store 100 docs (500 nodes each)");
    }

    private void benchmarkStoreLarge() throws Exception {
        final int numDocs = 10;
        final int nodesPerDoc = 5000;
        runStoreBenchmark(numDocs, nodesPerDoc, "store 10 docs (5000 nodes each)");
    }

    private void runStoreBenchmark(final int numDocs, final int nodesPerDoc, final String label) throws Exception {
        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];

        for (int run = 0; run < times.length; run++) {
            // Clean collection for each run
            final String runColl = BENCH_COLLECTION + "/store-" + run;
            createCollection(runColl);

            final long start = System.nanoTime();
            final BrokerPool pool = server.getBrokerPool();
            try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
                 final Txn txn = pool.getTransactionManager().beginTransaction()) {

                final Collection coll = broker.getOrCreateCollection(txn, XmldbURI.create(runColl));
                for (int d = 0; d < numDocs; d++) {
                    final String xml = generateDocument(run * numDocs + d, nodesPerDoc);
                    broker.storeDocument(txn, XmldbURI.create("doc-" + d + ".xml"),
                            new StringInputSource(xml), MimeType.XML_TYPE, coll);
                }
                txn.commit();
            }
            times[run] = (System.nanoTime() - start) / 1_000_000.0;
        }

        reportResults(times, numDocs, label);
    }

    private void benchmarkRetrieve() throws Exception {
        // Pre-populate 1000 docs
        final String coll = BENCH_COLLECTION + "/retrieve";
        createCollection(coll);
        final BrokerPool pool = server.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn txn = pool.getTransactionManager().beginTransaction()) {
            final Collection collection = broker.getOrCreateCollection(txn, XmldbURI.create(coll));
            for (int d = 0; d < 1000; d++) {
                broker.storeDocument(txn, XmldbURI.create("doc-" + d + ".xml"),
                        new StringInputSource(generateDocument(d, 50)), MimeType.XML_TYPE, collection);
            }
            txn.commit();
        }

        final Random rng = new Random(42);
        final int[] order = rng.ints(1000, 0, 1000).toArray();
        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];

        for (int run = 0; run < times.length; run++) {
            final long start = System.nanoTime();
            try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
                final Collection collection = broker.openCollection(XmldbURI.create(coll), Lock.LockMode.READ_LOCK);
                if (collection != null) {
                    try {
                        for (final int docIdx : order) {
                            final DocumentImpl doc = collection.getDocument(broker,
                                    XmldbURI.create("doc-" + docIdx + ".xml"));
                            if (doc == null) throw new AssertionError("Missing doc-" + docIdx);
                        }
                    } finally {
                        collection.close();
                    }
                }
            }
            times[run] = (System.nanoTime() - start) / 1_000_000.0;
        }

        reportResults(times, 1000, "retrieve 1000 docs");
    }

    private void benchmarkScan() throws Exception {
        // Use the same pre-populated collection from retrieve
        final String coll = BENCH_COLLECTION + "/retrieve";
        final BrokerPool pool = server.getBrokerPool();
        final double[] times = new double[WARMUP_RUNS + MEASURED_RUNS];

        for (int run = 0; run < times.length; run++) {
            final long start = System.nanoTime();
            try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
                final Collection collection = broker.openCollection(XmldbURI.create(coll), Lock.LockMode.READ_LOCK);
                if (collection != null) {
                    try {
                        int docCount = 0;
                        for (final Iterator<DocumentImpl> it = collection.iterator(broker); it.hasNext(); ) {
                            final DocumentImpl doc = it.next();
                            docCount++;
                        }
                    } finally {
                        collection.close();
                    }
                }
            }
            times[run] = (System.nanoTime() - start) / 1_000_000.0;
        }

        reportResults(times, 100, "scan 100 docs");
    }

    private void benchmarkMixedReadWrite() throws Exception {
        final int durationSeconds = 10;
        final String coll = BENCH_COLLECTION + "/mixed";
        createCollection(coll);

        // Pre-populate some docs
        final BrokerPool pool = server.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn txn = pool.getTransactionManager().beginTransaction()) {
            final Collection collection = broker.getOrCreateCollection(txn, XmldbURI.create(coll));
            for (int d = 0; d < 100; d++) {
                broker.storeDocument(txn, XmldbURI.create("doc-" + d + ".xml"),
                        new StringInputSource(generateDocument(d, 50)), MimeType.XML_TYPE, collection);
            }
            txn.commit();
        }

        final AtomicLong readOps = new AtomicLong(0);
        final AtomicLong writeOps = new AtomicLong(0);
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
                        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
                            final Collection collection = broker.openCollection(
                                    XmldbURI.create(coll), Lock.LockMode.READ_LOCK);
                            if (collection != null) {
                                try {
                                    collection.getDocument(broker,
                                            XmldbURI.create("doc-" + rng.nextInt(100) + ".xml"));
                                    readOps.incrementAndGet();
                                } finally {
                                    collection.close();
                                }
                            }
                        }
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // 1 writer thread
        executor.submit(() -> {
            try {
                barrier.await();
                int seq = 0;
                while (System.nanoTime() < deadline) {
                    try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
                         final Txn txn = pool.getTransactionManager().beginTransaction()) {
                        final Collection collection = broker.openCollection(
                                XmldbURI.create(coll), Lock.LockMode.WRITE_LOCK);
                        if (collection != null) {
                            try {
                                broker.storeDocument(txn, XmldbURI.create("write-" + seq + ".xml"),
                                        new StringInputSource("<w id='" + seq + "'/>"),
                                        MimeType.XML_TYPE, collection);
                                txn.commit();
                                writeOps.incrementAndGet();
                                seq++;
                            } finally {
                                collection.close();
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }
        });

        executor.shutdown();
        executor.awaitTermination(durationSeconds + 10, TimeUnit.SECONDS);

        System.out.printf("  Native mixed read/write (%ds): reads=%,d/s writes=%,d/s%n",
                durationSeconds, readOps.get() / durationSeconds, writeOps.get() / durationSeconds);
    }

    private void benchmarkMemory() throws Exception {
        final Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(100);
        final long before = rt.totalMemory() - rt.freeMemory();

        // Store 1000 docs
        final String coll = BENCH_COLLECTION + "/memory";
        createCollection(coll);
        final BrokerPool pool = server.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn txn = pool.getTransactionManager().beginTransaction()) {
            final Collection collection = broker.getOrCreateCollection(txn, XmldbURI.create(coll));
            for (int d = 0; d < 1000; d++) {
                broker.storeDocument(txn, XmldbURI.create("doc-" + d + ".xml"),
                        new StringInputSource(generateDocument(d, 50)), MimeType.XML_TYPE, collection);
            }
            txn.commit();
        }

        System.gc();
        Thread.sleep(100);
        final long after = rt.totalMemory() - rt.freeMemory();
        final long heapDelta = after - before;

        System.out.printf("  Native memory: heap delta = %,d bytes (%.1f MB)%n",
                heapDelta, heapDelta / (1024.0 * 1024.0));
    }

    // --- Helpers ---

    private void createCollection(final String path) throws Exception {
        final BrokerPool pool = server.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn txn = pool.getTransactionManager().beginTransaction()) {
            broker.getOrCreateCollection(txn, XmldbURI.create(path));
            txn.commit();
        }
    }

    static String generateDocument(final int docId, final int numNodes) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<root doc='").append(docId).append("'>");
        for (int n = 0; n < numNodes; n++) {
            sb.append("<node id='").append(n).append("' attr1='val").append(n * 3)
              .append("' attr2='val").append(n * 7).append("'>")
              .append("Content text ").append(docId).append("-").append(n)
              .append(" with some padding to simulate real data</node>");
        }
        sb.append("</root>");
        return sb.toString();
    }

    private void reportResults(final double[] allTimes, final int ops, final String label) {
        final double[] measured = new double[MEASURED_RUNS];
        System.arraycopy(allTimes, WARMUP_RUNS, measured, 0, MEASURED_RUNS);

        double sum = 0;
        for (final double t : measured) sum += t;
        final double mean = sum / measured.length;

        double variance = 0;
        for (final double t : measured) variance += (t - mean) * (t - mean);
        final double stddev = Math.sqrt(variance / measured.length);

        final double opsPerSec = ops / (mean / 1000.0);
        System.out.printf("  Native %s: %.1f ms (±%.1f ms), %.0f ops/sec%n",
                label, mean, stddev, opsPerSec);
    }
}
