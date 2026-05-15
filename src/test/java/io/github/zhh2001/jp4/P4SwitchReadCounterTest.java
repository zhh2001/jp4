package io.github.zhh2001.jp4;

import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.CounterEntry;
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
import p4.v1.P4RuntimeOuterClass.CounterData;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.Index;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code P4Switch.readCounter} against an in-process gRPC
 * fake. The fake captures inbound {@code ReadRequest}s for assertion and
 * pre-canned response cells the caller configures per test. Mirrors the
 * harness pattern established in {@code P4SwitchEnableDigestTest}.
 */
class P4SwitchReadCounterTest {

    private static final int COUNTER_ID = 0x0c000001;
    private static final String COUNTER_NAME = "MyIngress.pkt_counter";

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
    void readCounterNullArgumentRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.readCounter(null));
    }

    @Test
    void readCounterRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readCounter(COUNTER_NAME));
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readCounterUnknownNameRejected() {
        sw = connectAndBindPipeline();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readCounter("MyIngress.does_not_exist"));
        assertTrue(ex.getMessage().contains("does_not_exist"));
        assertTrue(ex.getMessage().contains("known:"),
                "expected known-list hint; got: " + ex.getMessage());
    }

    @Test
    void readCounterAllReturnsBuiltEntries() {
        sw = connectAndBindPipeline();
        fake.preloadCounterResponse(List.of(
                buildWireCounterEntry(0, 100, 1000),
                buildWireCounterEntry(1, 200, 2000)));

        List<CounterEntry> entries = sw.readCounter(COUNTER_NAME).all();
        assertEquals(2, entries.size());
        assertEquals(COUNTER_NAME, entries.get(0).counterName());
        assertEquals(0L, entries.get(0).index());
        assertEquals(100L, entries.get(0).packetCount());
        assertEquals(1000L, entries.get(0).byteCount());
        assertEquals(COUNTER_NAME, entries.get(1).counterName());
        assertEquals(1L, entries.get(1).index());
        assertEquals(200L, entries.get(1).packetCount());
        assertEquals(2000L, entries.get(1).byteCount());
    }

    @Test
    void readCounterIndexFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadCounterResponse(List.of(
                buildWireCounterEntry(7, 50, 500)));

        sw.readCounter(COUNTER_NAME).index(7L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured, "fake server should have captured a ReadRequest");
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasCounterEntry(), "entity should carry counter_entry");
        var ce = e.getCounterEntry();
        assertEquals(COUNTER_ID, ce.getCounterId());
        assertTrue(ce.hasIndex(), "index filter should be present on the wire");
        assertEquals(7L, ce.getIndex().getIndex());
    }

    @Test
    void readCounterWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadCounterResponse(List.of(
                buildWireCounterEntry(0, 0, 0),
                buildWireCounterEntry(1, 5, 50),
                buildWireCounterEntry(2, 0, 0)));

        List<CounterEntry> entries = sw.readCounter(COUNTER_NAME)
                .where(c -> c.packetCount() > 0)
                .all();
        assertEquals(1, entries.size());
        assertEquals(1L, entries.get(0).index());
        assertEquals(5L, entries.get(0).packetCount());
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(buildP4InfoWithCounter(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info buildP4InfoWithCounter() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addCounters(p4.config.v1.P4InfoOuterClass.Counter.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(COUNTER_ID)
                                .setName(COUNTER_NAME)
                                .build())
                        .setSpec(p4.config.v1.P4InfoOuterClass.CounterSpec.newBuilder()
                                .setUnit(p4.config.v1.P4InfoOuterClass.CounterSpec.Unit.BOTH)
                                .build())
                        .setSize(1024)
                        .build())
                .build();
        return P4Info.fromBytes(proto.toByteArray());
    }

    /** Build a wire CounterEntry for the fake server to return. */
    private static p4.v1.P4RuntimeOuterClass.CounterEntry buildWireCounterEntry(
            long index, long packets, long bytes) {
        return p4.v1.P4RuntimeOuterClass.CounterEntry.newBuilder()
                .setCounterId(COUNTER_ID)
                .setIndex(Index.newBuilder().setIndex(index).build())
                .setData(CounterData.newBuilder()
                        .setPacketCount(packets)
                        .setByteCount(bytes)
                        .build())
                .build();
    }

    /**
     * In-process P4Runtime fake: auto-arbitrates as primary, accepts pipeline
     * config, records inbound {@code ReadRequest}s, and replies with
     * pre-canned counter cells configured via {@link #preloadCounterResponse}.
     */
    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final BlockingQueue<ReadRequest> readRequests = new LinkedBlockingQueue<>();
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.CounterEntry>> preloaded =
                new AtomicReference<>(List.of());

        void preloadCounterResponse(List<p4.v1.P4RuntimeOuterClass.CounterEntry> entries) {
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
            for (var ce : preloaded.get()) {
                rb.addEntities(Entity.newBuilder().setCounterEntry(ce).build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
