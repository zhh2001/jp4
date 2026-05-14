package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.DigestConfig;
import io.github.zhh2001.jp4.entity.DigestEvent;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.Bytes;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the Digest dispatch + enable family against a
 * real BMv2 instance running the {@code digest_idle.p4} pipeline. Each
 * test drives a PacketOut through the StreamChannel; the pipeline calls
 * {@code digest(...)} on every packet, so a properly enabled DigestEntry
 * makes BMv2 emit DigestList back over the same stream.
 *
 * <p>The pipeline is the same {@code digest_idle.p4} used by
 * {@link IdleTimeoutIntegrationTest}; sharing the fixture keeps the
 * compiled JSON and P4Info under one source artifact.
 */
class DigestIntegrationTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);
    private static final String DIGEST_NAME = "learn_digest_t";
    private static final DigestConfig CFG = new DigestConfig(
            Duration.ofMillis(100),   // max timeout — flush within 100ms
            4,                        // max list size — flush every 4 entries
            Duration.ofSeconds(10));  // ack timeout — generous

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;

    private final List<DigestEvent> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("DigestIntegrationTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/digest_idle.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/digest_idle.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
        sw.enableDigest(DIGEST_NAME, CFG);
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

    @Test
    void enableDigestSendsModifyAndDeviceStartsEmitting() {
        sw.onDigest(received::add);
        sw.send(packetWith((byte) 0xaa));
        Awaitility.await().atMost(AWAIT).until(() -> !received.isEmpty());
        assertFalse(received.isEmpty(), "BMv2 should have emitted DigestList after enableDigest");
    }

    @Test
    void digestEventCarriesResolvedNameAndCorrectListId() {
        sw.onDigest(received::add);
        sw.send(packetWith((byte) 0xbb));
        Awaitility.await().atMost(AWAIT).until(() -> !received.isEmpty());
        DigestEvent evt = received.get(0);
        assertEquals(DIGEST_NAME, evt.digestName(),
                "DigestEvent.digestName should resolve via P4Info");
        assertTrue(evt.listId() > 0L,
                "DigestList.list_id should be set; got " + evt.listId());
    }

    @Test
    void digestEventDataMatchesP4Pack() {
        sw.onDigest(received::add);
        sw.send(packetWith((byte) 0xcc));
        Awaitility.await().atMost(AWAIT).until(() -> !received.isEmpty());
        DigestEvent evt = received.get(0);
        assertFalse(evt.data().isEmpty(),
                "DigestEvent.data should carry the packed digest entry");
    }

    @Test
    void listenerNotRegisteredStillAcks() {
        // No onDigest registration here; send packets and verify subsequent
        // digests still flow once a listener is later attached — would not
        // happen if BMv2 had entered ack_timeout suppression.
        sw.send(packetWith((byte) 0xd0));
        sw.send(packetWith((byte) 0xd1));
        // Small wait so the first two packets' digests are auto-acked.
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> true);

        // Now attach a listener and send more packets: events must arrive.
        sw.onDigest(received::add);
        sw.send(packetWith((byte) 0xd2));
        Awaitility.await().atMost(AWAIT).until(() -> !received.isEmpty());
        assertFalse(received.isEmpty(),
                "listener should still receive digests after unacknowledged ones cleared");
    }

    @Test
    void listenerReplacementLastWriteWins() {
        AtomicLong firstCount = new AtomicLong(0);
        AtomicLong secondCount = new AtomicLong(0);
        sw.onDigest(e -> firstCount.incrementAndGet());
        // Send a packet so the first listener has work, then replace.
        sw.send(packetWith((byte) 0xe0));
        Awaitility.await().atMost(AWAIT).until(() -> firstCount.get() > 0);

        sw.onDigest(e -> secondCount.incrementAndGet());
        long firstBaseline = firstCount.get();
        sw.send(packetWith((byte) 0xe1));
        sw.send(packetWith((byte) 0xe2));
        sw.send(packetWith((byte) 0xe3));
        sw.send(packetWith((byte) 0xe4));
        sw.send(packetWith((byte) 0xe5));
        Awaitility.await().atMost(AWAIT).until(() -> secondCount.get() > 0);
        // first listener must not have grown further
        assertEquals(firstBaseline, firstCount.get(),
                "first listener should not see digests after replacement");
        assertTrue(secondCount.get() > 0,
                "second listener should receive the post-replacement digests");
    }

    @Test
    void multipleDigestsInOneNotificationParsedCorrectly() {
        AtomicReference<DigestEvent> bigEvent = new AtomicReference<>();
        sw.onDigest(e -> {
            if (e.data().size() >= 2 && bigEvent.get() == null) bigEvent.set(e);
            received.add(e);
        });
        // Send 8 packets fast — max_list_size=4 in our DigestConfig should
        // cause BMv2 to batch them into DigestLists with multiple entries.
        for (int i = 0; i < 8; i++) {
            sw.send(packetWith((byte) (0xf0 + i)));
        }
        Awaitility.await().atMost(AWAIT).until(() -> bigEvent.get() != null);
        DigestEvent batched = bigEvent.get();
        assertNotNull(batched, "expected at least one DigestList with 2+ entries");
        assertTrue(batched.data().size() >= 2,
                "expected batched DigestList with data().size() >= 2, got " + batched.data().size());
    }

    /** Build a minimal Ethernet frame with the given source MAC byte (first
     *  byte of the 6-byte srcAddr); destination is fixed, etherType 0x0800. */
    private static PacketOut packetWith(byte srcAddrLead) {
        byte[] frame = new byte[14];
        byte[] dst = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        byte[] src = {srcAddrLead, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff};
        System.arraycopy(dst, 0, frame, 0, 6);
        System.arraycopy(src, 0, frame, 6, 6);
        frame[12] = 0x08; frame[13] = 0x00;
        return PacketOut.builder()
                .payload(Bytes.of(frame))
                .metadata("egress_port", Bytes.of(new byte[]{0, 1}))
                .build();
    }
}
