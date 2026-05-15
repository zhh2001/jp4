package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.ActionProfileGroup;
import io.github.zhh2001.jp4.entity.WeightedMember;
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
 * Unit tests for {@code P4Switch.readActionProfileGroup} against an
 * in-process gRPC fake. The happy-path test covers the three
 * {@code watch_kind} oneof shapes: {@code watch_port} set, oneof unset,
 * and the deprecated {@code watch} int32 path — the last two both
 * surface as a null {@code watchPort} per the v1.4 contract.
 */
class P4SwitchReadActionProfileGroupTest {

    private static final int ACTION_PROFILE_ID = 0x11000001;
    private static final String ACTION_PROFILE_NAME = "MyIngress.lb_ap";

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
    void readActionProfileGroupNullArgumentRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.readActionProfileGroup(null));
    }

    @Test
    void readActionProfileGroupRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readActionProfileGroup(ACTION_PROFILE_NAME));
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readActionProfileGroupUnknownNameRejected() {
        sw = connectAndBindPipeline();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readActionProfileGroup("MyIngress.does_not_exist"));
        assertTrue(ex.getMessage().contains("does_not_exist"));
        assertTrue(ex.getMessage().contains("known:"),
                "expected known-list hint; got: " + ex.getMessage());
    }

    @Test
    void readActionProfileGroupAllReturnsBuiltEntries() {
        sw = connectAndBindPipeline();
        // Three slots exercise the three watch_kind shapes:
        // - slot 0: watch_port bytes set      → record has non-null watchPort
        // - slot 1: oneof unset               → record has null watchPort
        // - slot 2: deprecated 'watch' int32  → record has null watchPort
        byte[] watchPortBytes = new byte[]{0x00, 0x07};
        var slot0 = p4.v1.P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                .setMemberId(100)
                .setWeight(3)
                .setWatchPort(ByteString.copyFrom(watchPortBytes))
                .build();
        var slot1 = p4.v1.P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                .setMemberId(101)
                .setWeight(1)
                .build();
        var slot2 = p4.v1.P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                .setMemberId(102)
                .setWeight(2)
                .setWatch(42)   // deprecated int32 path
                .build();

        var wireGroup = p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(ACTION_PROFILE_ID)
                .setGroupId(50)
                .setMaxSize(8)
                .addMembers(slot0)
                .addMembers(slot1)
                .addMembers(slot2)
                .build();
        fake.preloadGroupResponse(List.of(wireGroup));

        List<ActionProfileGroup> entries = sw.readActionProfileGroup(ACTION_PROFILE_NAME).all();
        assertEquals(1, entries.size());

        ActionProfileGroup g = entries.get(0);
        assertEquals(ACTION_PROFILE_NAME, g.actionProfileName());
        assertEquals(50L, g.groupId());
        assertEquals(8, g.maxSize());
        assertEquals(3, g.members().size());

        WeightedMember s0 = g.members().get(0);
        assertEquals(100L, s0.memberId());
        assertEquals(3, s0.weight());
        assertNotNull(s0.watchPort(), "slot 0 should have a non-null watchPort");
        assertArrayEquals(watchPortBytes, s0.watchPort().toByteArray());

        WeightedMember s1 = g.members().get(1);
        assertEquals(101L, s1.memberId());
        assertEquals(1, s1.weight());
        assertNull(s1.watchPort(), "slot 1 (oneof unset) should have null watchPort");

        WeightedMember s2 = g.members().get(2);
        assertEquals(102L, s2.memberId());
        assertEquals(2, s2.weight());
        assertNull(s2.watchPort(),
                "slot 2 (deprecated int32 watch) should also surface null watchPort");
    }

    @Test
    void readActionProfileGroupIdFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadGroupResponse(List.of(
                p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                        .setActionProfileId(ACTION_PROFILE_ID)
                        .setGroupId(11)
                        .build()));

        sw.readActionProfileGroup(ACTION_PROFILE_NAME).groupId(11L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured);
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasActionProfileGroup());
        var g = e.getActionProfileGroup();
        assertEquals(ACTION_PROFILE_ID, g.getActionProfileId());
        assertEquals(11, g.getGroupId());
    }

    @Test
    void readActionProfileGroupWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadGroupResponse(List.of(
                buildEmptyGroup(1),
                buildEmptyGroup(2),
                buildEmptyGroup(3)));

        List<ActionProfileGroup> entries = sw.readActionProfileGroup(ACTION_PROFILE_NAME)
                .where(g -> g.groupId() > 1L)
                .all();
        assertEquals(2, entries.size());
        assertEquals(2L, entries.get(0).groupId());
        assertEquals(3L, entries.get(1).groupId());
    }

    private static p4.v1.P4RuntimeOuterClass.ActionProfileGroup buildEmptyGroup(int groupId) {
        return p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(ACTION_PROFILE_ID)
                .setGroupId(groupId)
                .build();
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(buildP4InfoWithActionProfile(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info buildP4InfoWithActionProfile() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addActionProfiles(p4.config.v1.P4InfoOuterClass.ActionProfile.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(ACTION_PROFILE_ID)
                                .setName(ACTION_PROFILE_NAME)
                                .build())
                        .setSize(64)
                        .build())
                .build();
        return P4Info.fromBytes(proto.toByteArray());
    }

    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final BlockingQueue<ReadRequest> readRequests = new LinkedBlockingQueue<>();
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.ActionProfileGroup>> preloaded =
                new AtomicReference<>(List.of());

        void preloadGroupResponse(List<p4.v1.P4RuntimeOuterClass.ActionProfileGroup> entries) {
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
                rb.addEntities(Entity.newBuilder().setActionProfileGroup(g).build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
