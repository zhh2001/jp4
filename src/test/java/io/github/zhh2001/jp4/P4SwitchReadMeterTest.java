package io.github.zhh2001.jp4;

import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.MeterEntry;
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
import p4.v1.P4RuntimeOuterClass.MeterConfig;
import p4.v1.P4RuntimeOuterClass.MeterCounterData;
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
 * Unit tests for {@code P4Switch.readMeter} against an in-process gRPC
 * fake. Mirrors the harness pattern established in
 * {@code P4SwitchReadCounterTest}; the only differences are the entity
 * type and the richer (config + counter_data) wire payload.
 */
class P4SwitchReadMeterTest {

    private static final int METER_ID = 0x12000001;
    private static final String METER_NAME = "MyIngress.rate_meter";

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
    void readMeterNullArgumentRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.readMeter(null));
    }

    @Test
    void readMeterRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readMeter(METER_NAME));
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readMeterUnknownNameRejected() {
        sw = connectAndBindPipeline();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readMeter("MyIngress.does_not_exist"));
        assertTrue(ex.getMessage().contains("does_not_exist"));
        assertTrue(ex.getMessage().contains("known:"),
                "expected known-list hint; got: " + ex.getMessage());
    }

    @Test
    void readMeterAllReturnsBuiltEntries() {
        sw = connectAndBindPipeline();
        fake.preloadMeterResponse(List.of(
                buildWireMeterEntry(0,
                        1_000_000L, 8_192L, 2_000_000L, 16_384L, 4_096L,
                        100L, 1000L,
                        20L,  200L,
                        5L,   50L),
                buildWireMeterEntry(1,
                        1_500_000L, 12_288L, 3_000_000L, 24_576L, 6_144L,
                        50L,  500L,
                        10L,  100L,
                        1L,   10L)));

        List<MeterEntry> entries = sw.readMeter(METER_NAME).all();
        assertEquals(2, entries.size());

        MeterEntry e0 = entries.get(0);
        assertEquals(METER_NAME, e0.meterName());
        assertEquals(0L, e0.index());
        assertEquals(1_000_000L, e0.config().cir());
        assertEquals(8_192L,     e0.config().cburst());
        assertEquals(2_000_000L, e0.config().pir());
        assertEquals(16_384L,    e0.config().pburst());
        assertEquals(4_096L,     e0.config().eburst());
        assertEquals(100L,  e0.counterData().green().packetCount());
        assertEquals(1000L, e0.counterData().green().byteCount());
        assertEquals(20L,   e0.counterData().yellow().packetCount());
        assertEquals(200L,  e0.counterData().yellow().byteCount());
        assertEquals(5L,    e0.counterData().red().packetCount());
        assertEquals(50L,   e0.counterData().red().byteCount());

        MeterEntry e1 = entries.get(1);
        assertEquals(METER_NAME, e1.meterName());
        assertEquals(1L,         e1.index());
        assertEquals(1_500_000L, e1.config().cir());
        assertEquals(6_144L,     e1.config().eburst());
        assertEquals(50L,        e1.counterData().green().packetCount());
        assertEquals(1L,         e1.counterData().red().packetCount());
    }

    @Test
    void readMeterIndexFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadMeterResponse(List.of(
                buildWireMeterEntry(7,
                        1L, 1L, 1L, 1L, 1L,
                        0L, 0L,  0L, 0L,  0L, 0L)));

        sw.readMeter(METER_NAME).index(7L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured, "fake server should have captured a ReadRequest");
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasMeterEntry(), "entity should carry meter_entry");
        var me = e.getMeterEntry();
        assertEquals(METER_ID, me.getMeterId());
        assertTrue(me.hasIndex(), "index filter should be present on the wire");
        assertEquals(7L, me.getIndex().getIndex());
    }

    @Test
    void readMeterWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadMeterResponse(List.of(
                buildWireMeterEntry(0, 0,0,0,0,0,  0,0,  0,0,  0,0),
                buildWireMeterEntry(1, 0,0,0,0,0,  0,0,  0,0,  7L,77L),
                buildWireMeterEntry(2, 0,0,0,0,0,  0,0,  0,0,  0,0)));

        List<MeterEntry> entries = sw.readMeter(METER_NAME)
                .where(m -> m.counterData().red().packetCount() > 0)
                .all();
        assertEquals(1, entries.size());
        assertEquals(1L, entries.get(0).index());
        assertEquals(7L, entries.get(0).counterData().red().packetCount());
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(buildP4InfoWithMeter(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info buildP4InfoWithMeter() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addMeters(p4.config.v1.P4InfoOuterClass.Meter.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(METER_ID)
                                .setName(METER_NAME)
                                .build())
                        .setSpec(p4.config.v1.P4InfoOuterClass.MeterSpec.newBuilder()
                                .setUnit(p4.config.v1.P4InfoOuterClass.MeterSpec.Unit.BYTES)
                                .build())
                        .setSize(256)
                        .build())
                .build();
        return P4Info.fromBytes(proto.toByteArray());
    }

    /** Build a wire MeterEntry for the fake server to return. */
    private static p4.v1.P4RuntimeOuterClass.MeterEntry buildWireMeterEntry(
            long index,
            long cir, long cburst, long pir, long pburst, long eburst,
            long greenPackets, long greenBytes,
            long yellowPackets, long yellowBytes,
            long redPackets, long redBytes) {
        return p4.v1.P4RuntimeOuterClass.MeterEntry.newBuilder()
                .setMeterId(METER_ID)
                .setIndex(Index.newBuilder().setIndex(index).build())
                .setConfig(MeterConfig.newBuilder()
                        .setCir(cir)
                        .setCburst(cburst)
                        .setPir(pir)
                        .setPburst(pburst)
                        .setEburst(eburst)
                        .build())
                .setCounterData(MeterCounterData.newBuilder()
                        .setGreen(CounterData.newBuilder()
                                .setPacketCount(greenPackets)
                                .setByteCount(greenBytes)
                                .build())
                        .setYellow(CounterData.newBuilder()
                                .setPacketCount(yellowPackets)
                                .setByteCount(yellowBytes)
                                .build())
                        .setRed(CounterData.newBuilder()
                                .setPacketCount(redPackets)
                                .setByteCount(redBytes)
                                .build())
                        .build())
                .build();
    }

    /**
     * In-process P4Runtime fake: auto-arbitrates as primary, accepts pipeline
     * config, records inbound {@code ReadRequest}s, and replies with
     * pre-canned meter cells configured via {@link #preloadMeterResponse}.
     */
    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final BlockingQueue<ReadRequest> readRequests = new LinkedBlockingQueue<>();
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.MeterEntry>> preloaded =
                new AtomicReference<>(List.of());

        void preloadMeterResponse(List<p4.v1.P4RuntimeOuterClass.MeterEntry> entries) {
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
            for (var me : preloaded.get()) {
                rb.addEntities(Entity.newBuilder().setMeterEntry(me).build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
