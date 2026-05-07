package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionInstanceTest {

    private static ActionInstance build(Map<String, Bytes> params) {
        return new ActionInstance("MyIngress.set_egress", params);
    }

    @Test
    void paramReturnsBytesOrNull() {
        ActionInstance a = build(Map.of("port", Bytes.ofInt(7)));
        assertEquals(Bytes.ofInt(7), a.param("port"));
        assertNull(a.param("not_present"));
    }

    @Test
    void paramIntHappyPath() {
        ActionInstance a = build(Map.of("port", Bytes.ofInt(7)));
        assertEquals(7, a.paramInt("port"));
    }

    @Test
    void paramIntZeroFromEmptyBytesReturnsZero() {
        ActionInstance a = build(Map.of("port", Bytes.of()));
        assertEquals(0, a.paramInt("port"));
    }

    @Test
    void paramIntAtBoundary31BitsOk() {
        // 2^31 - 1 = Integer.MAX_VALUE; bit-length 31; fits.
        Bytes max = Bytes.ofInt(Integer.MAX_VALUE);
        ActionInstance a = build(Map.of("port", max));
        assertEquals(Integer.MAX_VALUE, a.paramInt("port"));
    }

    @Test
    void paramIntRejectsAbsent() {
        ActionInstance a = build(Map.of("port", Bytes.ofInt(7)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> a.paramInt("not_present"));
        assertTrue(ex.getMessage().contains("ActionInstance has no parameter 'not_present'"),
                "absent message should name the parameter");
        assertTrue(ex.getMessage().contains("known"),
                "absent message should expose known-parameter list");
    }

    @Test
    void paramIntRejectsOver31Bits() {
        // 2^31 = bit-length 32; doesn't fit signed int.
        Bytes wide = Bytes.of(BigInteger.ONE.shiftLeft(31).toByteArray());
        ActionInstance a = build(Map.of("port", wide));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> a.paramInt("port"));
        assertTrue(ex.getMessage().contains("paramLong"),
                "actionable error must name the recommended alternative");
    }

    @Test
    void paramIntRejectsNullName() {
        ActionInstance a = build(Map.of("port", Bytes.ofInt(7)));
        assertThrows(NullPointerException.class, () -> a.paramInt(null));
    }

    @Test
    void paramLongHappyPath() {
        ActionInstance a = build(Map.of("port", Bytes.ofLong(0x0102030405060708L)));
        assertEquals(0x0102030405060708L, a.paramLong("port"));
    }

    @Test
    void paramLongAcceptsValueRequiringLong() {
        // Value above int range but within long range.
        long v = (long) Integer.MAX_VALUE + 1L;
        ActionInstance a = build(Map.of("port", Bytes.ofLong(v)));
        assertEquals(v, a.paramLong("port"));
    }

    @Test
    void paramLongRejectsOver63Bits() {
        Bytes wide = Bytes.of(BigInteger.ONE.shiftLeft(63).toByteArray());
        ActionInstance a = build(Map.of("port", wide));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> a.paramLong("port"));
        assertTrue(ex.getMessage().contains("param(String)"),
                "actionable error must name the binary fallback");
    }

    @Test
    void paramLongRejectsAbsent() {
        ActionInstance a = build(Map.of());
        assertThrows(IllegalStateException.class, () -> a.paramLong("port"));
    }

    @Test
    void paramLongZeroFromEmptyBytesReturnsZero() {
        ActionInstance a = build(Map.of("port", Bytes.of()));
        assertEquals(0L, a.paramLong("port"));
    }
}
