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
    void reverseIdLookupsRoundTripWithNameLookups() {
        P4Info info = P4Info.fromText(TXT);
        TableInfo lpm = info.table("MyIngress.ipv4_lpm");
        // Table id round-trip
        assertSame(lpm, info.tableInfoById(lpm.id()),
                "tableInfoById should return the same TableInfo instance");
        // Match field id round-trip
        MatchFieldInfo dst = lpm.matchField("hdr.ipv4.dstAddr");
        assertSame(dst, lpm.matchFieldById(dst.id()));
        // Action id round-trip
        ActionInfo fwd = info.action("MyIngress.forward");
        assertSame(fwd, info.actionInfoById(fwd.id()));
        // Param id round-trip
        ActionParamInfo port = fwd.param("port");
        assertSame(port, fwd.paramById(port.id()));
    }

    @Test
    void reverseIdLookupsReturnNullForUnknownIds() {
        P4Info info = P4Info.fromText(TXT);
        assertNull(info.tableInfoById(999_999), "unknown table id");
        assertNull(info.actionInfoById(999_999), "unknown action id");
        TableInfo lpm = info.table("MyIngress.ipv4_lpm");
        assertNull(lpm.matchFieldById(999_999), "unknown match field id");
        ActionInfo fwd = info.action("MyIngress.forward");
        assertNull(fwd.paramById(999_999), "unknown param id");
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

    @Test
    void digestLookupResolvesDigestNameById() {
        // Build a P4Info proto carrying one Digest declaration and round-trip its
        // bytes through P4Info.fromBytes to exercise the parsing path used by
        // ConnectorTest fixtures. Mirrors the inline-proto style of
        // emptyP4InfoIsEmpty; once a real digest_idle.p4 fixture lands, that
        // file's compiled .p4info.txtpb is the assertion surface, but the
        // ground-truth lookup logic is what this unit test covers.
        int digestId = 0x01000001;
        String digestName = "MyIngress.learn_digest";
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addDigests(p4.config.v1.P4InfoOuterClass.Digest.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(digestId)
                                .setName(digestName)
                                .build())
                        .build())
                .build();
        P4Info info = P4Info.fromBytes(proto.toByteArray());
        assertEquals(digestName, info.digestNameById(digestId),
                "digest id should resolve to the declared P4 name");
    }

    @Test
    void digestLookupReturnsNullForUnknownId() {
        // P4Info fixtures used by the rest of the test suite carry no digest
        // declarations; queries against any digest id therefore return null,
        // matching the lookup-fail convention shared with tableInfoById /
        // actionInfoById / packetInFieldById.
        P4Info info = P4Info.fromText(TXT);
        assertNull(info.digestNameById(999_999), "unknown digest id");
        // An empty P4Info also returns null, regardless of the queried id.
        var emptyBytes = p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance().toByteArray();
        P4Info empty = P4Info.fromBytes(emptyBytes);
        assertNull(empty.digestNameById(0x01000001), "no digest declared in empty P4Info");
    }

    @Test
    void counterLookupResolvesByNameAndId() {
        int counterId = 0x0c000001;
        String counterName = "MyIngress.pkt_counter";
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addCounters(p4.config.v1.P4InfoOuterClass.Counter.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(counterId).setName(counterName).build())
                        .setSpec(p4.config.v1.P4InfoOuterClass.CounterSpec.newBuilder()
                                .setUnit(p4.config.v1.P4InfoOuterClass.CounterSpec.Unit.BOTH)
                                .build())
                        .setSize(1024)
                        .build())
                .build();
        P4Info info = P4Info.fromBytes(proto.toByteArray());
        CounterInfo c = info.counter(counterName);
        assertEquals(counterName, c.name());
        assertEquals(counterId, c.id());
        assertEquals(CounterInfo.Unit.BOTH, c.unit());
        assertEquals(1024L, c.size());
        assertSame(c, info.counterById(counterId),
                "counterById should return the same CounterInfo instance");
        assertEquals(List.of(counterName), info.counterNames());
    }

    @Test
    void meterLookupResolvesByNameAndId() {
        int meterId = 0x12000001;
        String meterName = "MyIngress.rate_meter";
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addMeters(p4.config.v1.P4InfoOuterClass.Meter.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(meterId).setName(meterName).build())
                        .setSpec(p4.config.v1.P4InfoOuterClass.MeterSpec.newBuilder()
                                .setUnit(p4.config.v1.P4InfoOuterClass.MeterSpec.Unit.PACKETS)
                                .build())
                        .setSize(256)
                        .build())
                .build();
        P4Info info = P4Info.fromBytes(proto.toByteArray());
        MeterInfo m = info.meter(meterName);
        assertEquals(meterName, m.name());
        assertEquals(meterId, m.id());
        assertEquals(MeterInfo.Unit.PACKETS, m.unit());
        assertEquals(256L, m.size());
        assertSame(m, info.meterById(meterId));
        assertEquals(List.of(meterName), info.meterNames());
    }

    @Test
    void registerLookupResolvesByNameAndId() {
        int registerId = 0x10000001;
        String registerName = "MyIngress.flow_counters";
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addRegisters(p4.config.v1.P4InfoOuterClass.Register.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(registerId).setName(registerName).build())
                        .setSize(512)
                        .build())
                .build();
        P4Info info = P4Info.fromBytes(proto.toByteArray());
        RegisterInfo r = info.register(registerName);
        assertEquals(registerName, r.name());
        assertEquals(registerId, r.id());
        assertEquals(512L, r.size());
        assertSame(r, info.registerById(registerId));
        assertEquals(List.of(registerName), info.registerNames());
    }

    @Test
    void actionProfileLookupResolvesByNameAndId() {
        int profileId = 0x11000001;
        String profileName = "MyIngress.ecmp_profile";
        var proto = p4.config.v1.P4InfoOuterClass.P4Info.newBuilder()
                .addActionProfiles(p4.config.v1.P4InfoOuterClass.ActionProfile.newBuilder()
                        .setPreamble(p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
                                .setId(profileId).setName(profileName).build())
                        .setWithSelector(true)
                        .setSize(64)
                        .setMaxGroupSize(8)
                        .addTableIds(0x03000001)
                        .addTableIds(0x03000002)
                        .build())
                .build();
        P4Info info = P4Info.fromBytes(proto.toByteArray());
        ActionProfileInfo ap = info.actionProfile(profileName);
        assertEquals(profileName, ap.name());
        assertEquals(profileId, ap.id());
        assertTrue(ap.withSelector(), "selector flag should round-trip");
        assertEquals(64L, ap.size());
        assertEquals(8, ap.maxGroupSize());
        assertTrue(ap.tableIds().contains(0x03000001));
        assertTrue(ap.tableIds().contains(0x03000002));
        assertEquals(2, ap.tableIds().size());
        assertSame(ap, info.actionProfileById(profileId));
        assertEquals(List.of(profileName), info.actionProfileNames());
    }

    @Test
    void unknownEntityNameThrowsWithKnownList() {
        // Use basic.p4info.txtpb: no counters/meters/registers/actionProfiles
        // declared, so any lookup by name throws with an empty known-list.
        P4Info info = P4Info.fromText(TXT);
        for (var probe : new Runnable[]{
                () -> info.counter("nope"),
                () -> info.meter("nope"),
                () -> info.register("nope"),
                () -> info.actionProfile("nope"),
        }) {
            P4PipelineException ex = assertThrows(P4PipelineException.class, probe::run);
            assertTrue(ex.getMessage().contains("nope"),
                    "expected message to name the queried entity 'nope': " + ex.getMessage());
            assertTrue(ex.getMessage().contains("known:"),
                    "expected message to carry a known-list hint: " + ex.getMessage());
        }
    }

    @Test
    void unknownEntityIdReturnsNull() {
        P4Info info = P4Info.fromText(TXT);
        assertNull(info.counterById(999_999), "unknown counter id");
        assertNull(info.meterById(999_999), "unknown meter id");
        assertNull(info.registerById(999_999), "unknown register id");
        assertNull(info.actionProfileById(999_999), "unknown action profile id");
    }
}
