package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Many threads issuing {@code .all()} concurrently against a pre-populated table on
 * the same {@link P4Switch}. Proves the outbound serial executor inside the switch
 * does not race read iterators, and that all caller threads see the same
 * (consistent) result.
 *
 * <p>Repeated three times because concurrency hazards surface intermittently — a
 * single green run is not enough evidence that the serialization actually works.
 */
class ConcurrentReadTest {

    private static final int THREADS = 8;
    private static final int ENTRIES = 5;

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;
    private static List<TableEntry> entries;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("ConcurrentReadTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/basic.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/basic.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);

        entries = new ArrayList<>(ENTRIES);
        var batch = sw.batch();
        for (int i = 0; i < ENTRIES; i++) {
            TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A300000 | (i << 8)), 24))
                    .action("MyIngress.forward").param("port", (i % 8) + 1)
                    .build();
            entries.add(e);
            batch.insert(e);
        }
        batch.execute();
    }

    @AfterAll
    static void stop() {
        if (sw != null) {
            try {
                var b = sw.batch();
                for (TableEntry e : entries) b.delete(e);
                b.execute();
            } catch (RuntimeException ignored) { }
            sw.close();
        }
        if (bmv2 != null) bmv2.close();
    }

    @RepeatedTest(3)
    void concurrentAllReadsReturnConsistentResults() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch fire = new CountDownLatch(1);
            List<CompletableFuture<Integer>> futures = new ArrayList<>(THREADS);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            for (int t = 0; t < THREADS; t++) {
                CompletableFuture<Integer> f = new CompletableFuture<>();
                futures.add(f);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        List<TableEntry> got = sw.read("MyIngress.ipv4_lpm").all();
                        f.complete(got.size());
                    } catch (Throwable th) {
                        firstFailure.compareAndSet(null, th);
                        f.completeExceptionally(th);
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads should reach the gate");
            fire.countDown();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(60, TimeUnit.SECONDS);

            assertNull(firstFailure.get(),
                    () -> "no thread should fail; first failure: " + firstFailure.get());
            for (CompletableFuture<Integer> f : futures) {
                assertEquals(ENTRIES, f.get().intValue(),
                        "every thread must see exactly " + ENTRIES + " entries");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "pool must drain");
        }
    }
}
