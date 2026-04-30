package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.ActionInstance;
import io.github.zhh2001.jp4.entity.TableEntry;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@code P4Switch.read(...).all() / one() / stream() / *Async}
 * against a real BMv2 instance with the basic.p4 pipeline pushed. Each test uses a
 * distinct match-key prefix to avoid cross-test collisions on the shared device, and
 * cleans up its own writes.
 *
 * <p>Read-side reverse parse is verified at byte level: the {@code dstAddr} key and
 * {@code port} action param round-trip through write → BMv2 → read → jp4 unchanged.
 */
class ReadRpcTest {

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;
    private static P4Info p4info;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("ReadRpcTest").start();
        p4info = P4Info.fromFile(Path.of("src/test/resources/p4/basic.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/basic.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (bmv2 != null) bmv2.close();
    }

    /** Scenario a (empty): read.all() on an empty table returns []. */
    @Test
    void readAllOnEmptyTableReturnsEmptyList() {
        List<TableEntry> got = sw.read("MyIngress.ipv4_lpm").all();
        assertTrue(got.isEmpty(), "empty table read should be []; got " + got);
    }

    /** Scenario a (empty / one): read.one() on an empty table returns Optional.empty(). */
    @Test
    void readOneOnEmptyTableReturnsEmpty() {
        Optional<TableEntry> got = sw.read("MyIngress.ipv4_lpm").one();
        assertTrue(got.isEmpty(), "one() on empty must be empty; got " + got);
    }

    /** Scenario b: insert one entry, read all; round-trip is byte-level. */
    @Test
    void readAllAfterInsertReturnsThatEntry() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100100), 24))
                .action("MyIngress.forward").param("port", 7)
                .build();
        sw.insert(e);
        try {
            List<TableEntry> got = sw.read("MyIngress.ipv4_lpm").all();
            assertEquals(1, got.size(), "exactly one entry; got " + got);
            TableEntry r = got.get(0);
            assertEquals("MyIngress.ipv4_lpm", r.tableName());
            Match.Lpm key = (Match.Lpm) r.match("hdr.ipv4.dstAddr");
            assertNotNull(key, "lpm key must round-trip");
            assertEquals(24, key.prefixLen(), "prefix len round-trips");
            assertNotNull(r.action(), "action half present");
            assertEquals("MyIngress.forward", r.action().name(), "action name round-trips");
            // port 7 canonicalises to one byte 0x07
            Bytes port = r.action().param("port");
            assertNotNull(port, "port param present");
            assertArrayEquals(new byte[]{7}, port.toByteArray(), "port byte-level round-trip");
        } finally {
            sw.delete(e);
        }
    }

    /** Scenario c: insert 3 entries, read all returns all three. */
    @Test
    void readAllAfterMultiInsertReturnsAll() {
        TableEntry e1 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100200), 24))
                .action("MyIngress.forward").param("port", 1).build();
        TableEntry e2 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100300), 24))
                .action("MyIngress.forward").param("port", 2).build();
        TableEntry e3 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100400), 24))
                .action("MyIngress.forward").param("port", 3).build();
        sw.batch().insert(e1).insert(e2).insert(e3).execute();
        try {
            List<TableEntry> got = sw.read("MyIngress.ipv4_lpm").all();
            assertEquals(3, got.size(), "expected 3 entries; got " + got.size());
        } finally {
            sw.batch().delete(e1).delete(e2).delete(e3).execute();
        }
    }

    /** Scenario d: read.one() on a table with N>=2 entries throws P4OperationException. */
    @Test
    void readOneWithMultipleEntriesThrows() {
        TableEntry e1 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100500), 24))
                .action("MyIngress.forward").param("port", 1).build();
        TableEntry e2 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100600), 24))
                .action("MyIngress.forward").param("port", 2).build();
        sw.batch().insert(e1).insert(e2).execute();
        try {
            P4OperationException ex = assertThrows(P4OperationException.class,
                    () -> sw.read("MyIngress.ipv4_lpm").one());
            assertTrue(ex.getMessage().contains("expected at most one entry"), ex.getMessage());
            assertTrue(ex.getMessage().contains("got 2"), ex.getMessage());
        } finally {
            sw.batch().delete(e1).delete(e2).execute();
        }
    }

    /** Scenario e: read.match(...).all() with the LPM key narrows / filters the result.
     *  BMv2 may return only the matching entry, or may return all entries if its filter
     *  semantics are loose. We assert the looser shape (the entry we expect is present),
     *  which both behaviours satisfy; empirics recorded in NOTES.md. */
    @Test
    void readMatchFiltersToTheExpectedEntry() {
        TableEntry e1 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100700), 24))
                .action("MyIngress.forward").param("port", 1).build();
        TableEntry e2 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100800), 24))
                .action("MyIngress.forward").param("port", 2).build();
        sw.batch().insert(e1).insert(e2).execute();
        try {
            List<TableEntry> got = sw.read("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100700), 24))
                    .all();
            // Empirics — recorded in NOTES.md for BMv2 read filter semantics.
            System.out.println("[read-filter-empirics] sent_lpm=10.16.7.0/24 table_size_after_writes=2 returned_count="
                    + got.size());
            for (TableEntry r : got) {
                Match.Lpm key = (Match.Lpm) r.match("hdr.ipv4.dstAddr");
                System.out.println("[read-filter-empirics]   entry: dstAddr=" + key.value()
                        + "/" + key.prefixLen());
            }
            // BMv2 empirics: see NOTES.md "BMv2 read filter semantics" for the actual
            // shape. The looser invariant (the asked-for entry is present) holds either way.
            boolean foundExpected = got.stream().anyMatch(e -> {
                Match.Lpm key = (Match.Lpm) e.match("hdr.ipv4.dstAddr");
                return key != null && key.prefixLen() == 24
                        && java.util.Arrays.equals(
                                key.value().canonical().toByteArray(),
                                Bytes.ofInt(0x0A100700).canonical().toByteArray());
            });
            assertTrue(foundExpected, "filtered read must include the asked-for entry; got " + got);
        } finally {
            sw.batch().delete(e1).delete(e2).execute();
        }
    }

    /** Scenario f: read.stream() consumed in try-with-resources; the stream closes
     *  cleanly and subsequent operations on the same switch still work. */
    @Test
    void readStreamClosesCleanlyAndSwitchStaysHealthy() {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100900), 24))
                .action("MyIngress.forward").param("port", 5).build();
        sw.insert(e);
        try {
            try (Stream<TableEntry> s = sw.read("MyIngress.ipv4_lpm").stream()) {
                long count = s.count();
                assertEquals(1, count, "stream count");
            }
            // After close, the switch must remain usable — cancel must not have leaked
            // state into the underlying channel.
            List<TableEntry> follow = sw.read("MyIngress.ipv4_lpm").all();
            assertEquals(1, follow.size(), "switch healthy after stream close; got " + follow);
        } finally {
            sw.delete(e);
        }
    }

    /** Scenario g: read.all() on an unknown table throws P4PipelineException with
     *  known-list (eager validation at .read("typo")). */
    @Test
    void readUnknownTableRejectedWithKnownList() {
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> sw.read("MyIngress.no_such_table"));
        assertTrue(ex.getMessage().contains("no table named") && ex.getMessage().contains("known:"),
                "must mention known tables; got: " + ex.getMessage());
    }

    /** Scenario h: read against a switch with no pipeline bound surfaces as
     *  P4PipelineException("no pipeline bound"). */
    @Test
    void readWithoutBoundPipelineRejected() throws Exception {
        try (BMv2TestSupport perTest = new BMv2TestSupport("readNoPipe").start();
             P4Switch noPipe = P4Switch.connectAsPrimary(perTest.grpcAddress())) {
            P4PipelineException ex = assertThrows(P4PipelineException.class,
                    () -> noPipe.read("MyIngress.ipv4_lpm"));
            assertTrue(ex.getMessage().contains("no pipeline bound"),
                    "must say no pipeline bound; got: " + ex.getMessage());
        }
    }

    /** Scenario i (async happy): read.allAsync().get() matches read.all() on the same state. */
    @Test
    void readAllAsyncMatchesSyncOutcome() throws Exception {
        TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100A00), 24))
                .action("MyIngress.forward").param("port", 9).build();
        sw.insert(e);
        try {
            List<TableEntry> sync = sw.read("MyIngress.ipv4_lpm").all();
            List<TableEntry> async = sw.read("MyIngress.ipv4_lpm").allAsync().get();
            assertEquals(sync.size(), async.size(), "sync vs async size match");
            assertEquals(1, async.size());
        } finally {
            sw.delete(e);
        }
    }

    /** Scenario i (async one): read.oneAsync() with multiple entries completes the
     *  future exceptionally with P4OperationException. */
    @Test
    void readOneAsyncMultipleEntriesCompletesExceptionally() {
        TableEntry e1 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100B00), 24))
                .action("MyIngress.forward").param("port", 1).build();
        TableEntry e2 = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(Bytes.ofInt(0x0A100C00), 24))
                .action("MyIngress.forward").param("port", 2).build();
        sw.batch().insert(e1).insert(e2).execute();
        try {
            var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> sw.read("MyIngress.ipv4_lpm").oneAsync().get());
            assertInstanceOf(P4OperationException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("expected at most one entry"),
                    ex.getCause().getMessage());
        } finally {
            sw.batch().delete(e1).delete(e2).execute();
        }
    }

    /** Scenario (bonus): unknown match field name surfaces with known-list at terminal. */
    @Test
    void readUnknownMatchFieldRejectedAtTerminal() {
        P4PipelineException ex = assertThrows(P4PipelineException.class, () ->
                sw.read("MyIngress.ipv4_lpm")
                        .match("hdr.ipv4.bogus", new Match.Lpm(Bytes.ofInt(0x0A100D00), 24))
                        .all());
        assertTrue(ex.getMessage().contains("Known fields:"),
                "must list known fields; got: " + ex.getMessage());
    }
}
