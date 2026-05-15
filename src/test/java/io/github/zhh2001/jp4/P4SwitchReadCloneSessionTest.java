package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.BackupReplica;
import io.github.zhh2001.jp4.entity.CloneSessionEntry;
import io.github.zhh2001.jp4.entity.Replica;
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
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry;
import p4.v1.P4RuntimeOuterClass.ReadRequest;
import p4.v1.P4RuntimeOuterClass.ReadResponse;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code P4Switch.readCloneSession} against an
 * in-process gRPC fake. Mirrors the harness pattern established in
 * {@code P4SwitchReadMulticastGroupTest}; PRE is program-agnostic so
 * the API takes no P4 name and the harness uses an empty P4Info just
 * to satisfy the pipeline-bound gate.
 */
class P4SwitchReadCloneSessionTest {

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
    void readCloneSessionRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readCloneSession());
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readCloneSessionAllReturnsBuiltEntries() {
        sw = connectAndBindPipeline();
        // Two replicas exercise port_kind shapes:
        // - replica 0: port bytes set       → record has non-null port
        // - replica 1: oneof unset          → record has null port
        byte[] portBytes = new byte[]{0x00, 0x09};
        var rep0 = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setPort(ByteString.copyFrom(portBytes))
                .setInstance(1)
                .build();
        var rep1 = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setInstance(2)
                .build();
        var wireSession = p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(100)
                .addReplicas(rep0)
                .addReplicas(rep1)
                .setClassOfService(7)
                .setPacketLengthBytes(128)
                .build();
        fake.preloadSessionResponse(List.of(wireSession));

        List<CloneSessionEntry> entries = sw.readCloneSession().all();
        assertEquals(1, entries.size());

        CloneSessionEntry s = entries.get(0);
        assertEquals(100L, s.sessionId());
        assertEquals(7L, s.classOfService(),
                "classOfService should round-trip through long-widened field");
        assertEquals(128, s.packetLengthBytes());
        assertEquals(2, s.replicas().size());

        Replica r0 = s.replicas().get(0);
        assertNotNull(r0.port(), "replica 0 should have non-null port");
        assertArrayEquals(portBytes, r0.port().toByteArray());
        assertEquals(1, r0.instance());

        Replica r1 = s.replicas().get(1);
        assertNull(r1.port(), "replica 1 (port_kind unset) should have null port");
        assertEquals(2, r1.instance());
    }

    @Test
    void readCloneSessionAllReturnsBackupReplicas() {
        sw = connectAndBindPipeline();
        byte[] primaryBytes = new byte[]{0x00, 0x20};
        byte[] backupBytes = new byte[]{0x00, 0x21};
        var backup = p4.v1.P4RuntimeOuterClass.BackupReplica.newBuilder()
                .setPort(ByteString.copyFrom(backupBytes))
                .setInstance(21)
                .build();
        var rep = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setPort(ByteString.copyFrom(primaryBytes))
                .setInstance(20)
                .addBackupReplicas(backup)
                .build();
        var wireSession = p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(200)
                .addReplicas(rep)
                .build();
        fake.preloadSessionResponse(List.of(wireSession));

        CloneSessionEntry s = sw.readCloneSession().all().get(0);
        Replica r = s.replicas().get(0);
        assertArrayEquals(primaryBytes, r.port().toByteArray());
        assertEquals(1, r.backupReplicas().size());

        BackupReplica b = r.backupReplicas().get(0);
        assertArrayEquals(backupBytes, b.port().toByteArray());
        assertEquals(21, b.instance());
    }

    @Test
    void readCloneSessionTruncationBoundary() {
        // Two sessions exercise the packetLengthBytes semantics defined in
        // the P4Runtime spec comment: 0 means "do not truncate"; a positive
        // value means "truncate cloned payload to N bytes".
        sw = connectAndBindPipeline();
        var noTrunc = p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(300)
                .setPacketLengthBytes(0)
                .build();
        var trunc64 = p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(301)
                .setPacketLengthBytes(64)
                .build();
        fake.preloadSessionResponse(List.of(noTrunc, trunc64));

        List<CloneSessionEntry> entries = sw.readCloneSession().all();
        assertEquals(2, entries.size());

        CloneSessionEntry noTruncEntry = entries.get(0);
        assertEquals(300L, noTruncEntry.sessionId());
        assertEquals(0, noTruncEntry.packetLengthBytes(),
                "packetLengthBytes=0 means do not truncate; the value round-trips as 0");

        CloneSessionEntry truncEntry = entries.get(1);
        assertEquals(301L, truncEntry.sessionId());
        assertEquals(64, truncEntry.packetLengthBytes(),
                "packetLengthBytes=64 means truncate clones to 64 bytes; value round-trips intact");
    }

    @Test
    void readCloneSessionSessionIdFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadSessionResponse(List.of(
                p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                        .setSessionId(99)
                        .build()));

        sw.readCloneSession().sessionId(99L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured);
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasPacketReplicationEngineEntry(),
                "entity should carry packet_replication_engine_entry");
        var pre = e.getPacketReplicationEngineEntry();
        assertTrue(pre.hasCloneSessionEntry(),
                "PRE entry should carry clone_session_entry");
        assertEquals(99, pre.getCloneSessionEntry().getSessionId());
    }

    @Test
    void readCloneSessionWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadSessionResponse(List.of(
                buildEmptySession(1),
                buildEmptySession(2),
                buildEmptySession(3)));

        List<CloneSessionEntry> entries = sw.readCloneSession()
                .where(s -> s.sessionId() > 1L)
                .all();
        assertEquals(2, entries.size());
        assertEquals(2L, entries.get(0).sessionId());
        assertEquals(3L, entries.get(1).sessionId());
    }

    private static p4.v1.P4RuntimeOuterClass.CloneSessionEntry buildEmptySession(int sessionId) {
        return p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(sessionId)
                .build();
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(emptyP4Info(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info emptyP4Info() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder().build();
        return P4Info.fromBytes(proto.toByteArray());
    }

    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final BlockingQueue<ReadRequest> readRequests = new LinkedBlockingQueue<>();
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.CloneSessionEntry>> preloaded =
                new AtomicReference<>(List.of());

        void preloadSessionResponse(List<p4.v1.P4RuntimeOuterClass.CloneSessionEntry> entries) {
            preloaded.set(entries);
        }

        ReadRequest firstReadRequest() {
            return readRequests.peek();
        }

        @Override
        public StreamObserver<StreamMessageRequest> streamChannel(
                StreamObserver<StreamMessageResponse> respObs) {
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
        public void read(ReadRequest req, StreamObserver<ReadResponse> respObs) {
            readRequests.add(req);
            ReadResponse.Builder rb = ReadResponse.newBuilder();
            for (var s : preloaded.get()) {
                rb.addEntities(Entity.newBuilder()
                        .setPacketReplicationEngineEntry(
                                PacketReplicationEngineEntry.newBuilder()
                                        .setCloneSessionEntry(s)
                                        .build())
                        .build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
