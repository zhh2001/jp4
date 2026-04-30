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
 * Scenario m: many threads inserting against the same {@link P4Switch} concurrently
 * must all succeed when keys are distinct. Proves that the outbound serial executor
 * inside the switch does not lose, reorder, or corrupt updates under load, and that
 * synchronous {@code insert(...)} from N caller threads is safe with respect to the
 * single owning gRPC stream.
 *
 * <p>Repeated three times because concurrency hazards are intermittent — a single
 * green run is not enough evidence that the serialization actually works.
 */
class ConcurrentWriteTest {

    private static final int THREADS = 16;

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("ConcurrentWriteTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/basic.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/basic.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (bmv2 != null) bmv2.close();
    }

    @RepeatedTest(3)
    void concurrentInsertsWithDistinctKeysAllSucceed() throws Exception {
        // Per-iteration entries so retried iterations don't collide on duplicate keys.
        List<TableEntry> entries = new ArrayList<>(THREADS);
        long iterTag = System.nanoTime() & 0xff;        // bottom byte of /24 keeps within u32
        for (int i = 0; i < THREADS; i++) {
            int dst = 0x0A_20_00_00 | ((int) (iterTag << 8)) | i;
            entries.add(TableEntry.in("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(dst), 32))
                    .action("MyIngress.forward").param("port", (i % 8) + 1)
                    .build());
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch fire = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>(THREADS);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            for (TableEntry e : entries) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                futures.add(f);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        sw.insert(e);
                        f.complete(null);
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                        f.completeExceptionally(t);
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads should reach the gate");
            fire.countDown();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(60, TimeUnit.SECONDS);

            assertNull(firstFailure.get(),
                    () -> "no thread should fail; first failure: " + firstFailure.get());

            // Cleanup as a single batch so the next iteration starts clean even if iterTag collides.
            var batch = sw.batch();
            for (TableEntry e : entries) batch.delete(e);
            batch.execute();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "pool must drain");
        }
    }
}
