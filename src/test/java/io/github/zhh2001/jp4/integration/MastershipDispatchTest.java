package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.MastershipStatus;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Listener-dispatch coverage for scenarios f / g / h. Verifies that
 * {@code onMastershipChange} callbacks can safely call back into the switch
 * (asPrimary / close) without deadlock, and that the inbound queue preserves event
 * order even when events arrive in quick succession.
 *
 * <p>All tests are {@code @RepeatedTest(3)}: the scheduling between the inbound Netty
 * thread, the user callback executor, and the close-coordinator thread is sensitive
 * to timing, so we run each three times to catch flaky behaviour.
 */
class MastershipDispatchTest {

    @BeforeAll
    static void env() {
        BMv2TestSupport.checkEnvironment();
    }

    /** Scenario f: a listener that calls {@code asPrimary()} from inside the callback
     *  must not deadlock. The re-arbitration is doomed to fail (B holds higher
     *  election id), so the call must surface a {@code P4ConnectionException} (either
     *  {@code P4ArbitrationLost} when the device explicitly denies, or the parent
     *  type when the device does not respond to the duplicate MAU at all — BMv2
     *  exhibits the latter behaviour). The key invariant under test is that the
     *  callback completes within a bounded time and no thread is stuck. */
    @RepeatedTest(3)
    void callbackCanCallAsPrimaryWithoutDeadlock() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("cbAsPrimary").start();
             P4Switch a = P4Switch.connect(bmv2.grpcAddress()).electionId(1L).asPrimary()) {

            AtomicReference<Throwable> reArbResult = new AtomicReference<>();
            CountDownLatch reArbDone = new CountDownLatch(1);

            a.onMastershipChange(status -> {
                // Only act on the first Lost event; subsequent demotions (e.g. from
                // the failed re-arbitration response) would loop otherwise.
                if (status.isLost() && reArbDone.getCount() > 0) {
                    try {
                        a.asPrimary();   // expected to throw — see test docstring
                    } catch (Throwable t) {
                        reArbResult.set(t);
                    } finally {
                        reArbDone.countDown();
                    }
                }
            });

            // B's connect uses the retry helper to absorb the BMv2 Docker
            // mastership-transition quirk (see NOTES.md). Without retry, B's first
            // arbitration response occasionally surfaces as onError and the test
            // fails before A's listener can ever run.
            P4Switch b = BMv2TestSupport.connectPrimaryWithRetry(bmv2.grpcAddress(), 99L, 3);
            try {
                assertTrue(b.isPrimary());

                assertTrue(reArbDone.await(15, TimeUnit.SECONDS),
                        "callback's asPrimary must complete (no deadlock)");
                Throwable t = reArbResult.get();
                assertNotNull(t, "callback's asPrimary must surface an outcome");
                assertInstanceOf(P4ConnectionException.class, t,
                        "callback's asPrimary should fail with a connection-class exception, got: " + t);
            } finally {
                b.close();
            }
        }
    }

    /** Scenario g: a listener that calls {@code close()} from inside the callback must
     *  complete in bounded time. Verifies the closer-thread + caller-detection path:
     *  the callback executor is the calling thread, so doClose skips its own
     *  awaitTermination and lets it drain after the callback returns. */
    @RepeatedTest(3)
    void callbackCanCallCloseWithoutDeadlock() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("cbClose").start()) {
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicReference<Throwable> closeError = new AtomicReference<>();

            P4Switch a = P4Switch.connect(bmv2.grpcAddress()).electionId(1L).asPrimary();
            try {
                a.onMastershipChange(status -> {
                    if (status.isLost() && closeReturned.getCount() > 0) {
                        try {
                            a.close();
                        } catch (Throwable t) {
                            closeError.set(t);
                        } finally {
                            closeReturned.countDown();
                        }
                    }
                });

                // B's connect uses the retry helper for the same Docker quirk reason
                // documented on callbackCanCallAsPrimaryWithoutDeadlock above.
                P4Switch b = BMv2TestSupport.connectPrimaryWithRetry(bmv2.grpcAddress(), 99L, 3);
                try {
                    assertTrue(b.isPrimary());

                    assertTrue(closeReturned.await(10, TimeUnit.SECONDS),
                            "close() called from inside the callback must return");
                    assertNull(closeError.get(),
                            "close() from callback should not throw, got: " + closeError.get());
                } finally {
                    b.close();
                }
            } finally {
                a.close();   // idempotent
            }
        }
    }

    /** Scenario h: rapid mastership changes are dispatched to the listener in arrival
     *  order. We trigger demotion (Lost event) then reconnect-via-restart (Acquired
     *  event); the recorded sequence must be exactly [Lost, Acquired]. */
    @RepeatedTest(3)
    void rapidMastershipEventsArriveInOrder() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("eventsOrdered").start()) {
            List<MastershipStatus> events = new CopyOnWriteArrayList<>();
            try (P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .electionId(7L)
                    .reconnectPolicy(io.github.zhh2001.jp4.ReconnectPolicy.exponentialBackoff(
                            Duration.ofMillis(100), Duration.ofMillis(500), 20))
                    .asPrimary()) {
                sw.onMastershipChange(events::add);

                bmv2.killForciblyNow();
                bmv2.restart();

                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(15))
                        .pollInterval(Duration.ofMillis(50))
                        .until(() -> events.size() >= 2);

                // Order assertion: Lost from the break, then Acquired from the reconnect.
                assertInstanceOf(MastershipStatus.Lost.class, events.get(0),
                        "event[0] must be Lost; got " + events.get(0));
                assertInstanceOf(MastershipStatus.Acquired.class, events.get(1),
                        "event[1] must be Acquired; got " + events.get(1));
            }
        }
    }
}
