package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.github.zhh2001.jp4.entity.RegisterEntry;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import p4.v1.P4DataOuterClass.P4Data;
import p4.v1.P4RuntimeGrpc;
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
import java.util.Arrays;
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
 * Unit tests for {@code P4Switch.readRegister} against an in-process
 * gRPC fake. Mirrors the harness pattern established in
 * {@code P4SwitchReadMeterTest}; the data column is exercised against
 * the serialised-P4Data convention the entity record carries.
 */
class P4SwitchReadRegisterTest {

    private static final int REGISTER_ID = 0x15000001;
    private static final String REGISTER_NAME = "MyIngress.flow_counters";

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
    void readRegisterNullArgumentRejected() {
        sw = connectAndBindPipeline();
        assertThrows(NullPointerException.class, () -> sw.readRegister(null));
    }

    @Test
    void readRegisterRequiresPipelineBound() {
        sw = P4Switch.connect("localhost:" + port).asPrimary();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readRegister(REGISTER_NAME));
        assertTrue(ex.getMessage().contains("no pipeline bound"),
                "expected pipeline-bound message; got: " + ex.getMessage());
    }

    @Test
    void readRegisterUnknownNameRejected() {
        sw = connectAndBindPipeline();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.readRegister("MyIngress.does_not_exist"));
        assertTrue(ex.getMessage().contains("does_not_exist"));
        assertTrue(ex.getMessage().contains("known:"),
                "expected known-list hint; got: " + ex.getMessage());
    }

    @Test
    void readRegisterAllReturnsBuiltEntries() throws Exception {
        sw = connectAndBindPipeline();
        byte[] bits0 = new byte[]{0x01, 0x02};
        byte[] bits1 = new byte[]{(byte) 0xFF, (byte) 0xFE};
        fake.preloadRegisterResponse(List.of(
                buildWireRegisterEntry(0, bits0),
                buildWireRegisterEntry(1, bits1)));

        List<RegisterEntry> entries = sw.readRegister(REGISTER_NAME).all();
        assertEquals(2, entries.size());

        RegisterEntry e0 = entries.get(0);
        assertEquals(REGISTER_NAME, e0.registerName());
        assertEquals(0L, e0.index());
        // Round-trip through P4Data.parseFrom to confirm the bytes are a
        // serialised P4Data proto, not an extracted bitstring.
        P4Data decoded0 = P4Data.parseFrom(e0.data().toByteArray());
        assertTrue(decoded0.hasBitstring(),
                "decoded data should carry the bitstring oneof variant");
        assertArrayEquals(bits0, decoded0.getBitstring().toByteArray());

        RegisterEntry e1 = entries.get(1);
        assertEquals(REGISTER_NAME, e1.registerName());
        assertEquals(1L, e1.index());
        P4Data decoded1 = P4Data.parseFrom(e1.data().toByteArray());
        assertArrayEquals(bits1, decoded1.getBitstring().toByteArray());
    }

    @Test
    void readRegisterIndexFilterAssemblesCorrectly() {
        sw = connectAndBindPipeline();
        fake.preloadRegisterResponse(List.of(
                buildWireRegisterEntry(7, new byte[]{0x00})));

        sw.readRegister(REGISTER_NAME).index(7L).all();
        ReadRequest captured = fake.firstReadRequest();
        assertNotNull(captured, "fake server should have captured a ReadRequest");
        assertEquals(1, captured.getEntitiesCount());
        Entity e = captured.getEntities(0);
        assertTrue(e.hasRegisterEntry(), "entity should carry register_entry");
        var re = e.getRegisterEntry();
        assertEquals(REGISTER_ID, re.getRegisterId());
        assertTrue(re.hasIndex(), "index filter should be present on the wire");
        assertEquals(7L, re.getIndex().getIndex());
    }

    @Test
    void readRegisterWhereFiltersClientSide() {
        sw = connectAndBindPipeline();
        fake.preloadRegisterResponse(Arrays.asList(
                buildWireRegisterEntry(0, new byte[]{0x00}),
                buildWireRegisterEntry(1, new byte[]{0x42}),
                buildWireRegisterEntry(2, new byte[]{0x00})));

        List<RegisterEntry> entries = sw.readRegister(REGISTER_NAME)
                .where(r -> r.index() > 0)
                .all();
        assertEquals(2, entries.size());
        assertEquals(1L, entries.get(0).index());
        assertEquals(2L, entries.get(1).index());
    }

    private P4Switch connectAndBindPipeline() {
        P4Switch s = P4Switch.connect("localhost:" + port).asPrimary();
        s.bindPipeline(buildP4InfoWithRegister(), DeviceConfig.Bmv2.fromBytes(new byte[]{0}));
        return s;
    }

    private static P4Info buildP4InfoWithRegister() {
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addRegisters(p4.config.v1.P4InfoOuterClass.Register.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(REGISTER_ID)
                                .setName(REGISTER_NAME)
                                .build())
                        .setSize(128)
                        .build())
                .build();
        return P4Info.fromBytes(proto.toByteArray());
    }

    /**
     * Build a wire RegisterEntry for the fake server to return. The payload
     * is wrapped as a bit<W>-style P4Data (bitstring oneof variant) — the
     * 99% common case for P4 registers.
     */
    private static p4.v1.P4RuntimeOuterClass.RegisterEntry buildWireRegisterEntry(
            long index, byte[] bitstring) {
        return p4.v1.P4RuntimeOuterClass.RegisterEntry.newBuilder()
                .setRegisterId(REGISTER_ID)
                .setIndex(Index.newBuilder().setIndex(index).build())
                .setData(P4Data.newBuilder()
                        .setBitstring(ByteString.copyFrom(bitstring))
                        .build())
                .build();
    }

    /**
     * In-process P4Runtime fake: auto-arbitrates as primary, accepts pipeline
     * config, records inbound {@code ReadRequest}s, and replies with
     * pre-canned register cells configured via {@link #preloadRegisterResponse}.
     */
    private static final class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        private final BlockingQueue<ReadRequest> readRequests = new LinkedBlockingQueue<>();
        private final AtomicReference<List<p4.v1.P4RuntimeOuterClass.RegisterEntry>> preloaded =
                new AtomicReference<>(List.of());

        void preloadRegisterResponse(List<p4.v1.P4RuntimeOuterClass.RegisterEntry> entries) {
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
            for (var re : preloaded.get()) {
                rb.addEntities(Entity.newBuilder().setRegisterEntry(re).build());
            }
            respObs.onNext(rb.build());
            respObs.onCompleted();
        }
    }
}
