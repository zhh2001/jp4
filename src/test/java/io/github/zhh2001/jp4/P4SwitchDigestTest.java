package io.github.zhh2001.jp4;

import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.DigestEvent;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.DigestList;
import p4.v1.P4RuntimeOuterClass.DigestListAck;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code P4Switch.onDigest} registration and
 * {@code dispatchDigest} ack-first dispatch behaviour. Uses an in-process
 * Netty server on a free localhost port with a {@code FakeP4RuntimeService}
 * that auto-arbitrates, accepts {@code SetForwardingPipelineConfig}, captures
 * inbound {@code DigestListAck}s, and emits {@code DigestList}s on demand.
 *
 * <p>The BMv2-backed end-to-end exercise lands in a later sub-phase once a
 * P4 program with a digest extern is available.
 */
class P4SwitchDigestTest {

    private static final int DIGEST_ID = 0x01000001;
    private static final String DIGEST_NAME = "MyIngress.learn_digest";

    private Server server;
    private FakeP4RuntimeService fake;
    private int port;
    private P4Switch sw;

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        fake = new FakeP4RuntimeService();
        server = NettyServerBuilder.forPort(port)
                .addService(fake)
                .build()
                .start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (sw != null) {
            try { sw.close(); } catch (RuntimeException ignored) { }
        }
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void onDigestNullHandlerRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.onDigest(null));
    }

    @Test
    void onDigestRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        assertThrows(P4PipelineException.class, () -> sw.onDigest(event -> { }));
    }

    @Test
    void onDigestLastWriteWinsReplacesListener() {
        sw = connectAndBindPipeline();
        List<DigestEvent> first = new CopyOnWriteArrayList<>();
        List<DigestEvent> second = new CopyOnWriteArrayList<>();
        sw.onDigest(first::add);
        sw.onDigest(second::add);

        fake.emitDigestList(DIGEST_ID, 42L);
        awaitTrue(() -> !second.isEmpty(), 2_000);

        assertTrue(first.isEmpty(), "first listener was replaced, should not receive");
        assertEquals(1, second.size());
        assertEquals(DIGEST_NAME, second.get(0).digestName());
        assertEquals(42L, second.get(0).listId());
    }

    @Test
    void dispatchDigestResolvesNameAndDispatchesEvent() {
        sw = connectAndBindPipeline();
        BlockingQueue<DigestEvent> received = new LinkedBlockingQueue<>();
        sw.onDigest(received::add);

        fake.emitDigestList(DIGEST_ID, 99L);
        DigestEvent event;
        try {
            event = received.poll(2_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for DigestEvent", ie);
        }

        assertNotNull(event, "listener should have received a DigestEvent");
        assertEquals(DIGEST_NAME, event.digestName());
        assertEquals(99L, event.listId());
        assertEquals(DIGEST_ID, event.rawDigestId());
    }

    @Test
    void dispatchDigestAutoAcksOutbound() {
        sw = connectAndBindPipeline();
        sw.onDigest(event -> { });

        fake.emitDigestList(DIGEST_ID, 77L);
        awaitTrue(() -> fake.lastDigestAck() != null, 2_000);

        DigestListAck ack = fake.lastDigestAck();
        assertNotNull(ack, "device should have received a DigestListAck");
        assertEquals(DIGEST_ID, ack.getDigestId());
        assertEquals(77L, ack.getListId());
    }

    @Test
    void dispatchDigestAcksEvenWithoutListener() {
        sw = connectAndBindPipeline();
        // No onDigest registration — listener stays null.
        assertNull(fake.lastDigestAck(), "no ack yet before any DigestList");

        fake.emitDigestList(DIGEST_ID, 33L);
        awaitTrue(() -> fake.lastDigestAck() != null, 2_000);

        DigestListAck ack = fake.lastDigestAck();
        assertNotNull(ack, "ack must still fire even without a registered listener");
        assertEquals(DIGEST_ID, ack.getDigestId());
        assertEquals(33L, ack.getListId());
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(buildP4InfoWithDigest(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info buildP4InfoWithDigest() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addDigests(p4.config.v1.P4InfoOuterClass.Digest.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(DIGEST_ID)
                                .setName(DIGEST_NAME)
                                .build())
                        .build())
                .build();
        return P4Info.fromBytes(proto.toByteArray());
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * In-process P4Runtime fake: auto-arbitrates the controller as primary on
     * the first {@code MasterArbitrationUpdate}, accepts every
     * {@code SetForwardingPipelineConfig}, records inbound
     * {@code DigestListAck}s for assertion, and emits {@code DigestList}s on
     * test command via {@link #emitDigestList(int, long)}.
     */
    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final AtomicReference<StreamObserver<StreamMessageResponse>> downstream =
                new AtomicReference<>();
        private final BlockingQueue<StreamMessageRequest> received = new LinkedBlockingQueue<>();

        @Override
        public StreamObserver<StreamMessageRequest> streamChannel(
                StreamObserver<StreamMessageResponse> respObs) {
            downstream.set(respObs);
            return new StreamObserver<>() {
                @Override
                public void onNext(StreamMessageRequest req) {
                    received.add(req);
                    if (req.hasArbitration()) {
                        var arb = req.getArbitration();
                        respObs.onNext(StreamMessageResponse.newBuilder()
                                .setArbitration(MasterArbitrationUpdate.newBuilder()
                                        .setDeviceId(arb.getDeviceId())
                                        .setElectionId(arb.getElectionId())
                                        .setStatus(Status.newBuilder().setCode(0).build())
                                        .build())
                                .build());
                    }
                }

                @Override public void onError(Throwable t) { }

                @Override
                public void onCompleted() {
                    try { respObs.onCompleted(); } catch (RuntimeException ignored) { }
                }
            };
        }

        @Override
        public void setForwardingPipelineConfig(
                SetForwardingPipelineConfigRequest req,
                StreamObserver<SetForwardingPipelineConfigResponse> respObs) {
            respObs.onNext(SetForwardingPipelineConfigResponse.getDefaultInstance());
            respObs.onCompleted();
        }

        void emitDigestList(int digestId, long listId) {
            StreamMessageResponse resp = StreamMessageResponse.newBuilder()
                    .setDigest(DigestList.newBuilder()
                            .setDigestId(digestId)
                            .setListId(listId)
                            .setTimestamp(System.nanoTime())
                            .build())
                    .build();
            downstream.get().onNext(resp);
        }

        DigestListAck lastDigestAck() {
            DigestListAck latest = null;
            for (StreamMessageRequest r : received) {
                if (r.hasDigestAck()) latest = r.getDigestAck();
            }
            return latest;
        }
    }
}
