package io.github.zhh2001.jp4.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigestConfigTest {

    private static final Duration ONE_S = Duration.ofSeconds(1);
    private static final Duration TWO_S = Duration.ofSeconds(2);

    /**
     * Records auto-generate component-wise {@code equals} and {@code hashCode};
     * verify that two configurations built from equal components compare
     * equal across the full component set, and that changing one component
     * breaks equality.
     */
    @Test
    void recordEqualityAndHashCodeAreDeep() {
        DigestConfig a = new DigestConfig(ONE_S, 64, TWO_S);
        DigestConfig b = new DigestConfig(ONE_S, 64, TWO_S);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        DigestConfig differentMaxList = new DigestConfig(ONE_S, 128, TWO_S);
        assertNotEquals(a, differentMaxList);
    }

    /**
     * The canonical constructor rejects null in either Duration component
     * with a {@link NullPointerException} whose message is the rejected
     * component name, matching the project-wide null-rejection convention.
     */
    @Test
    void canonicalConstructorRejectsNullDurations() {
        NullPointerException nMax = assertThrows(NullPointerException.class, () ->
                new DigestConfig(null, 64, TWO_S));
        assertEquals("maxTimeout", nMax.getMessage());

        NullPointerException nAck = assertThrows(NullPointerException.class, () ->
                new DigestConfig(ONE_S, 64, null));
        assertEquals("ackTimeout", nAck.getMessage());
    }

    /**
     * Negative durations and a non-positive {@code maxListSize} surface
     * as {@link IllegalArgumentException}. Zero maxListSize is rejected
     * specifically because the protobuf default-unset value is also
     * {@code 0}, so accepting it would yield an ambiguous wire encoding.
     */
    @Test
    void canonicalConstructorRejectsInvalidNumericValues() {
        IllegalArgumentException eMax = assertThrows(IllegalArgumentException.class, () ->
                new DigestConfig(Duration.ofNanos(-1), 64, TWO_S));
        org.junit.jupiter.api.Assertions.assertTrue(
                eMax.getMessage().contains("maxTimeout"),
                "expected message to name maxTimeout: " + eMax.getMessage());

        IllegalArgumentException eAck = assertThrows(IllegalArgumentException.class, () ->
                new DigestConfig(ONE_S, 64, Duration.ofNanos(-1)));
        org.junit.jupiter.api.Assertions.assertTrue(
                eAck.getMessage().contains("ackTimeout"),
                "expected message to name ackTimeout: " + eAck.getMessage());

        IllegalArgumentException eZero = assertThrows(IllegalArgumentException.class, () ->
                new DigestConfig(ONE_S, 0, TWO_S));
        org.junit.jupiter.api.Assertions.assertTrue(
                eZero.getMessage().contains("maxListSize"),
                "expected message to name maxListSize: " + eZero.getMessage());

        IllegalArgumentException eNeg = assertThrows(IllegalArgumentException.class, () ->
                new DigestConfig(ONE_S, -1, TWO_S));
        org.junit.jupiter.api.Assertions.assertTrue(
                eNeg.getMessage().contains("maxListSize"),
                "expected message to name maxListSize: " + eNeg.getMessage());
    }
}
