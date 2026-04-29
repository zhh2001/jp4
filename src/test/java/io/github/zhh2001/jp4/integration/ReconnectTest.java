package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.MastershipStatus;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.ReconnectPolicy;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.ElectionId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reconnect-side coverage for scenarios a / b / c / d / e from the 4C plan.
 * Each test gets its own BMv2 instance — most of these kill the server, so a shared
 * one would poison neighbours. Concurrency-sensitive paths use {@code @RepeatedTest(3)}
 * so a single flaky failure surfaces.
 */
class ReconnectTest {

    @BeforeAll
    static void env() {
        BMv2TestSupport.checkEnvironment();
    }

    /** Scenario a: stream breaks, BMv2 restarts on the same port, switch reconnects
     *  and re-arbitrates with the same election id; listener observes Lost then
     *  Acquired. */
    @RepeatedTest(3)
    void autoReconnectsAfterStreamBreakAndPreservesElectionId() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("autoReconnect").start()) {
            ElectionId myEid = ElectionId.of(42L);
            List<MastershipStatus> events = new CopyOnWriteArrayList<>();

            try (P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .electionId(myEid)
                    .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                            Duration.ofMillis(100), Duration.ofMillis(500), 20))
                    .asPrimary()) {
                sw.onMastershipChange(events::add);
                assertTrue(sw.isPrimary());

                bmv2.killForciblyNow();
                bmv2.restart();

                await().atMost(Duration.ofSeconds(15))
                        .pollInterval(Duration.ofMillis(50))
                        .until(() -> events.size() >= 2);

                assertInstanceOf(MastershipStatus.Lost.class, events.get(0),
                        "first event must be Lost from the stream break");
                MastershipStatus second = events.get(1);
                assertInstanceOf(MastershipStatus.Acquired.class, second,
                        "second event must be Acquired from the reconnect");
                assertEquals(myEid, ((MastershipStatus.Acquired) second).ourElectionId(),
                        "election id must persist across reconnect (trap 3)");

                await().atMost(Duration.ofSeconds(2)).until(sw::isPrimary);
            }
        }
    }

    /** Scenario b: ReconnectPolicy is consulted with monotonically-increasing attempt
     *  numbers; the recorded delays form an exponential, not a flat, sequence. */
    @Test
    void exponentialBackoffActuallyBacksOff() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("backoffShape").start()) {
            List<Long> delays = new CopyOnWriteArrayList<>();
            ReconnectPolicy base = ReconnectPolicy.exponentialBackoff(
                    Duration.ofMillis(50), Duration.ofMillis(800), 20);
            ReconnectPolicy recording = attempt -> {
                OptionalLong d = base.nextDelayMillis(attempt);
                d.ifPresent(delays::add);
                return d;
            };

            try (P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .reconnectPolicy(recording)
                    .asPrimary()) {
                assertTrue(sw.isPrimary());
                bmv2.killForciblyNow();   // no restart — let attempts pile up

                await().atMost(Duration.ofSeconds(5))
                        .pollInterval(Duration.ofMillis(50))
                        .until(() -> delays.size() >= 4);

                // exponential: 50, 100, 200, 400 (then capped at 800).
                assertEquals(50L, delays.get(0));
                assertEquals(100L, delays.get(1));
                assertEquals(200L, delays.get(2));
                assertEquals(400L, delays.get(3));
            }
        }
    }

    /** Scenario c: after maxRetries, the policy returns empty and the switch
     *  surfaces P4ConnectionException on the next user-visible call. */
    @Test
    void giveUpAfterMaxRetries() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("giveUp").start()) {
            try (P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                            Duration.ofMillis(50), Duration.ofMillis(100), 3))
                    .asPrimary()) {
                assertTrue(sw.isPrimary());
                bmv2.killForciblyNow();

                // Three retries with delays 50 + 100 + 100 ms ≈ 0.25 s, plus per-attempt
                // gRPC failure (~50 ms each on localhost). Bound at 5 s for slack.
                await().atMost(Duration.ofSeconds(5))
                        .pollInterval(Duration.ofMillis(50))
                        .untilAsserted(() ->
                                assertThrows(P4ConnectionException.class, sw::asPrimary));
            }
        }
    }

    /** Scenario d: close() during the scheduler's idle wait between attempts must
     *  return promptly — proves cancel(false) on nextReconnect plus the closing-flag
     *  poll on the runnable's entry actually short-circuits a long wait. */
    @RepeatedTest(3)
    void closeDuringReconnectSchedulerWait() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("closeWait").start()) {
            List<Long> consultations = new CopyOnWriteArrayList<>();
            ReconnectPolicy longSleepPolicy = attempt -> {
                consultations.add(System.nanoTime());
                return OptionalLong.of(5_000L);   // 5 s — far longer than the close timeout
            };
            P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .reconnectPolicy(longSleepPolicy)
                    .asPrimary();
            try {
                assertTrue(sw.isPrimary());
                bmv2.killForciblyNow();

                // Wait until the scheduler is actually parked on its 5-second sleep.
                await().atMost(Duration.ofSeconds(2))
                        .pollInterval(Duration.ofMillis(20))
                        .until(() -> !consultations.isEmpty());

                // close() must return well under 5 s.
                assertTimeoutPreemptively(Duration.ofSeconds(3), sw::close,
                        "close() must short-circuit the scheduler wait");
            } finally {
                sw.close();   // idempotent; exercises the "called twice" path too
            }
        }
    }

    /** Scenario e: close() called immediately after the stream breaks may catch the
     *  reconnect attempt at any phase (entry / channel build / arbitration await).
     *  RepeatedTest gives statistical coverage of all phases. */
    @RepeatedTest(3)
    void closeImmediatelyAfterBreakAlwaysReleases() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("closeImmediate").start()) {
            P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                    .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                            Duration.ofMillis(20), Duration.ofMillis(50), 100))
                    .asPrimary();
            try {
                assertTrue(sw.isPrimary());
                bmv2.killForciblyNow();
                // No deliberate sleep: the reconnect runnable may be in any of the
                // seven polling-checkpoint windows when close fires.
                assertTimeoutPreemptively(Duration.ofSeconds(3), sw::close,
                        "close() must release regardless of which reconnect-attempt phase is in flight");
            } finally {
                sw.close();
            }
        }
    }
}
