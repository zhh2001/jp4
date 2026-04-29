package io.github.zhh2001.jp4.types;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class ElectionIdTest {

    @Test
    void ofLowSetsHighToZero() {
        ElectionId id = ElectionId.of(1L);
        assertEquals(0L, id.high());
        assertEquals(1L, id.low());
    }

    @Test
    void ofHighLow() {
        ElectionId id = ElectionId.of(7L, 42L);
        assertEquals(7L, id.high());
        assertEquals(42L, id.low());
    }

    @Test
    void fromBigIntegerRoundTrips() {
        BigInteger v = BigInteger.ONE.shiftLeft(70).add(BigInteger.valueOf(5));
        ElectionId id = ElectionId.fromBigInteger(v);
        assertEquals(v, id.toBigInteger());
    }

    @Test
    void fromBigIntegerRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> ElectionId.fromBigInteger(BigInteger.valueOf(-1)));
    }

    @Test
    void fromBigIntegerRejectsOverflow() {
        BigInteger tooBig = BigInteger.ONE.shiftLeft(128);
        assertThrows(IllegalArgumentException.class, () -> ElectionId.fromBigInteger(tooBig));
    }

    @Test
    void compareToIsUnsigned() {
        ElectionId small = ElectionId.of(1L);
        ElectionId large = ElectionId.of(-1L);   // unsigned 0xffffffff_ffffffff
        assertTrue(small.compareTo(large) < 0);
        assertTrue(large.compareTo(small) > 0);

        ElectionId hiSet = ElectionId.of(1L, 0L);
        ElectionId loOnly = ElectionId.of(0L, -1L);  // 0x0000... 0xffff...
        assertTrue(loOnly.compareTo(hiSet) < 0);
    }

    @Test
    void zeroAndMaxConstants() {
        assertEquals(0L, ElectionId.ZERO.high());
        assertEquals(0L, ElectionId.ZERO.low());
        assertEquals(-1L, ElectionId.MAX.high());
        assertEquals(-1L, ElectionId.MAX.low());
        assertTrue(ElectionId.ZERO.compareTo(ElectionId.MAX) < 0);
    }
}
