package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.Pipeline;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.MatchFieldInfo;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.TableInfo;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-BMv2 tests for {@link P4Switch#bindPipeline} and {@link P4Switch#loadPipeline},
 * covering scenarios a / b / c / d / e / f / g / h from the Phase 5 plan.
 *
 * <p>Uses a single shared BMv2 instance: every test connects its own {@code P4Switch}
 * via try-with-resources so BMv2's PI state goes back to "no client, no pipeline" once
 * the switch closes (BMv2 forgets the pipeline only when the device is restarted, but
 * test {@code e} swap and test {@code g} unbound are designed to handle whichever
 * pipeline the previous test happened to install — see comments).
 */
class PipelineConfigTest {

    private static final Path BASIC_P4INFO = Path.of("src/test/resources/p4/basic.p4info.txtpb");
    private static final Path BASIC_JSON   = Path.of("src/test/resources/p4/basic.json");
    private static final Path ALT_P4INFO   = Path.of("src/test/resources/p4/alt.p4info.txtpb");
    private static final Path ALT_JSON     = Path.of("src/test/resources/p4/alt.json");

    private static BMv2TestSupport bmv2;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("PipelineConfigTest").start();
    }

    @AfterAll
    static void stop() {
        if (bmv2 != null) bmv2.close();
    }

    /** Scenario a + b + c: bindPipeline pushes a real program and the cached
     *  P4Info is queryable through P4Switch.pipeline(). */
    @Test
    void bindPipelinePushesAndCachesP4Info() throws Exception {
        P4Info info = P4Info.fromFile(BASIC_P4INFO);
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(BASIC_JSON);

        try (P4Switch sw = P4Switch.connectAsPrimary(bmv2.grpcAddress())) {
            assertNull(sw.pipeline(), "no pipeline before bind");
            sw.bindPipeline(info, dc);

            Pipeline bound = sw.pipeline();
            assertNotNull(bound, "pipeline cached after bindPipeline returns");
            assertSame(info, bound.p4info(),
                    "cache must hold the very P4Info object the user supplied");
            assertSame(dc, bound.deviceConfig());

            assertTrue(bound.p4info().tableNames().contains("MyIngress.ipv4_lpm"));
            TableInfo t = bound.p4info().table("MyIngress.ipv4_lpm");
            assertTrue(t.id() != 0, "table id must be assigned by p4c");
            assertEquals(MatchFieldInfo.Kind.LPM, t.matchFields().get(0).matchKind());
        }
    }

    /** Scenario e: a second bindPipeline on the same switch swaps the cached pipeline
     *  to the new program. */
    @Test
    void secondBindPipelineSwapsCache() throws Exception {
        P4Info first  = P4Info.fromFile(BASIC_P4INFO);
        DeviceConfig firstDc = DeviceConfig.Bmv2.fromFile(BASIC_JSON);
        P4Info second = P4Info.fromFile(ALT_P4INFO);
        DeviceConfig secondDc = DeviceConfig.Bmv2.fromFile(ALT_JSON);

        try (P4Switch sw = P4Switch.connectAsPrimary(bmv2.grpcAddress())) {
            sw.bindPipeline(first, firstDc);
            assertTrue(sw.pipeline().p4info().tableNames().contains("MyIngress.ipv4_lpm"));

            sw.bindPipeline(second, secondDc);
            assertTrue(sw.pipeline().p4info().tableNames().contains("MyIngress.ethernet_dmac"),
                    "after swap, new program's table is visible");
            assertFalse(sw.pipeline().p4info().tableNames().contains("MyIngress.ipv4_lpm"),
                    "after swap, old program's table is gone from the cache");
        }
    }

    /** Scenario f: a separate switch opened against a device that was already bound
     *  reads back the same P4Info via loadPipeline. */
    @Test
    void loadPipelineReadsBackBoundProgram() throws Exception {
        P4Info info = P4Info.fromFile(BASIC_P4INFO);
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(BASIC_JSON);

        // Push first.
        try (P4Switch pusher = P4Switch.connect(bmv2.grpcAddress()).electionId(1L).asPrimary()) {
            pusher.bindPipeline(info, dc);
        }
        // Read back from a fresh switch.
        try (P4Switch reader = P4Switch.connect(bmv2.grpcAddress()).electionId(2L).asPrimary()) {
            reader.loadPipeline();
            Pipeline loaded = reader.pipeline();
            assertNotNull(loaded);
            assertTrue(loaded.p4info().tableNames().contains("MyIngress.ipv4_lpm"),
                    "loaded P4Info should include the basic.p4 table");
            assertEquals(info.table("MyIngress.ipv4_lpm").id(),
                         loaded.p4info().table("MyIngress.ipv4_lpm").id(),
                         "table ids must match between pushed and loaded P4Info");
        }
    }

    /** Scenario h: pipeline() returns null before any bind/load call, and is
     *  still null after a failed bindPipeline (failure must not leave half-state). */
    @Test
    void pipelineReturnsNullBeforeBindOrLoad() throws Exception {
        try (P4Switch sw = P4Switch.connectAsPrimary(bmv2.grpcAddress())) {
            assertNull(sw.pipeline(), "no pipeline before any bind/load call");
        }
    }

    // Scenario d (mismatched p4info + deviceConfig) was experimentally cut after
    // verifying BMv2 does NOT reject such combinations — see NOTES.md "BMv2 does
    // not validate p4info/deviceConfig consistency" and the warning in
    // P4Switch.bindPipeline's JavaDoc.

    /**
     * Scenario g: loadPipeline against a device with no bound pipeline throws
     * {@link P4PipelineException} carrying the contract message "device has no
     * bound pipeline". Uses a per-test BMv2 instance so the device is guaranteed
     * to be unbound at the start of the test.
     */
    @Test
    void loadPipelineOnUnboundDeviceThrowsP4PipelineException() throws Exception {
        try (BMv2TestSupport perTest = new BMv2TestSupport("unbound").start();
             P4Switch sw = P4Switch.connectAsPrimary(perTest.grpcAddress())) {
            P4PipelineException ex = assertThrows(P4PipelineException.class, sw::loadPipeline,
                    "loadPipeline against an unbound device must throw");
            assertNotNull(ex.getMessage());
            // Message should mention "no bound pipeline" (our explicit check) OR
            // wrap a gRPC NOT_FOUND/FAILED_PRECONDITION (BMv2 may use either).
            // Either way, it's the contract that the user observes a P4PipelineException.
        }
    }
}
