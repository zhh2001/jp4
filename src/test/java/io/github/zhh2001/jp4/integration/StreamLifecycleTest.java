package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stream-lifecycle tests. Each test gets its own BMv2 instance because scenario h
 * forcibly kills the server, which would poison a shared instance. Per-test BMv2
 * spawn cost is around 1 s, and only two tests live here, so the total overhead is
 * acceptable.
 */
class StreamLifecycleTest {

    @BeforeAll
    static void env() {
        BMv2TestSupport.checkEnvironment();
    }

    /**
     * Scenario h: when BMv2 is killed mid-stream the next operation surfaces
     * {@link P4ConnectionException}. The stream's onError fires on Netty; the broken
     * state propagates to user-visible methods. Awaitility polls because the gRPC
     * stack reports the disconnect asynchronously.
     */
    @Test
    void killingBmv2SurfacesAsP4ConnectionException() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("killMidStream").start();
             P4Switch sw = P4Switch.connectAsPrimary(bmv2.grpcAddress())) {
            assertTrue(sw.isPrimary());

            bmv2.killForciblyNow();

            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() ->
                            assertThrows(P4ConnectionException.class, sw::asPrimary));
        }
    }

    /**
     * Scenario i: {@code close()} on a switch with a healthy active stream completes
     * within a bounded time (no deadlock between the close path and the inbound
     * observer). After close, write-side calls surface "switch is closed" — never
     * UnsupportedOperationException — confirming the gate runs before any business
     * logic.
     */
    @Test
    void closeOnActiveStreamReleasesPromptlyAndRejectsLaterWrites() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("closeActive").start()) {
            P4Switch sw = P4Switch.connectAsPrimary(bmv2.grpcAddress());
            assertTrue(sw.isPrimary());

            assertTimeoutPreemptively(Duration.ofSeconds(10), sw::close,
                    "close() should release within 10s on an active stream");

            P4ConnectionException afterClose = assertThrows(
                    P4ConnectionException.class, () -> sw.insert(null));
            assertTrue(afterClose.getMessage().contains("closed"),
                    "after close, write should report closed-switch state, got: "
                            + afterClose.getMessage());

            // Idempotent close — second call must not throw.
            sw.close();
        }
    }
}
