package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.ActionProfileMember;
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
import p4.v1.P4RuntimeOuterClass.Action;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code P4Switch.readActionProfileMember} against an
 * in-process gRPC fake.
 */
class P4SwitchReadActionProfileMemberTest {

    private static final int ACTION_PROFILE_ID = 0x11000001;
    private static final String ACTION_PROFILE_NAME = "MyIngress.lb_ap";
    private static final int ACTION_ID = 0x01000010;
    private static final String ACTION_NAME = "MyIngress.set_egress";
    private static final int PARAM_ID = 1;
    private static final String PARAM_NAME = "port";

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
    void readActionProfileMemberNullArgumentRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.readActionProfileMember(null));
    }

    @Test
    void readActionProfileMemberRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readActionProfileMember(ACTION_PROFILE_NAME));
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readActionProfileMemberUnknownNameRejected() {
        sw = connectAndBindPipeline();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readActionProfileMember("MyIngress.does_not_exist"));
        assertTrue(ex.getMessage().contains("does_not_exist"));
        assertTrue(ex.getMessage().contains("known:"),
                "expected known-list hint; got: " + ex.getMessage());
    }

    @Test
    void readActionProfileMemberAllReturnsBuiltEntries() {
        sw = connectAndBindPipeline();
        byte[] port1Bytes = new byte[]{0x00, 0x05};
        byte[] port2Bytes = new byte[]{0x00, 0x0A};
        fake.preloadMemberResponse(List.of(
                buildWireMember(100, port1Bytes),
                buildWireMember(200, port2Bytes)));

        List<ActionProfileMember> entries = sw.readActionProfileMember(ACTION_PROFILE_NAME).all();
        assertEquals(2, entries.size());

        ActionProfileMember m0 = entries.get(0);
        assertEquals(ACTION_PROFILE_NAME, m0.actionProfileName());
        assertEquals(100L, m0.memberId());
        assertEquals(ACTION_NAME, m0.action().name());
        assertArrayEquals(port1Bytes, m0.action().param(PARAM_NAME).toByteArray());

        ActionProfileMember m1 = entries.get(1);
        assertEquals(200L, m1.memberId());
        assertArrayEquals(port2Bytes, m1.action().param(PARAM_NAME).toByteArray());
    }

    @Test
    void readActionProfileMemberIdFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadMemberResponse(List.of(
                buildWireMember(7, new byte[]{0x01})));

        sw.readActionProfileMember(ACTION_PROFILE_NAME).memberId(7L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured);
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasActionProfileMember());
        var m = e.getActionProfileMember();
        assertEquals(ACTION_PROFILE_ID, m.getActionProfileId());
        assertEquals(7, m.getMemberId());
    }

    @Test
    void readActionProfileMemberWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadMemberResponse(List.of(
                buildWireMember(1, new byte[]{0x01}),
                buildWireMember(2, new byte[]{0x02}),
                buildWireMember(3, new byte[]{0x03})));

        List<ActionProfileMember> entries = sw.readActionProfileMember(ACTION_PROFILE_NAME)
                .where(m -> m.memberId() > 1L)
                .all();
        assertEquals(2, entries.size());
        assertEquals(2L, entries.get(0).memberId());
        assertEquals(3L, entries.get(1).memberId());
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(buildP4InfoWithActionProfile(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info buildP4InfoWithActionProfile() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addActions(p4.config.v1.P4InfoOuterClass.Action.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(ACTION_ID)
                                .setName(ACTION_NAME)
                                .build())
                        .addParams(p4.config.v1.P4InfoOuterClass.Action.Param.newBuilder()
                                .setId(PARAM_ID)
                                .setName(PARAM_NAME)
                                .setBitwidth(9)
                                .build())
                        .build())
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

    private static p4.v1.P4RuntimeOuterClass.ActionProfileMember buildWireMember(
            int memberId, byte[] portBytes) {
        Action action = Action.newBuilder()
                .setActionId(ACTION_ID)
                .addParams(Action.Param.newBuilder()
                        .setParamId(PARAM_ID)
                        .setValue(ByteString.copyFrom(portBytes))
                        .build())
                .build();
        return p4.v1.P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(ACTION_PROFILE_ID)
                .setMemberId(memberId)
                .setAction(action)
                .build();
    }

    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final BlockingQueue<ReadRequest> readRequests = new LinkedBlockingQueue<>();
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.ActionProfileMember>> preloaded =
                new AtomicReference<>(List.of());

        void preloadMemberResponse(List<p4.v1.P4RuntimeOuterClass.ActionProfileMember> entries) {
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
            for (var m : preloaded.get()) {
                rb.addEntities(Entity.newBuilder().setActionProfileMember(m).build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
