package io.github.zhh2001.jp4.pipeline;

import io.github.zhh2001.jp4.error.P4PipelineException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link P4Info} parsing, name lookup, and the read-only metadata
 * exposed by {@link TableInfo} / {@link ActionInfo} / {@link MatchFieldInfo} /
 * {@link ActionParamInfo}. No BMv2; pure parsing of pre-compiled artifacts checked
 * into {@code src/test/resources/p4/}.
 */
class P4InfoTest {

    private static final Path TXT  = Path.of("src/test/resources/p4/basic.p4info.txtpb");
    private static final Path BIN  = Path.of("src/test/resources/p4/basic.p4info.bin");
    private static final Path ALT  = Path.of("src/test/resources/p4/alt.p4info.txtpb");

    @Test
    void fromTextParsesBasic() {
        P4Info info = P4Info.fromText(TXT);
        assertTrue(info.tableNames().contains("MyIngress.ipv4_lpm"),
                "tableNames should contain ipv4_lpm; got " + info.tableNames());
        assertTrue(info.actionNames().contains("MyIngress.forward"),
                "actionNames should contain forward; got " + info.actionNames());
    }

    @Test
    void fromBinaryParsesBasic() {
        P4Info info = P4Info.fromBinary(BIN);
        assertTrue(info.tableNames().contains("MyIngress.ipv4_lpm"));
        assertTrue(info.actionNames().contains("MyIngress.forward"));
    }

    @Test
    void fromFileAutoSniffsBothFormats() {
        P4Info textVia = P4Info.fromFile(TXT);
        P4Info binVia  = P4Info.fromFile(BIN);
        // Two parses of the same source program produce identical structural data.
        assertEquals(textVia.tableNames().size(), binVia.tableNames().size());
        assertEquals(textVia.actionNames().size(), binVia.actionNames().size());
        TableInfo t1 = textVia.table("MyIngress.ipv4_lpm");
        TableInfo t2 = binVia.table("MyIngress.ipv4_lpm");
        assertEquals(t1.id(), t2.id(), "table id must match across formats");
    }

    @Test
    void fromBytesMalformedThrowsP4PipelineException() {
        byte[] garbage = "this is not p4info, not binary, not text".getBytes();
        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> P4Info.fromBytes(garbage));
        assertTrue(ex.getMessage().contains("could not parse"),
                "exception should explain parser failure; got: " + ex.getMessage());
    }

    @Test
    void fromFileNonexistentThrowsP4PipelineException(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.p4info");
        assertThrows(P4PipelineException.class, () -> P4Info.fromFile(missing));
    }

    @Test
    void tableLookupHitAndMiss() {
        P4Info info = P4Info.fromText(TXT);
        TableInfo t = info.table("MyIngress.ipv4_lpm");
        assertEquals("MyIngress.ipv4_lpm", t.name());
        assertTrue(t.id() > 0, "table id must be assigned by p4c");
        assertEquals(1024L, t.maxSize(), "size from basic.p4");

        P4PipelineException ex = assertThrows(P4PipelineException.class,
                () -> info.table("MyIngress.does_not_exist"));
        assertTrue(ex.getMessage().contains("does_not_exist"));
    }

    @Test
    void actionLookupHitAndMiss() {
        P4Info info = P4Info.fromText(TXT);
        ActionInfo forward = info.action("MyIngress.forward");
        assertEquals("MyIngress.forward", forward.name());
        assertTrue(forward.id() > 0);
        // basic.p4: forward(bit<9> port) → one parameter named "port" of width 9
        ActionParamInfo port = forward.param("port");
        assertEquals("port", port.name());
        assertEquals(9, port.bitWidth());

        assertThrows(P4PipelineException.class, () -> info.action("MyIngress.no_such_action"));
    }

    @Test
    void matchFieldsExposeKindAndWidth() {
        P4Info info = P4Info.fromText(TXT);
        TableInfo t = info.table("MyIngress.ipv4_lpm");
        List<MatchFieldInfo> fields = t.matchFields();
        assertEquals(1, fields.size(), "ipv4_lpm has one match field");
        MatchFieldInfo dst = fields.get(0);
        assertEquals("hdr.ipv4.dstAddr", dst.name());
        assertEquals(MatchFieldInfo.Kind.LPM, dst.matchKind());
        assertEquals(32, dst.bitWidth());

        // Lookup by name.
        assertSame(dst, t.matchField("hdr.ipv4.dstAddr"));
        assertThrows(P4PipelineException.class, () -> t.matchField("nope"));
    }

    @Test
    void altP4InfoStructurallyDistinct() {
        P4Info basic = P4Info.fromText(TXT);
        P4Info alt   = P4Info.fromText(ALT);
        // Different programs → disjoint table name sets.
        assertTrue(basic.tableNames().contains("MyIngress.ipv4_lpm"));
        assertFalse(basic.tableNames().contains("MyIngress.ethernet_dmac"));
        assertTrue(alt.tableNames().contains("MyIngress.ethernet_dmac"));
        assertFalse(alt.tableNames().contains("MyIngress.ipv4_lpm"));
        // alt.p4's table uses exact match, not lpm.
        assertEquals(MatchFieldInfo.Kind.EXACT,
                alt.table("MyIngress.ethernet_dmac").matchFields().get(0).matchKind());
    }

    @Test
    void emptyP4InfoIsEmpty() throws Exception {
        // An empty bytes-payload parses to a P4Info with zero tables / actions.
        // Manually construct an empty proto instance and round-trip its bytes.
        var emptyBytes = p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance().toByteArray();
        P4Info empty = P4Info.fromBytes(emptyBytes);
        assertTrue(empty.isEmpty(), "default-constructed P4Info should be empty");
        assertEquals(List.of(), empty.tableNames());
        assertEquals(List.of(), empty.actionNames());
    }
}
