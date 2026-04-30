package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@code .stream()} cancellation contract: closing a Stream mid-iteration
 * propagates through {@link io.grpc.Context} to the underlying {@code ClientCall},
 * and the {@link P4Switch} stays healthy afterwards (no channel leak, subsequent
 * read/write operations succeed).
 *
 * <p>Pre-populates with enough entries that BMv2 splits the response into multiple
 * {@code ReadResponse} chunks (or at least enough to make a partial read meaningful);
 * if BMv2 returns everything in one chunk, the test still passes — the close path
 * just runs after the iterator naturally exhausts.
 */
class ReadStreamCloseTest {

    private static final int ENTRIES = 100;

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;
    private static List<TableEntry> entries;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("ReadStreamCloseTest").start();
        P4Info p4info = P4Info.fromFile(Path.of("src/test/resources/p4/basic.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/test/resources/p4/basic.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);

        entries = new ArrayList<>(ENTRIES);
        var batch = sw.batch();
        for (int i = 0; i < ENTRIES; i++) {
            TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr",
                            new Match.Lpm(Bytes.ofInt(0x0A400000 | (i << 8)), 24))
                    .action("MyIngress.forward").param("port", (i % 8) + 1)
                    .build();
            entries.add(e);
            batch.insert(e);
        }
        batch.execute();
    }

    @AfterAll
    static void stop() {
        if (sw != null) {
            try {
                var b = sw.batch();
                for (TableEntry e : entries) b.delete(e);
                b.execute();
            } catch (RuntimeException ignored) { }
            sw.close();
        }
        if (bmv2 != null) bmv2.close();
    }

    /** Stream consumed to exhaustion via try-with-resources: count matches the
     *  pre-populated entry count; switch remains usable afterwards. */
    @Test
    void streamFullyConsumedAndClosedSwitchStillWorks() {
        long count;
        try (Stream<TableEntry> s = sw.read("MyIngress.ipv4_lpm").stream()) {
            count = s.count();
        }
        assertEquals(ENTRIES, count, "full stream count");

        // Subsequent operations must still work.
        long countAfter = sw.read("MyIngress.ipv4_lpm").stream().count();
        assertEquals(ENTRIES, countAfter, "switch healthy after full stream");
    }

    /** Stream closed mid-iteration: limit() to a small number, close, then verify
     *  subsequent reads still work — proves the cancel propagates without leaving
     *  the channel in a stuck state. Repeated 3 times to catch racy / leaky paths. */
    @RepeatedTest(3)
    void streamClosedEarlySwitchStaysHealthy() {
        List<TableEntry> partial;
        try (Stream<TableEntry> s = sw.read("MyIngress.ipv4_lpm").stream()) {
            partial = s.limit(3).toList();
        }
        assertEquals(3, partial.size(), "limit consumed exactly 3");

        // Subsequent .all() must complete normally and see all entries.
        List<TableEntry> after = sw.read("MyIngress.ipv4_lpm").all();
        assertEquals(ENTRIES, after.size(),
                "switch must remain healthy after early stream close; got " + after.size());

        // Subsequent stream must also work.
        try (Stream<TableEntry> s2 = sw.read("MyIngress.ipv4_lpm").stream()) {
            assertEquals(ENTRIES, s2.count(), "second stream healthy");
        }
    }
}
