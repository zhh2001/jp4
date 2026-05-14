package io.github.zhh2001.jp4;

import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.DigestConfig;
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
import p4.v1.P4RuntimeOuterClass.DigestEntry;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.WriteRequest;
import p4.v1.P4RuntimeOuterClass.WriteResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code P4Switch.enableDigest}. Uses an in-process Netty
 * server on a free localhost port with a {@code FakeP4RuntimeService}
 * that auto-arbitrates, accepts {@code SetForwardingPipelineConfig}, and
 * captures inbound {@code WriteRequest}s for assertion.
 */
class P4SwitchEnableDigestTest {

    private static final int DIGEST_ID = 0x01000001;
    private static final String DIGEST_NAME = "MyIngress.learn_digest";
    private static final DigestConfig SAMPLE_CFG = new DigestConfig(
            Duration.ofMillis(500), 16, Duration.ofSeconds(2));

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
    void enableDigestNullArgumentsRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.enableDigest(null, SAMPLE_CFG));
        assertThrows(NullPointerException.class, () -> sw.enableDigest(DIGEST_NAME, null));
    }

    @Test
    void enableDigestRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.enableDigest(DIGEST_NAME, SAMPLE_CFG));
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void enableDigestWritesDigestEntryUpdate() {
        sw = connectAndBindPipeline();
        sw.enableDigest(DIGEST_NAME, SAMPLE_CFG);

        WriteRequest captured = fake.firstWriteRequest();
        assertNotNull(captured, "fake server should have captured a WriteRequest");
        assertEquals(1, captured.getUpdatesCount());
        Update u = captured.getUpdates(0);
        assertEquals(Update.Type.INSERT, u.getType());
        assertTrue(u.getEntity().hasDigestEntry(),
                "Entity should carry digest_entry; got " + u.getEntity().getEntityCase());

        DigestEntry de = u.getEntity().getDigestEntry();
        assertEquals(DIGEST_ID, de.getDigestId());
        assertEquals(500_000_000L, de.getConfig().getMaxTimeoutNs(),
                "maxTimeoutNs should match the Duration.toNanos()");
        assertEquals(16, de.getConfig().getMaxListSize());
        assertEquals(2_000_000_000L, de.getConfig().getAckTimeoutNs());
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

    /**
     * In-process P4Runtime fake: auto-arbitrates the controller as primary,
     * accepts every {@code SetForwardingPipelineConfig}, replies to every
     * {@code Write} with a successful response, and records the inbound
     * {@code WriteRequest}s for assertion.
     */
    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final AtomicReference<StreamObserver<StreamMessageResponse>> downstream =
                new AtomicReference<>();
        private final BlockingQueue<WriteRequest> writeRequests = new LinkedBlockingQueue<>();

        @Override
        public StreamObserver<StreamMessageRequest> streamChannel(
                StreamObserver<StreamMessageResponse> respObs) {
            downstream.set(respObs);
            return new StreamObserver<>() {
                @Override
                public void onNext(StreamMessageRequest req) {
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

        @Override
        public void write(WriteRequest req, StreamObserver<WriteResponse> respObs) {
            writeRequests.add(req);
            respObs.onNext(WriteResponse.getDefaultInstance());
            respObs.onCompleted();
        }

        WriteRequest firstWriteRequest() {
            return writeRequests.peek();
        }

        List<WriteRequest> allWriteRequests() {
            return List.copyOf(writeRequests);
        }
    }
}
