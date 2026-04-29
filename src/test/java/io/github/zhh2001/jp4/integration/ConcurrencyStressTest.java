package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.MastershipStatus;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.ElectionId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency stress tests for {@link P4Switch}'s read-only accessors and the
 * close path. The two scenarios cover the load-bearing concurrent-access patterns:
 * <ol>
 *   <li>16 threads × 1000 iterations of read-only queries — proves no NPE / data
 *       race / inconsistent return.</li>
 *   <li>Latch-coordinated close at the midpoint of a parallel query workload —
 *       proves close() is thread-safe with respect to in-flight queries and that
 *       no thread ever sees a non-{@link P4ConnectionException} failure.</li>
 * </ol>
 *
 * <p>All scenarios are {@code @RepeatedTest(5)} because concurrency bugs surface
 * intermittently; a single run is not enough evidence.
 */
class ConcurrencyStressTest {

    @BeforeAll
    static void env() {
        BMv2TestSupport.checkEnvironment();
    }

    /**
     * Test 1: 16 threads run 1000 iterations each of all four query methods. Asserts
     * no exception escapes any thread and the values returned match the immutable
     * fields of the switch — proves there's no torn read or unexpected null on the
     * common get path.
     */
    @RepeatedTest(5)
    void parallelQueriesNeverThrowOrTearReads() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("stressQueries").start();
             P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                     .deviceId(0)
                     .electionId(ElectionId.of(33L))
                     .asPrimary()) {

            final int threads = 16;
            final int iterations = 1000;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < iterations; n++) {
                            // Each immutable accessor must always return the value
                            // we configured at construction.
                            assertEquals(0L, sw.deviceId());
                            assertEquals(ElectionId.of(33L), sw.electionId());
                            assertEquals("127.0.0.1:" + bmv2.grpcPort(), sw.address());
                            // The mutable ones must never return null and must agree
                            // with each other.
                            MastershipStatus snapshot = sw.mastership();
                            assertNotNull(snapshot, "mastership() must never return null while open");
                            assertEquals(!snapshot.isLost(), sw.isPrimary(),
                                    "isPrimary() must agree with mastership()");
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS),
                    "all 16 threads must finish 1000 iterations within 30s");
            pool.shutdownNow();

            if (!failures.isEmpty()) {
                AssertionError agg = new AssertionError(
                        "Stress queries produced " + failures.size() + " failure(s); first: " + failures.get(0));
                failures.forEach(agg::addSuppressed);
                throw agg;
            }
        }
    }

    /**
     * Test 2: 8 threads loop calling read-only accessors. The main thread fires
     * {@link P4Switch#close()} exactly when every worker has finished its first
     * half of iterations (coordinated by {@code midGate}). After close, workers
     * may either still observe a normal value (if the close hasn't propagated to
     * their read window yet) or see a {@link P4ConnectionException} carrying
     * "closed" — anything else (NPE, IllegalStateException, etc.) fails the test.
     *
     * <p>Latch coordination:
     * <pre>
     *   startGate.countDown()  → all workers run iter 1..N/2
     *   midGate.await()        → main thread waits until every worker hit N/2
     *   sw.close()             → real "mid-stream" close
     *   endGate.await(timeout) → main waits for all workers to finish
     * </pre>
     */
    @RepeatedTest(5)
    void closeMidStreamSurfacesAsP4ConnectionExceptionOrNoFailure() throws Exception {
        final int threads = 8;
        final int iterations = 200;

        try (BMv2TestSupport bmv2 = new BMv2TestSupport("stressCloseMid").start()) {
            P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .electionId(11L).asPrimary();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch midGate = new CountDownLatch(threads);
            CountDownLatch endGate = new CountDownLatch(threads);
            List<Throwable> rogueFailures = new CopyOnWriteArrayList<>();
            AtomicLong preCloseSuccesses = new AtomicLong();
            AtomicLong postCloseConnectExceptions = new AtomicLong();
            AtomicLong postCloseStillSucceeded = new AtomicLong();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int n = 0; n < iterations; n++) {
                            try {
                                // Touch every accessor — any NPE on a closed switch
                                // would be caught by the outer Throwable branch.
                                sw.address();
                                sw.deviceId();
                                sw.electionId();
                                sw.mastership();
                                sw.isPrimary();
                                preCloseSuccesses.incrementAndGet();
                            } catch (P4ConnectionException p4) {
                                // Expected once close() lands; verify the message
                                // tells the user what happened.
                                if (!p4.getMessage().contains("closed")) {
                                    rogueFailures.add(new AssertionError(
                                            "P4ConnectionException after close should mention 'closed'; got: "
                                                    + p4.getMessage(), p4));
                                } else if (n < iterations / 2) {
                                    // Should not see a "closed" exception before
                                    // we've signalled midGate.
                                    rogueFailures.add(new AssertionError(
                                            "Unexpected closed exception before midGate", p4));
                                } else {
                                    postCloseConnectExceptions.incrementAndGet();
                                }
                            } catch (RuntimeException unexpected) {
                                rogueFailures.add(unexpected);
                            }
                            if (n == iterations / 2) {
                                midGate.countDown();
                            }
                        }
                        // If we made it through the entire loop without ever seeing a
                        // closed exception, count it (close raced ahead of our
                        // last accessor read).
                        if (postCloseConnectExceptions.get() == 0) {
                            postCloseStillSucceeded.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        rogueFailures.add(t);
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(midGate.await(15, TimeUnit.SECONDS),
                    "all threads must reach iteration N/2 within 15s");

            sw.close();   // the "mid-stream" close

            assertTrue(endGate.await(15, TimeUnit.SECONDS),
                    "all threads must terminate within 15s after close");
            pool.shutdownNow();

            if (!rogueFailures.isEmpty()) {
                AssertionError agg = new AssertionError(
                        "Concurrent close produced " + rogueFailures.size() + " unclassified failure(s);"
                                + " first: " + rogueFailures.get(0)
                                + "; preCloseSuccesses=" + preCloseSuccesses.get()
                                + " postCloseConnectExceptions=" + postCloseConnectExceptions.get()
                                + " postCloseStillSucceeded=" + postCloseStillSucceeded.get());
                rogueFailures.forEach(agg::addSuppressed);
                throw agg;
            }
            // Sanity: at least the pre-close half should have run on every thread.
            assertTrue(preCloseSuccesses.get() >= threads * (iterations / 2L),
                    "expected at least " + (threads * (iterations / 2L))
                            + " pre-close successes, got " + preCloseSuccesses.get());
        }
    }
}
