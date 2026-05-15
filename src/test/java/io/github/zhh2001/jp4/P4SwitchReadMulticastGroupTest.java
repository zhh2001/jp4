package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.BackupReplica;
import io.github.zhh2001.jp4.entity.MulticastGroupEntry;
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
 * Unit tests for {@code P4Switch.readMulticastGroup} against an
 * in-process gRPC fake. The packet replication engine is
 * program-agnostic, so the API takes no P4 name and the harness can
 * use any valid P4Info; this class binds a minimal empty pipeline
 * just to satisfy the pipeline-bound gate.
 */
class P4SwitchReadMulticastGroupTest {

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
    void readMulticastGroupRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readMulticastGroup());
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readMulticastGroupAllReturnsBuiltEntries() {
        sw = connectAndBindPipeline();
        // Three replicas exercise the three port_kind shapes:
        // - replica 0: port bytes set       → record has non-null port
        // - replica 1: oneof unset          → record has null port
        // - replica 2: deprecated egress_port int32 → record has null port
        byte[] portBytes = new byte[]{0x00, 0x05};
        var rep0 = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setPort(ByteString.copyFrom(portBytes))
                .setInstance(0)
                .build();
        var rep1 = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setInstance(1)
                .build();
        var rep2 = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setEgressPort(42)   // deprecated int32 path
                .setInstance(2)
                .build();
        var wireGroup = p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(100)
                .addReplicas(rep0)
                .addReplicas(rep1)
                .addReplicas(rep2)
                .build();
        fake.preloadGroupResponse(List.of(wireGroup));

        List<MulticastGroupEntry> entries = sw.readMulticastGroup().all();
        assertEquals(1, entries.size());

        MulticastGroupEntry g = entries.get(0);
        assertEquals(100L, g.multicastGroupId());
        assertEquals(3, g.replicas().size());

        Replica r0 = g.replicas().get(0);
        assertNotNull(r0.port(), "replica 0 should have non-null port");
        assertArrayEquals(portBytes, r0.port().toByteArray());
        assertEquals(0, r0.instance());
        assertEquals(0, r0.backupReplicas().size());

        Replica r1 = g.replicas().get(1);
        assertNull(r1.port(), "replica 1 (port_kind unset) should have null port");
        assertEquals(1, r1.instance());

        Replica r2 = g.replicas().get(2);
        assertNull(r2.port(),
                "replica 2 (deprecated egress_port int32) should also surface null port");
        assertEquals(2, r2.instance());
    }

    @Test
    void readMulticastGroupAllReturnsBackupReplicas() {
        sw = connectAndBindPipeline();
        byte[] primaryBytes = new byte[]{0x00, 0x10};
        byte[] backup1Bytes = new byte[]{0x00, 0x11};
        byte[] backup2Bytes = new byte[]{0x00, 0x12};
        var backup1 = p4.v1.P4RuntimeOuterClass.BackupReplica.newBuilder()
                .setPort(ByteString.copyFrom(backup1Bytes))
                .setInstance(11)
                .build();
        var backup2 = p4.v1.P4RuntimeOuterClass.BackupReplica.newBuilder()
                .setPort(ByteString.copyFrom(backup2Bytes))
                .setInstance(12)
                .build();
        var rep = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setPort(ByteString.copyFrom(primaryBytes))
                .setInstance(10)
                .addBackupReplicas(backup1)
                .addBackupReplicas(backup2)
                .build();
        var wireGroup = p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(200)
                .addReplicas(rep)
                .build();
        fake.preloadGroupResponse(List.of(wireGroup));

        MulticastGroupEntry g = sw.readMulticastGroup().all().get(0);
        Replica r = g.replicas().get(0);
        assertArrayEquals(primaryBytes, r.port().toByteArray());
        assertEquals(2, r.backupReplicas().size());

        BackupReplica b1 = r.backupReplicas().get(0);
        assertArrayEquals(backup1Bytes, b1.port().toByteArray());
        assertEquals(11, b1.instance());

        BackupReplica b2 = r.backupReplicas().get(1);
        assertArrayEquals(backup2Bytes, b2.port().toByteArray());
        assertEquals(12, b2.instance());
    }

    @Test
    void readMulticastGroupAllReturnsMetadata() {
        sw = connectAndBindPipeline();
        byte[] metaBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        var wireGroup = p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(300)
                .setMetadata(ByteString.copyFrom(metaBytes))
                .build();
        fake.preloadGroupResponse(List.of(wireGroup));

        MulticastGroupEntry g = sw.readMulticastGroup().all().get(0);
        assertEquals(300L, g.multicastGroupId());
        assertArrayEquals(metaBytes, g.metadata().toByteArray());
    }

    @Test
    void readMulticastGroupGroupIdFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadGroupResponse(List.of(
                p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                        .setMulticastGroupId(77)
                        .build()));

        sw.readMulticastGroup().groupId(77L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured);
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasPacketReplicationEngineEntry(),
                "entity should carry packet_replication_engine_entry");
        var pre = e.getPacketReplicationEngineEntry();
        assertTrue(pre.hasMulticastGroupEntry(),
                "PRE entry should carry multicast_group_entry");
        assertEquals(77, pre.getMulticastGroupEntry().getMulticastGroupId());
    }

    @Test
    void readMulticastGroupWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadGroupResponse(List.of(
                buildEmptyGroup(1),
                buildEmptyGroup(2),
                buildEmptyGroup(3)));

        List<MulticastGroupEntry> entries = sw.readMulticastGroup()
                .where(g -> g.multicastGroupId() > 1L)
                .all();
        assertEquals(2, entries.size());
        assertEquals(2L, entries.get(0).multicastGroupId());
        assertEquals(3L, entries.get(1).multicastGroupId());
    }

    private static p4.v1.P4RuntimeOuterClass.MulticastGroupEntry buildEmptyGroup(int groupId) {
        return p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(groupId)
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
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.MulticastGroupEntry>> preloaded =
                new AtomicReference<>(List.of());

        void preloadGroupResponse(List<p4.v1.P4RuntimeOuterClass.MulticastGroupEntry> entries) {
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
            for (var g : preloaded.get()) {
                rb.addEntities(Entity.newBuilder()
                        .setPacketReplicationEngineEntry(
                                PacketReplicationEngineEntry.newBuilder()
                                        .setMulticastGroupEntry(g)
                                        .build())
                        .build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
