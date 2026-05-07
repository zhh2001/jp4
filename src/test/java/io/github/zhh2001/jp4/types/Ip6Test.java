package io.github.zhh2001.jp4.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ip6Test {

    @Test
    void parsesShorthandLoopback() {
        Ip6 ip = Ip6.of("::1");
        byte[] expected = new byte[16];
        expected[15] = 1;
        assertArrayEquals(expected, ip.octets());
    }

    @Test
    void parsesFullForm() {
        Ip6 ip = Ip6.of("2001:db8::1");
        assertEquals(16, ip.octets().length);
        assertEquals((byte) 0x20, ip.octets()[0]);
        assertEquals((byte) 0x01, ip.octets()[1]);
        assertEquals((byte) 0x0d, ip.octets()[2]);
        assertEquals((byte) 0xb8, ip.octets()[3]);
        assertEquals((byte) 0x01, ip.octets()[15]);
    }

    @Test
    void rejectsHostnameLikeInput() {
        assertThrows(IllegalArgumentException.class, () -> Ip6.of("example.com"));
    }

    @Test
    void rejectsIpv4Literal() {
        assertThrows(IllegalArgumentException.class, () -> Ip6.of("10.0.0.1"));
    }

    @Test
    void octetsIsDefensiveCopy() {
        Ip6 ip = Ip6.of("::1");
        byte[] copy = ip.octets();
        copy[15] = 0;
        assertEquals(1, ip.octets()[15]);
    }

    @Test
    void equalsIsDeep() {
        assertEquals(Ip6.of("::1"), Ip6.of("::1"));
        assertEquals(Ip6.of("2001:db8::1"), Ip6.of("2001:db8::1"));
        assertNotEquals(Ip6.of("::1"), Ip6.of("::2"));
    }

    @Test
    void fromBytesHappyPath() {
        byte[] loopback = new byte[16];
        loopback[15] = 1;
        assertEquals(Ip6.of("::1"), Ip6.fromBytes(loopback));
    }

    @Test
    void fromBytesRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Ip6.fromBytes(new byte[15]));
        assertThrows(IllegalArgumentException.class,
                () -> Ip6.fromBytes(new byte[17]));
        assertThrows(IllegalArgumentException.class,
                () -> Ip6.fromBytes(new byte[0]));
    }

    @Test
    void fromBytesRejectsNull() {
        assertThrows(NullPointerException.class, () -> Ip6.fromBytes(null));
    }

    @Test
    void fromBytesIsDefensiveCopy() {
        byte[] src = new byte[16];
        src[15] = 1;
        Ip6 ip = Ip6.fromBytes(src);
        src[15] = 99;
        assertEquals(1, ip.octets()[15]);
    }
}
