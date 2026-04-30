package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.PacketMetadataInfo;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@code P4Switch.onPacketIn / pollPacketIn /
 * packetInStream / send / sendAsync} against a real BMv2 instance running the
 * packet_io.p4 pipeline. The pipeline is a controlled loopback: every PacketOut
 * the controller sends comes back as a PacketIn, with the controller's
 * {@code egress_port} reflected as the PacketIn's {@code ingress_port}. This
 * lets each test verify the round-trip without external traffic injection.
 *
 * <p>BMv2 launches with {@code --cpu-port 255} so the device generates
 * StreamChannel PacketIn messages whenever {@code std.egress_spec == 255}.
 */
class PacketIoTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("PacketIoTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/packet_io.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/packet_io.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (bmv2 != null) bmv2.close();
    }

    /**
     * Drain anything still buffered or in flight from prior tests in this class
     * before each test runs. Without this, a PacketIn surfaced by an earlier
     * scenario (e.g. {@code packetInStreamSubscriberSeesPacketThenCancels} sends
     * two packets) could leak into a later test's handler and inflate counters
     * past their expected values. {@code pollPacketIn(200ms)} blocks the full
     * duration when the deque is empty, so the loop exits only after a 200ms
     * window passed with no arrival — a stable-empty signal against both local
     * native and Docker BMv2 round-trip latency.
     */
    @BeforeEach
    void drainResidualPackets() throws InterruptedException {
        while (sw.pollPacketIn(Duration.ofMillis(200)).isPresent()) {
            // discard
        }
    }

    /** Utility: register a one-shot handler returning a future of the first PacketIn. */
    private static CompletableFuture<PacketIn> nextPacketViaHandler() {
        CompletableFuture<PacketIn> f = new CompletableFuture<>();
        sw.onPacketIn(p -> f.complete(p));
        return f;
    }

    /** Scenario a: PacketOut → BMv2 → PacketIn closed loop, byte-level round-trip. */
    @Test
    void packetOutLoopsBackAsPacketInWithMatchingPayload() throws Exception {
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        CompletableFuture<PacketIn> got = nextPacketViaHandler();

        sw.send(PacketOut.builder()
                .payload(payload)
                .metadata("egress_port", 3)
                .build());

        PacketIn p = got.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertArrayEquals(payload, p.payload().toByteArray(),
                "PacketIn payload must byte-match the sent PacketOut");
    }

    /** Scenario b: PacketIn metadata reverse-parse — ingress_port resolves to the
     *  port the controller specified as egress_port, proving the metadata id→name
     *  reverse lookup runs end-to-end against BMv2's wire format. */
    @Test
    void packetInMetadataIngressPortReflectsControllerEgressChoice() throws Exception {
        CompletableFuture<PacketIn> got = nextPacketViaHandler();
        sw.send(PacketOut.builder()
                .payload(new byte[]{0x10, 0x11, 0x12})
                .metadata("egress_port", 5)
                .build());

        PacketIn p = got.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(5, p.metadataInt("ingress_port"),
                "ingress_port should round-trip controller's egress_port choice; got " + p);
    }

    /** Scenario c: PacketOut metadata serialise — the bit-pattern at the wire boundary
     *  must be the canonicalised value (verified via the round-trip; if the device
     *  parsed the wrong byte ordering, ingress_port would not equal what we sent). */
    @Test
    void packetOutMetadataSerialisesAtCorrectWireWidth() throws Exception {
        // Top-of-9bit value — exercises the full bit-width packing.
        CompletableFuture<PacketIn> got = nextPacketViaHandler();
        sw.send(PacketOut.builder()
                .payload(new byte[]{0x20, 0x21})
                .metadata("egress_port", 257)   // 9-bit value with high bit set
                .build());

        PacketIn p = got.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(257, p.metadataInt("ingress_port"),
                "9-bit metadata value must survive the wire round-trip; got " + p);
    }

    /** Scenario d: onPacketIn replaces a prior handler — last-wins (matches
     *  onMastershipChange and v3 §D6). Two-round design: prove the first handler
     *  works, then replace it, then prove the second receives and the first
     *  count does not advance. Avoids races with cross-test dispatch state. */
    @Test
    void onPacketInIsLastWinsReplace() throws Exception {
        java.util.concurrent.atomic.AtomicInteger firstCount = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch firstSaw = new CountDownLatch(1);
        sw.onPacketIn(p -> {
            firstCount.incrementAndGet();
            firstSaw.countDown();
        });
        sw.send(PacketOut.builder().payload(new byte[]{0x30}).metadata("egress_port", 1).build());
        assertTrue(firstSaw.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS),
                "first handler must receive the first round");
        int countAfterRound1 = firstCount.get();

        // Replace; ensure the prior dispatch has fully landed before we send round 2.
        CompletableFuture<PacketIn> sawOnSecond = new CompletableFuture<>();
        sw.onPacketIn(sawOnSecond::complete);

        sw.send(PacketOut.builder().payload(new byte[]{0x31}).metadata("egress_port", 2).build());
        sawOnSecond.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(countAfterRound1, firstCount.get(),
                "first handler must NOT have been called after the replace");
    }

    /** Scenario e: pollPacketIn timeout returns Optional.empty(). */
    @Test
    void pollPacketInTimesOutToEmpty() throws Exception {
        // Drain anything stale first so the test sees a clean queue.
        while (sw.pollPacketIn(Duration.ofMillis(50)).isPresent()) { /* drain */ }

        Optional<PacketIn> p = sw.pollPacketIn(Duration.ofMillis(200));
        assertTrue(p.isEmpty(), "no packets sent → Optional.empty(); got " + p);
    }

    /** Scenario f: packetInStream subscriber sees the packet, then cancels cleanly.
     *  Two subscribers — we cancel A and verify B still receives. The "B got the
     *  second packet" event is the synchronisation point that proves the second
     *  packet was fully dispatched, so the assertion that A did not receive it
     *  is meaningful (no Thread.sleep). */
    @Test
    void packetInStreamSubscriberSeesPacketThenCancels() throws Exception {
        AtomicReference<Flow.Subscription> subA = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger aCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger bCount = new java.util.concurrent.atomic.AtomicInteger();

        sw.packetInStream().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { subA.set(s); s.request(Long.MAX_VALUE); }
            @Override public void onNext(PacketIn p) { aCount.incrementAndGet(); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        sw.packetInStream().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(PacketIn p) { bCount.incrementAndGet(); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });

        // First send: both subscribers should observe.
        sw.send(PacketOut.builder().payload(new byte[]{0x40}).metadata("egress_port", 1).build());
        Awaitility.await().atMost(AWAIT).until(() -> aCount.get() >= 1 && bCount.get() >= 1);
        int aBeforeCancel = aCount.get();

        // Cancel A; subsequent sends must reach B but not A.
        subA.get().cancel();
        sw.send(PacketOut.builder().payload(new byte[]{0x41}).metadata("egress_port", 1).build());
        Awaitility.await().atMost(AWAIT).until(() -> bCount.get() >= 2);
        // B has now seen the second packet — fan-out has fully dispatched. A's
        // count must not have moved past its pre-cancel value.
        assertEquals(aBeforeCancel, aCount.get(),
                "cancelled subscriber must not receive after cancel; A count moved from "
                        + aBeforeCancel + " to " + aCount.get());
    }

    /** Scenario g: multiple subscribers fan-out — both see the same packet. */
    @Test
    void multipleSubscribersBothSeeSamePacket() throws Exception {
        CompletableFuture<PacketIn> a = new CompletableFuture<>();
        CompletableFuture<PacketIn> b = new CompletableFuture<>();
        sw.packetInStream().subscribe(subscriberCompleting(a));
        sw.packetInStream().subscribe(subscriberCompleting(b));

        byte[] payload = new byte[]{0x50, 0x51, 0x52};
        sw.send(PacketOut.builder().payload(payload).metadata("egress_port", 2).build());

        PacketIn pa = a.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        PacketIn pb = b.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertArrayEquals(payload, pa.payload().toByteArray());
        assertArrayEquals(payload, pb.payload().toByteArray());
    }

    /** Scenario h: handler can call sw.send without deadlocking (Trap 2 from the plan). */
    @Test
    void handlerCanCallSendWithoutDeadlock() throws Exception {
        CountDownLatch second = new CountDownLatch(1);
        sw.onPacketIn(p -> {
            try {
                if (p.metadataInt("ingress_port") == 6) {
                    // First receipt → trigger a second send-from-callback.
                    sw.send(PacketOut.builder().payload(p.payload())
                            .metadata("egress_port", 7).build());
                } else if (p.metadataInt("ingress_port") == 7) {
                    second.countDown();
                }
            } catch (RuntimeException ignored) { }
        });
        sw.send(PacketOut.builder().payload(new byte[]{0x60}).metadata("egress_port", 6).build());
        assertTrue(second.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS),
                "handler→send chain must not deadlock");
    }

    /** Scenario i: close() while a subscriber is active completes its stream. */
    @Test
    void closeSwitchCompletesActiveSubscribers() throws Exception {
        // Use a per-test BMv2 / switch so closing it doesn't impact other tests.
        try (BMv2TestSupport perTest = new BMv2TestSupport("packetCloseStream").start();
             P4Switch perSw = P4Switch.connectAsPrimary(perTest.grpcAddress())
                     .bindPipeline(P4Info.fromFile(Path.of("src/test/resources/p4/packet_io.p4info.txtpb")),
                             DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/packet_io.json")))) {

            CompletableFuture<Void> completed = new CompletableFuture<>();
            perSw.packetInStream().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(PacketIn p) { }
                @Override public void onError(Throwable t) { completed.completeExceptionally(t); }
                @Override public void onComplete() { completed.complete(null); }
            });

            perSw.close();
            // close() returns once doClose finishes; subscriber should see onComplete.
            completed.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Validation: PacketOut with an unknown metadata field rejected with known-list. */
    @Test
    void packetOutWithUnknownMetadataRejectedWithKnownList() {
        PacketOut bogus = PacketOut.builder()
                .payload(new byte[]{0x70})
                .metadata("does_not_exist", 1)
                .build();
        P4PipelineException ex = assertThrows(P4PipelineException.class, () -> sw.send(bogus));
        assertTrue(ex.getMessage().contains("known:"),
                "must list known PacketOut metadata fields; got: " + ex.getMessage());
    }

    /** Validation: PacketOut metadata wider than the declared bitwidth is rejected. */
    @Test
    void packetOutMetadataTooWideRejected() {
        PacketOut wide = PacketOut.builder()
                .payload(new byte[]{0x71})
                .metadata("egress_port", new byte[]{0x07, (byte) 0xFF})   // 11 significant bits
                .build();
        P4PipelineException ex = assertThrows(P4PipelineException.class, () -> sw.send(wide));
        assertTrue(ex.getMessage().contains("bitWidth 9"),
                "must mention declared bit width; got: " + ex.getMessage());
    }

    /** Validation: send on a switch with no pipeline bound surfaces P4PipelineException. */
    @Test
    void sendWithoutBoundPipelineRejected() throws Exception {
        try (BMv2TestSupport perTest = new BMv2TestSupport("sendNoPipe").start();
             P4Switch noPipe = P4Switch.connectAsPrimary(perTest.grpcAddress())) {
            PacketOut p = PacketOut.builder().payload(new byte[]{(byte) 0x80}).build();
            P4PipelineException ex = assertThrows(P4PipelineException.class, () -> noPipe.send(p));
            assertTrue(ex.getMessage().contains("no pipeline bound"),
                    "got: " + ex.getMessage());
        }
    }

    /** sendAsync returns a CompletableFuture that completes successfully on the
     *  happy path (sync wrapper consistency). */
    @Test
    void sendAsyncReturnsCompletableFuture() throws Exception {
        sw.sendAsync(PacketOut.builder()
                .payload(new byte[]{(byte) 0x90})
                .metadata("egress_port", 1)
                .build()).get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** P4Info exposes the parsed PacketIn / PacketOut metadata declarations. */
    @Test
    void p4InfoSurfacesPacketMetadataDeclarations() {
        P4Info info = P4Info.fromFile(Path.of("src/test/resources/p4/packet_io.p4info.txtpb"));
        List<PacketMetadataInfo> in = info.packetInMetadata();
        List<PacketMetadataInfo> out = info.packetOutMetadata();
        assertEquals(2, in.size(),  "packet_in: ingress_port + _pad");
        assertEquals(2, out.size(), "packet_out: egress_port + _pad");
        assertEquals("ingress_port", in.get(0).name());
        assertEquals(9, in.get(0).bitWidth());
        assertEquals("egress_port", out.get(0).name());
        assertEquals(9, out.get(0).bitWidth());
    }

    // -- helpers --

    private static Flow.Subscriber<PacketIn> subscriberCompleting(CompletableFuture<PacketIn> f) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(PacketIn p) { f.complete(p); }
            @Override public void onError(Throwable t) { f.completeExceptionally(t); }
            @Override public void onComplete() { }
        };
    }
}
