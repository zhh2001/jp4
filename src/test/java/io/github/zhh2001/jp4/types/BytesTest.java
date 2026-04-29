package io.github.zhh2001.jp4.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BytesTest {

    @Test
    void ofIntZeroIsSingleZeroByte() {
        Bytes b = Bytes.ofInt(0);
        assertEquals(1, b.length());
        assertArrayEquals(new byte[]{0}, b.toByteArray());
    }

    @Test
    void ofIntCanonicalStripsLeadingZeros() {
        assertArrayEquals(new byte[]{1}, Bytes.ofInt(1).toByteArray());
        assertArrayEquals(new byte[]{(byte) 0xff}, Bytes.ofInt(255).toByteArray());
        assertArrayEquals(new byte[]{1, 0}, Bytes.ofInt(256).toByteArray());
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                Bytes.ofInt(-1).toByteArray());   // unsigned 0xffffffff
    }

    @Test
    void ofIntFixedWidthPadsLeftWithZeros() {
        assertArrayEquals(new byte[]{0, 1}, Bytes.ofInt(1, 16).toByteArray());
        assertArrayEquals(new byte[]{0, 0, 0, 1}, Bytes.ofInt(1, 32).toByteArray());
        // bit<9> → 2 bytes; value 1 → [0, 1]
        assertArrayEquals(new byte[]{0, 1}, Bytes.ofInt(1, 9).toByteArray());
    }

    @Test
    void ofLongCanonical() {
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef},
                Bytes.ofLong(0xDEADBEEFL).toByteArray());
    }

    @Test
    void ofHexParses() {
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad}, Bytes.ofHex("dead").toByteArray());
    }

    @Test
    void canonicalStripsLeadingZeros() {
        assertArrayEquals(new byte[]{1}, Bytes.of((byte) 0, (byte) 0, (byte) 1).canonical().toByteArray());
        assertArrayEquals(new byte[]{0}, Bytes.of((byte) 0, (byte) 0, (byte) 0).canonical().toByteArray());
        assertArrayEquals(new byte[]{0}, Bytes.of().canonical().toByteArray());
    }

    @Test
    void toByteArrayIsDefensiveCopy() {
        Bytes b = Bytes.of((byte) 1, (byte) 2);
        byte[] copy = b.toByteArray();
        copy[0] = 99;
        assertArrayEquals(new byte[]{1, 2}, b.toByteArray());
    }

    @Test
    void equalsAndHashCodeAreDeep() {
        Bytes a = Bytes.of((byte) 1, (byte) 2);
        Bytes b = Bytes.of((byte) 1, (byte) 2);
        Bytes c = Bytes.of((byte) 1, (byte) 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
