package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.IdleTimeoutEvent;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.TableInfo;
import io.github.zhh2001.jp4.pipeline.ActionInfo;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.Action;
import p4.v1.P4RuntimeOuterClass.FieldMatch;
import p4.v1.P4RuntimeOuterClass.IdleTimeoutNotification;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest;
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse;
import p4.v1.P4RuntimeOuterClass.TableAction;
import p4.v1.P4RuntimeOuterClass.TableEntry;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code P4Switch.onIdleTimeout} registration and
 * {@code dispatchIdleTimeout} behaviour, including the fail-open drop
 * path on an action-profile entry that the v1.3 reverse-parser rejects.
 * Uses an in-process Netty server on a free localhost port with a
 * {@code FakeP4RuntimeService} that auto-arbitrates, accepts
 * {@code SetForwardingPipelineConfig}, and emits
 * {@code IdleTimeoutNotification}s on demand. The bound P4Info reuses
 * {@code basic.p4info.txtpb} to keep table / action / match-field ids
 * stable across runs.
 */
class P4SwitchIdleTimeoutTest {

    private static final Path BASIC_P4INFO = Path.of("src/test/resources/p4/basic.p4info.txtpb");

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
    void onIdleTimeoutNullHandlerRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.onIdleTimeout(null));
    }

    @Test
    void onIdleTimeoutRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        assertThrows(P4PipelineException.class, () -> sw.onIdleTimeout(event -> { }));
    }

    @Test
    void onIdleTimeoutLastWriteWinsReplacesListener() {
        sw = connectAndBindPipeline();
        List<IdleTimeoutEvent> first = new CopyOnWriteArrayList<>();
        List<IdleTimeoutEvent> second = new CopyOnWriteArrayList<>();
        sw.onIdleTimeout(first::add);
        sw.onIdleTimeout(second::add);

        fake.emitIdleTimeoutNotification(List.of(buildLpmDirectActionEntry(sw.pipeline().p4info())));
        awaitTrue(() -> !second.isEmpty(), 2_000);

        assertTrue(first.isEmpty(), "first listener was replaced, should not receive");
        assertEquals(1, second.size());
        assertEquals(1, second.get(0).entries().size());
        assertEquals("MyIngress.ipv4_lpm", second.get(0).entries().get(0).tableName());
    }

    @Test
    void dispatchIdleTimeoutResolvesEntriesAndCallsListener() {
        sw = connectAndBindPipeline();
        BlockingQueue<IdleTimeoutEvent> received = new LinkedBlockingQueue<>();
        sw.onIdleTimeout(received::add);

        TableEntry wire = buildLpmDirectActionEntry(sw.pipeline().p4info());
        fake.emitIdleTimeoutNotification(List.of(wire));

        IdleTimeoutEvent event;
        try {
            event = received.poll(2_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for IdleTimeoutEvent", ie);
        }

        assertNotNull(event, "listener should have received an IdleTimeoutEvent");
        assertEquals(1, event.entries().size());
        assertEquals("MyIngress.ipv4_lpm", event.entries().get(0).tableName());
    }

    @Test
    void dispatchIdleTimeoutWarnDropsOnActionProfileEntry() {
        sw = connectAndBindPipeline();
        List<IdleTimeoutEvent> received = new CopyOnWriteArrayList<>();
        sw.onIdleTimeout(received::add);

        TableEntry actionProfile = buildLpmActionProfileEntry(sw.pipeline().p4info());
        fake.emitIdleTimeoutNotification(List.of(actionProfile));

        // Give the dispatch path a chance to run; the WARN-drop path should
        // produce zero IdleTimeoutEvents because the whole notification is
        // dropped on the first action-profile entry.
        sleep(500);
        assertTrue(received.isEmpty(),
                "listener must not receive a partial event; got " + received);
    }

    @Test
    void dispatchIdleTimeoutNoListenerNoOp() {
        sw = connectAndBindPipeline();
        // No onIdleTimeout registration — listener stays null.

        TableEntry wire = buildLpmDirectActionEntry(sw.pipeline().p4info());
        fake.emitIdleTimeoutNotification(List.of(wire));

        // No outbound ack is expected for IdleTimeoutNotification, and
        // no listener is registered, so the dispatch path should silently
        // no-op. Wait a small grace window and confirm nothing crashed.
        sleep(300);
        assertFalse(server.isShutdown(), "server must still be running");
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(
                P4Info.fromFile(BASIC_P4INFO),
                DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static TableEntry buildLpmDirectActionEntry(P4Info p4info) {
        TableInfo lpm = p4info.table("MyIngress.ipv4_lpm");
        ActionInfo fwd = p4info.action("MyIngress.forward");
        int matchFieldId = lpm.matchField("hdr.ipv4.dstAddr").id();
        int portParamId = fwd.param("port").id();
        return TableEntry.newBuilder()
                .setTableId(lpm.id())
                .addMatch(FieldMatch.newBuilder()
                        .setFieldId(matchFieldId)
                        .setLpm(FieldMatch.LPM.newBuilder()
                                .setValue(ByteString.copyFrom(new byte[]{10, 0, 0, 0}))
                                .setPrefixLen(8)
                                .build())
                        .build())
                .setAction(TableAction.newBuilder()
                        .setAction(Action.newBuilder()
                                .setActionId(fwd.id())
                                .addParams(Action.Param.newBuilder()
                                        .setParamId(portParamId)
                                        .setValue(ByteString.copyFrom(new byte[]{0, 1}))
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static TableEntry buildLpmActionProfileEntry(P4Info p4info) {
        TableInfo lpm = p4info.table("MyIngress.ipv4_lpm");
        int matchFieldId = lpm.matchField("hdr.ipv4.dstAddr").id();
        // Use the same match shape as the happy path so the only thing that
        // makes the reverse-parser reject this entry is the action-profile
        // member reference in TableAction.
        return TableEntry.newBuilder()
                .setTableId(lpm.id())
                .addMatch(FieldMatch.newBuilder()
                        .setFieldId(matchFieldId)
                        .setLpm(FieldMatch.LPM.newBuilder()
                                .setValue(ByteString.copyFrom(new byte[]{10, 0, 0, 0}))
                                .setPrefixLen(8)
                                .build())
                        .build())
                .setAction(TableAction.newBuilder()
                        .setActionProfileMemberId(1)
                        .build())
                .build();
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep(10);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * In-process P4Runtime fake: auto-arbitrates the controller as primary on
     * the first {@code MasterArbitrationUpdate}, accepts every
     * {@code SetForwardingPipelineConfig}, and emits
     * {@code IdleTimeoutNotification}s on test command via
     * {@link #emitIdleTimeoutNotification(List)}.
     */
    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final AtomicReference<StreamObserver<StreamMessageResponse>> downstream =
                new AtomicReference<>();

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

        void emitIdleTimeoutNotification(List<TableEntry> entries) {
            IdleTimeoutNotification.Builder b = IdleTimeoutNotification.newBuilder()
                    .setTimestamp(System.nanoTime());
            for (TableEntry e : entries) b.addTableEntry(e);
            downstream.get().onNext(StreamMessageResponse.newBuilder()
                    .setIdleTimeoutNotification(b.build())
                    .build());
        }
    }
}
