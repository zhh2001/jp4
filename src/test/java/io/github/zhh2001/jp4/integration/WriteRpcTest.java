package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.WriteResult;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.entity.UpdateFailure;
import io.github.zhh2001.jp4.error.ErrorCode;
import io.github.zhh2001.jp4.error.P4OperationException;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@code P4Switch.insert / modify / delete} and
 * {@code BatchBuilder.execute()} against a real BMv2 instance with the basic.p4
 * pipeline pushed. Each test uses a distinct match key to avoid cross-test
 * collisions on the shared device.
 *
 * <p>Read-back / byte-level round-trip verification is deferred to Phase 6C tests,
 * where {@code ReadQuery} is implemented; here, success is determined by the device
 * accepting (or correctly rejecting) the write RPC.
 */
class WriteRpcTest {

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("WriteRpcTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/basic.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/basic.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (bmv2 != null) bmv2.close();
    }

    /** Scenario a: insert one entry against the bound pipeline succeeds. */
    @Test
    void insertSucceedsForWellFormedEntry() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000100), 24))   // 10.0.1.0/24
                .action("MyIngress.forward").param("port", 1)
                .build();
        assertDoesNotThrow(() -> sw.insert(e));
        sw.delete(e);   // cleanup
    }

    /** Scenario c: delete after insert succeeds. */
    @Test
    void deleteAfterInsertSucceeds() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000400), 24))
                .action("MyIngress.forward").param("port", 4)
                .build();
        sw.insert(e);
        assertDoesNotThrow(() -> sw.delete(e));
    }

    /** Scenario b: modify after insert succeeds. */
    @Test
    void modifyAfterInsertSucceeds() {
        TableEntry inserted = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000300), 24))
                .action("MyIngress.forward").param("port", 1)
                .build();
        sw.insert(inserted);
        try {
            TableEntry modified = TableEntry.in("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000300), 24))
                    .action("MyIngress.forward").param("port", 2)
                    .build();
            assertDoesNotThrow(() -> sw.modify(modified));
        } finally {
            sw.delete(inserted);
        }
    }

    /** Insert duplicate key surfaces as P4OperationException with ALREADY_EXISTS or
     *  similar; BMv2's exact gRPC code is recorded but not asserted. */
    @Test
    void insertDuplicateKeyIsRejected() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000200), 24))
                .action("MyIngress.forward").param("port", 1)
                .build();
        sw.insert(e);
        try {
            assertThrows(P4OperationException.class, () -> sw.insert(e),
                    "second insert with the same key must be rejected");
        } finally {
            sw.delete(e);
        }
    }

    /** Scenario d: bogus field name is caught by EntryValidator before any RPC,
     *  surfaces as P4PipelineException with a known-list message. */
    @Test
    void bogusFieldNameRejectedWithKnownList() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.bogus", new Match.Lpm(Bytes.ofInt(0x0A000800), 24))
                .action("MyIngress.forward").param("port", 1)
                .build();

        P4PipelineException ex = assertThrows(P4PipelineException.class, () -> sw.insert(e));
        assertTrue(ex.getMessage().contains("Known fields:"),
                "validator must list known fields; got: " + ex.getMessage());
    }

    /** Scenario g: int value > 9 bits for the bit&lt;9&gt; "port" param is caught. */
    @Test
    void paramValueTooWideRejectedWithKnownList() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000B00), 24))
                .action("MyIngress.forward").param("port", 0x200)   // 10 bits
                .build();

        P4PipelineException ex = assertThrows(P4PipelineException.class, () -> sw.insert(e));
        String msg = ex.getMessage();
        assertTrue(msg.contains("port") && msg.contains("9"),
                "must mention param + declared bits; got: " + msg);
    }

    /** Scenario h: insert with no action is rejected at the validator. */
    @Test
    void insertWithoutActionRejected() {
        TableEntry deleteOnly = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000C00), 24))
                .build();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.insert(deleteOnly));
        assertTrue(ex.getMessage().contains("requires an action"), ex.getMessage());
    }

    /** Scenario i: delete without an action is accepted; the device removes by key. */
    @Test
    void deleteWithoutActionAccepted() {
        TableEntry inserted = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000D00), 24))
                .action("MyIngress.forward").param("port", 1)
                .build();
        sw.insert(inserted);

        TableEntry keyOnly = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000D00), 24))
                .build();
        assertDoesNotThrow(() -> sw.delete(keyOnly));
    }

    /** Scenario j: batch with several updates succeeds. */
    @Test
    void batchInsertAndDeleteSucceeds() {
        TableEntry e1 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000500), 24))
                .action("MyIngress.forward").param("port", 1).build();
        TableEntry e2 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000600), 24))
                .action("MyIngress.forward").param("port", 2).build();
        TableEntry e3 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000700), 24))
                .action("MyIngress.forward").param("port", 3).build();

        WriteResult r = sw.batch().insert(e1).insert(e2).insert(e3).execute();
        assertTrue(r.allSucceeded(), "batch should succeed; failures=" + r.failures());
        assertEquals(3, r.submitted());

        // Cleanup as a separate batch.
        sw.batch().delete(e1).delete(e2).delete(e3).execute();
    }

    /** Scenario l: async insert variant returns a CompletableFuture that completes
     *  with the same outcome as the sync call. */
    @Test
    void insertAsyncReturnsCompletableFuture() throws Exception {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000E00), 24))
                .action("MyIngress.forward").param("port", 1)
                .build();
        try {
            sw.insertAsync(e).get();   // happy path: completes with null
        } finally {
            sw.delete(e);
        }
    }

    /** Async failure path: validation error completes the future exceptionally with
     *  P4PipelineException — caller observes via .get(). */
    @Test
    void insertAsyncFailureCompletesFutureExceptionally() {
        TableEntry bogus = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.bogus", new Match.Lpm(Bytes.ofInt(0x0A000F00), 24))
                .action("MyIngress.forward").param("port", 1)
                .build();

        var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> sw.insertAsync(bogus).get());
        assertInstanceOf(P4PipelineException.class, ex.getCause());
    }

    /** Scenario k: batch with one duplicate-key insert mixed with valid ones.
     *  Observational test recording the BMv2 response shape (per-update details
     *  vs top-level status only); see NOTES.md "BMv2 partial-failure response
     *  format". The strict assertion is that the failed batch must not be
     *  reported as {@code allSucceeded()}. */
    @Test
    void batchPartialFailureSurfacesPerUpdateFailures() {
        TableEntry seed = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A001000), 24))
                .action("MyIngress.forward").param("port", 1).build();
        TableEntry b = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A001100), 24))
                .action("MyIngress.forward").param("port", 2).build();
        TableEntry c = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A001200), 24))
                .action("MyIngress.forward").param("port", 3).build();

        sw.insert(seed);   // pre-populate so the batched insert(seed) collides at index 0
        try {
            WriteResult r = sw.batch().insert(seed).insert(b).insert(c).execute();

            // Empirics — these println lines are intentional: they document the BMv2
            // response shape for future contributors (NOTES.md captures the same
            // observation). Removing them would lose the only on-the-wire trace.
            System.out.println("[partial-failure-empirics] submitted=" + r.submitted()
                    + " allSucceeded=" + r.allSucceeded()
                    + " failures.size=" + r.failures().size());
            for (UpdateFailure uf : r.failures()) {
                System.out.println("[partial-failure-empirics] index=" + uf.index()
                        + " code=" + uf.code()
                        + " message=" + uf.message());
            }

            assertFalse(r.allSucceeded(),
                    "batch with a duplicate-key insert must NOT be reported as all-succeeded");
            assertEquals(1, r.failures().size(),
                    "exactly one update should fail; OK details for accepted updates "
                    + "must be filtered. Got: " + r.failures());
            UpdateFailure only = r.failures().get(0);
            assertEquals(0, only.index(),
                    "the duplicate-key insert was at batch index 0");
            assertEquals(ErrorCode.ALREADY_EXISTS, only.code(),
                    "BMv2 maps duplicate-key insert to ALREADY_EXISTS");
            assertTrue(only.message().contains("Match entry exists"),
                    "BMv2 explanatory message should be carried through; got: " + only.message());
        } finally {
            try { sw.delete(seed); } catch (RuntimeException ignored) { }
            try { sw.delete(b);    } catch (RuntimeException ignored) { }
            try { sw.delete(c);    } catch (RuntimeException ignored) { }
        }
    }

    /** Operations against a switch that has no pipeline bound surface as
     *  P4PipelineException with a clear message — they do not fall through to a
     *  device-side error. Uses a per-test BMv2 to keep the shared one bound. */
    @Test
    void writeWithoutBoundPipelineRejected() throws Exception {
        try (BMv2TestSupport perTest = new BMv2TestSupport("noPipeline").start();
             P4Switch noPipe = P4Switch.connectAsPrimary(perTest.grpcAddress())) {
            TableEntry any = TableEntry.in("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A000100), 24))
                    .action("MyIngress.forward").param("port", 1)
                    .build();

            P4PipelineException ex = assertThrows(P4PipelineException.class,
                    () -> noPipe.insert(any));
            assertTrue(ex.getMessage().contains("no pipeline bound"),
                    "must say no pipeline bound; got: " + ex.getMessage());
        }
    }
}
