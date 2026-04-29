package io.github.zhh2001.jp4.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MacTest {

    @Test
    void parsesColonForm() {
        Mac m = Mac.of("de:ad:be:ef:00:01");
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef, 0, 1}, m.octets());
    }

    @Test
    void parsesHyphenForm() {
        assertEquals(Mac.of("de:ad:be:ef:00:01"), Mac.of("de-ad-be-ef-00-01"));
    }

    @Test
    void parsesUnseparatedForm() {
        assertEquals(Mac.of("de:ad:be:ef:00:01"), Mac.of("deadbeef0001"));
    }

    @Test
    void parsingIsCaseInsensitive() {
        assertEquals(Mac.of("de:ad:be:ef:00:01"), Mac.of("DE:AD:BE:EF:00:01"));
    }

    @Test
    void invalidLengthThrows() {
        assertThrows(IllegalArgumentException.class, () -> Mac.of("de:ad:be:ef"));
    }

    @Test
    void ofLongUsesLow48Bits() {
        Mac m = Mac.of(0x0000_0000_0001L);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 1}, m.octets());
    }

    @Test
    void octetsIsDefensiveCopy() {
        Mac m = Mac.of("de:ad:be:ef:00:01");
        byte[] copy = m.octets();
        copy[0] = 0;
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef, 0, 1}, m.octets());
    }

    @Test
    void equalsAndHashCodeAreDeep() {
        assertEquals(Mac.of("de:ad:be:ef:00:01"), Mac.of("de:ad:be:ef:00:01"));
        assertEquals(Mac.of("de:ad:be:ef:00:01").hashCode(), Mac.of("de:ad:be:ef:00:01").hashCode());
        assertNotEquals(Mac.of("de:ad:be:ef:00:01"), Mac.of("de:ad:be:ef:00:02"));
    }

    @Test
    void toStringIsCanonicalLowercaseColonForm() {
        assertEquals("de:ad:be:ef:00:01", Mac.of("DE:AD:BE:EF:00:01").toString());
    }

    @Test
    void toBytesProducesSixByteValue() {
        Bytes b = Mac.of("de:ad:be:ef:00:01").toBytes();
        assertEquals(6, b.length());
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef, 0, 1}, b.toByteArray());
    }
}
