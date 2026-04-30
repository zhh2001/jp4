package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Many caller threads issuing {@code sw.send(PacketOut)} concurrently against a
 * shared {@link P4Switch}. Proves the outbound serial executor inside the switch
 * does not race packet writes — every PacketOut reaches the device, every
 * loopback PacketIn comes back, no thread fails. Uses the same packet_io.p4
 * loopback pipeline as {@link PacketIoTest}.
 *
 * <p>Repeated three times because concurrency hazards are intermittent.
 */
class ConcurrentSendTest {

    private static final int THREADS = 16;

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("ConcurrentSendTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/packet_io.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/packet_io.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (bmv2 != null) bmv2.close();
    }

    @RepeatedTest(3)
    void concurrentSendAllReachTheDevice() throws Exception {
        AtomicInteger received = new AtomicInteger();
        // Counts received-and-attributable packets across all threads.
        sw.onPacketIn(p -> received.incrementAndGet());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch fire = new CountDownLatch(1);
            List<CompletableFuture<Void>> sendFutures = new ArrayList<>(THREADS);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            for (int i = 0; i < THREADS; i++) {
                final int idx = i;
                CompletableFuture<Void> f = new CompletableFuture<>();
                sendFutures.add(f);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        // Use distinct payloads so we know each send is independently constructed.
                        sw.send(PacketOut.builder()
                                .payload(new byte[]{(byte) idx, 0x01, 0x02})
                                .metadata("egress_port", (idx % 8) + 1)
                                .build());
                        f.complete(null);
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                        f.completeExceptionally(t);
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads should reach the gate");
            fire.countDown();

            CompletableFuture.allOf(sendFutures.toArray(CompletableFuture[]::new))
                    .get(60, TimeUnit.SECONDS);
            assertNull(firstFailure.get(),
                    () -> "no thread should fail; first failure: " + firstFailure.get());

            // Await loopback delivery via Awaitility — no Thread.sleep here.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> received.get() >= THREADS);
            assertEquals(THREADS, received.get(),
                    "every PacketOut must round-trip as a PacketIn; got " + received.get());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "pool must drain");
        }
    }
}
