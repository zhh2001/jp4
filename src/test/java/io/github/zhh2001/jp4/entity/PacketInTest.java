package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PacketInTest {

    private static PacketIn build(Map<String, Bytes> meta) {
        return new PacketIn(Bytes.of((byte) 0), meta);
    }

    @Test
    void metadataReturnsBytesOrNull() {
        PacketIn p = build(Map.of("ingress_port", Bytes.ofInt(3)));
        assertEquals(Bytes.ofInt(3), p.metadata("ingress_port"));
        assertNull(p.metadata("not_present"));
    }

    @Test
    void metadataIntHappyPath() {
        PacketIn p = build(Map.of("ingress_port", Bytes.ofInt(3)));
        assertEquals(3, p.metadataInt("ingress_port"));
    }

    @Test
    void metadataIntZeroFromEmptyBytesReturnsZero() {
        PacketIn p = build(Map.of("ingress_port", Bytes.of()));
        assertEquals(0, p.metadataInt("ingress_port"));
    }

    @Test
    void metadataIntAtBoundary31BitsOk() {
        Bytes max = Bytes.ofInt(Integer.MAX_VALUE);
        PacketIn p = build(Map.of("ingress_port", max));
        assertEquals(Integer.MAX_VALUE, p.metadataInt("ingress_port"));
    }

    @Test
    void metadataIntRejectsAbsent() {
        PacketIn p = build(Map.of("ingress_port", Bytes.ofInt(3)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.metadataInt("not_present"));
        assertTrue(ex.getMessage().contains("PacketIn has no metadata field 'not_present'"));
        assertTrue(ex.getMessage().contains("known"));
    }

    @Test
    void metadataIntOver31BitsCarriesActionableMessage() {
        Bytes wide = Bytes.of(BigInteger.ONE.shiftLeft(31).toByteArray());
        PacketIn p = build(Map.of("ingress_port", wide));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.metadataInt("ingress_port"));
        assertTrue(ex.getMessage().contains("metadataLong"),
                "actionable error must name the recommended alternative");
    }

    @Test
    void metadataIntRejectsNullName() {
        PacketIn p = build(Map.of("ingress_port", Bytes.ofInt(3)));
        assertThrows(NullPointerException.class, () -> p.metadataInt(null));
    }

    @Test
    void metadataLongHappyPath() {
        PacketIn p = build(Map.of("trace_id", Bytes.ofLong(0x0102030405060708L)));
        assertEquals(0x0102030405060708L, p.metadataLong("trace_id"));
    }

    @Test
    void metadataLongAcceptsValueRequiringLong() {
        long v = (long) Integer.MAX_VALUE + 1L;
        PacketIn p = build(Map.of("trace_id", Bytes.ofLong(v)));
        assertEquals(v, p.metadataLong("trace_id"));
    }

    @Test
    void metadataLongRejectsOver63Bits() {
        Bytes wide = Bytes.of(BigInteger.ONE.shiftLeft(63).toByteArray());
        PacketIn p = build(Map.of("trace_id", wide));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.metadataLong("trace_id"));
        assertTrue(ex.getMessage().contains("metadata(String)"),
                "actionable error must name the binary fallback");
    }

    @Test
    void metadataLongRejectsAbsent() {
        PacketIn p = build(Map.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> p.metadataLong("trace_id"));
        assertTrue(ex.getMessage().contains("PacketIn has no metadata field 'trace_id'"),
                "absent message should name the field");
        assertTrue(ex.getMessage().contains("known"),
                "absent message should expose known-field list");
    }

    @Test
    void metadataLongZeroFromEmptyBytesReturnsZero() {
        PacketIn p = build(Map.of("trace_id", Bytes.of()));
        assertEquals(0L, p.metadataLong("trace_id"));
    }

    @Test
    void metadataLongRejectsNullName() {
        PacketIn p = build(Map.of());
        assertThrows(NullPointerException.class, () -> p.metadataLong(null));
    }

    @Test
    void metadataRejectsNullName() {
        PacketIn p = build(Map.of("ingress_port", Bytes.ofInt(3)));
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> p.metadata((String) null));
        assertEquals("name", ex.getMessage(),
                "NPE message should be the rejected parameter name (per Objects.requireNonNull convention)");
    }
}
