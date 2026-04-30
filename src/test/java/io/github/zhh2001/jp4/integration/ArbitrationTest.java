package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.MastershipStatus;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.ElectionId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-BMv2 tests for the arbitration path, covering scenarios a / b / c / d / e / f
 * from the 4B test plan. Uses a single BMv2 instance shared across tests via
 * {@code @BeforeAll}/{@code @AfterAll}: every test cleans up its own
 * {@code P4Switch} via try-with-resources, so BMv2 is back to a no-client state
 * before the next test runs. Order-independent.
 */
class ArbitrationTest {

    private static BMv2TestSupport bmv2;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("ArbitrationTest").start();
    }

    @AfterAll
    static void stop() {
        if (bmv2 != null) bmv2.close();
    }

    /** Scenario a + c: connectAsPrimary defaults; isPrimary returns true; deviceId/electionId match defaults. */
    @Test
    void connectAsPrimaryWithDefaults() {
        try (P4Switch sw = P4Switch.connectAsPrimary(bmv2.grpcAddress())) {
            assertTrue(sw.isPrimary(), "must hold primary after connectAsPrimary");
            assertEquals(0L, sw.deviceId(), "default deviceId");
            assertEquals(ElectionId.of(1L), sw.electionId(), "default electionId");
            assertInstanceOf(MastershipStatus.Acquired.class, sw.mastership());
            assertEquals(ElectionId.of(1L),
                    ((MastershipStatus.Acquired) sw.mastership()).ourElectionId());
        }
    }

    /** Scenario b: connector chain with custom electionId (BMv2 itself runs deviceId=0). */
    @Test
    void connectorWithCustomElectionId() {
        ElectionId myEid = ElectionId.of(0xCAFEL);
        try (P4Switch sw = P4Switch.connect(bmv2.grpcAddress())
                .deviceId(0)
                .electionId(myEid)
                .asPrimary()) {
            assertTrue(sw.isPrimary());
            assertEquals(myEid, sw.electionId());
            assertEquals(myEid, ((MastershipStatus.Acquired) sw.mastership()).ourElectionId());
        }
    }

    /**
     * Scenario d: a switch obtained via {@code asSecondary()} (because a primary already
     * holds a higher election id) refuses writes with {@link P4ConnectionException}.
     * Passing {@code null} as the entry is intentional: the primary-state gate runs
     * before any entry validation, so the entry value is irrelevant.
     *
     * TODO(phase 5+): when {@code TableEntry.build()} is implemented, replace
     * {@code null} with a real entry to ensure null-check ordering doesn't mask the
     * primary-gate behaviour.
     */
    @Test
    void secondaryRejectsWritesWithP4ConnectionException() {
        try (P4Switch primary = P4Switch.connect(bmv2.grpcAddress())
                .electionId(100L).asPrimary()) {
            assertTrue(primary.isPrimary());

            try (P4Switch secondary = P4Switch.connect(bmv2.grpcAddress())
                    .electionId(50L).asSecondary()) {
                assertFalse(secondary.isPrimary(), "lower election id must lose primary");
                assertInstanceOf(MastershipStatus.Lost.class, secondary.mastership());

                P4ConnectionException ex = assertThrows(P4ConnectionException.class,
                        () -> secondary.insert(null));
                assertTrue(ex.getMessage().contains("not primary"),
                        "exception message should explain the gate, got: " + ex.getMessage());
            }
        }
    }

    /**
     * Scenario e: two clients on the same device; the higher election id wins primary,
     * the lower one is demoted to secondary on receipt of the next arbitration message.
     *
     * <p>The lower client's listener may receive more than one mastership event when
     * B's connect is retried for the BMv2 Docker mastership-transition quirk (see
     * NOTES.md). Strict assertions on {@code previousElectionId} therefore use the
     * <b>first</b> Lost event in the listener log — that one corresponds to the
     * Acquired→Lost transition with full provenance. Any subsequent Lost events from
     * retry-induced refreshes carry {@code null} for {@code previousElectionId}
     * because {@code parseArbitration} only populates it when transitioning out of
     * the Acquired state.
     */
    @Test
    void higherElectionIdWinsAndDemotesIncumbent() throws Exception {
        try (P4Switch lower = P4Switch.connect(bmv2.grpcAddress())
                .electionId(1L).asPrimary()) {
            assertTrue(lower.isPrimary(), "alone, low-id client holds primary");
            java.util.List<MastershipStatus> events =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            lower.onMastershipChange(events::add);

            P4Switch higher = io.github.zhh2001.jp4.testsupport.BMv2TestSupport
                    .connectPrimaryWithRetry(bmv2.grpcAddress(), 99L, 3);
            try {
                assertTrue(higher.isPrimary(), "higher election id steals primary");

                // The demotion message arrives asynchronously; wait for it to land.
                await().atMost(Duration.ofSeconds(5))
                        .pollInterval(Duration.ofMillis(50))
                        .until(() -> !lower.isPrimary());

                assertFalse(lower.isPrimary(), "lower election id has been demoted");
                MastershipStatus.Lost finalState = assertInstanceOf(
                        MastershipStatus.Lost.class, lower.mastership());
                assertEquals(ElectionId.of(99L), finalState.currentPrimaryElectionId(),
                        "final Lost snapshot should report new primary's election id");

                // Strict provenance assertions on the FIRST Lost event in the log.
                assertTrue(events.stream().anyMatch(e -> e instanceof MastershipStatus.Lost),
                        "must observe at least one Lost event, got: " + events);
                MastershipStatus.Lost firstLost = events.stream()
                        .filter(e -> e instanceof MastershipStatus.Lost)
                        .map(e -> (MastershipStatus.Lost) e)
                        .findFirst()
                        .orElseThrow();
                assertEquals(ElectionId.of(99L), firstLost.currentPrimaryElectionId(),
                        "first Lost should report new primary's election id");
                assertEquals(ElectionId.of(1L), firstLost.previousElectionId(),
                        "first Lost should report the election id we held (1)");
            } finally {
                higher.close();
            }
        }
    }

    /** Scenario f: close releases the slot; a fresh connect with the same election id succeeds. */
    @Test
    void closeThenReconnectWithSameElectionIdSucceeds() {
        ElectionId reused = ElectionId.of(7L);

        try (P4Switch first = P4Switch.connect(bmv2.grpcAddress())
                .electionId(reused).asPrimary()) {
            assertTrue(first.isPrimary());
        }
        try (P4Switch second = P4Switch.connect(bmv2.grpcAddress())
                .electionId(reused).asPrimary()) {
            assertTrue(second.isPrimary(), "reconnecting with the same election id should win primary again");
            assertEquals(reused, second.electionId());
        }
    }
}
