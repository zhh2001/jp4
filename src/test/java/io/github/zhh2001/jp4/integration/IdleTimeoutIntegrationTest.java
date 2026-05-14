package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.IdleTimeoutEvent;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.Bytes;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the IdleTimeout dispatch + enable family against
 * a real BMv2 instance running the {@code digest_idle.p4} pipeline.
 * Entries inserted with a positive {@code idleTimeoutNs} are aged out by
 * BMv2's AgeingMonitor (default sweep interval around one second) and
 * reported back to the controller as {@code IdleTimeoutNotification},
 * surfaced through {@link P4Switch#onIdleTimeout}.
 */
class IdleTimeoutIntegrationTest {

    private static final Duration IDLE_WINDOW = Duration.ofSeconds(2);
    private static final long IDLE_NS = IDLE_WINDOW.toNanos();
    /** Generous deadline: BMv2 sweeps at roughly one-second intervals, so an
     *  idle entry should fire within {@code IDLE_WINDOW + ~2 sweeps + grace}. */
    private static final Duration AWAIT_AGE = Duration.ofSeconds(7);

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;

    private final List<IdleTimeoutEvent> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("IdleTimeoutIntegrationTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/digest_idle.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/digest_idle.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (bmv2 != null) bmv2.close();
    }

    @BeforeEach
    void resetListener() throws InterruptedException {
        received.clear();
        // drain residual packet-ins from earlier tests
        while (sw.pollPacketIn(Duration.ofMillis(100)).isPresent()) { /* discard */ }
    }

    @AfterEach
    void deleteEntry() {
        // Best-effort cleanup of mac_learn entries left from each test so the
        // next test starts from a known-empty table state.
        try {
            for (byte[] mac : new byte[][]{
                    macForLead((byte) 0x11),
                    macForLead((byte) 0x22),
                    macForLead((byte) 0x33),
                    macForLead((byte) 0x44)
            }) {
                try {
                    sw.delete(macLearnEntry(mac, /*withIdleTimeout=*/ 0L));
                } catch (RuntimeException ignored) { /* entry may not exist */ }
            }
        } catch (RuntimeException ignored) { /* tolerate cleanup failures */ }
    }

    @Test
    void entryWithIdleTimeoutFiresNotificationAfterIdle() {
        sw.onIdleTimeout(received::add);
        byte[] mac = macForLead((byte) 0x11);
        sw.insert(macLearnEntry(mac, IDLE_NS));

        Awaitility.await().atMost(AWAIT_AGE).until(() -> !received.isEmpty());
        assertFalse(received.isEmpty(),
                "BMv2 should have aged out the entry and sent IdleTimeoutNotification");
    }

    @Test
    void idleTimeoutEventCarriesParsedEntries() {
        sw.onIdleTimeout(received::add);
        byte[] mac = macForLead((byte) 0x22);
        sw.insert(macLearnEntry(mac, IDLE_NS));

        Awaitility.await().atMost(AWAIT_AGE).until(() -> !received.isEmpty());
        IdleTimeoutEvent evt = received.get(0);
        assertNotNull(evt, "IdleTimeoutEvent should have arrived");
        assertFalse(evt.entries().isEmpty(),
                "IdleTimeoutEvent.entries() should be non-empty");
        TableEntry parsed = evt.entries().get(0);
        assertEquals("MyIngress.mac_learn", parsed.tableName(),
                "parsed entry's tableName should match the inserted one");
    }

    @Test
    void entryHitResetsIdleTimer() throws InterruptedException {
        sw.onIdleTimeout(received::add);
        byte[] mac = macForLead((byte) 0x33);
        sw.insert(macLearnEntry(mac, IDLE_NS));

        // Pulse traffic matching the entry every ~700ms for 4 seconds — well
        // longer than IDLE_NS — to keep the entry continuously hit. The hits
        // reset BMv2's idle timer for the entry, so no IdleTimeoutNotification
        // should fire within this window.
        long deadlineNs = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadlineNs) {
            sw.send(packetWithSrcMac(mac));
            Thread.sleep(700);
        }
        assertTrue(received.isEmpty(),
                "no IdleTimeoutNotification should fire while entry is being hit; got "
                        + received);
    }

    @Test
    void noListenerNoNotificationButNoCrash() throws InterruptedException {
        // No onIdleTimeout registration here. Insert an entry and let it age
        // out; BMv2 will send the notification but jp4 dispatches with a null
        // listener (no-op) and the switch must stay healthy.
        byte[] mac = macForLead((byte) 0x44);
        sw.insert(macLearnEntry(mac, IDLE_NS));

        TimeUnit.SECONDS.sleep(AWAIT_AGE.toSeconds());

        // Now attach a listener and verify the switch is still operational by
        // sending one more packet through the PacketIn loopback and observing
        // it returns — proves dispatchIdleTimeout's null-listener path didn't
        // poison the inbound thread.
        AtomicReference<Boolean> seenPacket = new AtomicReference<>(false);
        sw.onPacketIn(p -> seenPacket.set(true));
        sw.send(packetWithSrcMac(macForLead((byte) 0x77)));
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(seenPacket::get);
        assertTrue(seenPacket.get(),
                "switch should still process PacketIn after a listener-less idle event");
    }

    // ---------- helpers ---------------------------------------------------

    private static byte[] macForLead(byte lead) {
        return new byte[]{lead, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff};
    }

    private static TableEntry macLearnEntry(byte[] mac, long idleTimeoutNs) {
        var b = TableEntry.in("MyIngress.mac_learn")
                .match("hdr.ethernet.srcAddr", new Match.Exact(Bytes.of(mac)))
                .action("MyIngress.mac_seen");
        if (idleTimeoutNs > 0L) {
            b.idleTimeoutNs(idleTimeoutNs);
        }
        return b.build();
    }

    private static PacketOut packetWithSrcMac(byte[] src) {
        byte[] frame = new byte[14];
        byte[] dst = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        System.arraycopy(dst, 0, frame, 0, 6);
        System.arraycopy(src, 0, frame, 6, 6);
        frame[12] = 0x08; frame[13] = 0x00;
        return PacketOut.builder()
                .payload(Bytes.of(frame))
                .metadata("egress_port", Bytes.of(new byte[]{0, 1}))
                .build();
    }
}
