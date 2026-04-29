package io.github.zhh2001.jp4.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ip4Test {

    @Test
    void parsesDottedQuad() {
        Ip4 ip = Ip4.of("10.0.0.1");
        assertArrayEquals(new byte[]{10, 0, 0, 1}, ip.octets());
    }

    @Test
    void parsesEdgeValues() {
        assertArrayEquals(new byte[]{0, 0, 0, 0}, Ip4.of("0.0.0.0").octets());
        assertArrayEquals(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255},
                Ip4.of("255.255.255.255").octets());
    }

    @Test
    void rejectsOutOfRangeOctet() {
        assertThrows(IllegalArgumentException.class, () -> Ip4.of("256.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Ip4.of("-1.0.0.0"));
    }

    @Test
    void rejectsWrongOctetCount() {
        assertThrows(IllegalArgumentException.class, () -> Ip4.of("10.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Ip4.of("10.0.0.0.1"));
    }

    @Test
    void ofIntBigEndian() {
        // 0x0A_00_00_01 → 10.0.0.1
        assertEquals(Ip4.of("10.0.0.1"), Ip4.of(0x0A_00_00_01));
    }

    @Test
    void octetsIsDefensiveCopy() {
        Ip4 ip = Ip4.of("10.0.0.1");
        byte[] copy = ip.octets();
        copy[0] = 99;
        assertArrayEquals(new byte[]{10, 0, 0, 1}, ip.octets());
    }

    @Test
    void equalsIsDeep() {
        assertEquals(Ip4.of("10.0.0.1"), Ip4.of("10.0.0.1"));
        assertNotEquals(Ip4.of("10.0.0.1"), Ip4.of("10.0.0.2"));
    }

    @Test
    void toStringIsDottedQuad() {
        assertEquals("10.0.0.1", Ip4.of("10.0.0.1").toString());
    }
}
