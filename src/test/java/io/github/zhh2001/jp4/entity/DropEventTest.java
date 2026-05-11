package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DropEventTest {

    private static final PacketIn SAMPLE_PACKET =
            new PacketIn(Bytes.of(new byte[]{1, 2, 3}), Map.of());

    /**
     * Records auto-generate component-wise {@code equals} and {@code hashCode};
     * verify that the auto-generated implementations behave as deep equality
     * across the full component set so the type is safe to use as a map key
     * or in equality-based test assertions.
     */
    @Test
    void recordEqualityAndHashCodeAreDeep() {
        Instant ts = Instant.parse("2026-05-09T12:00:00Z");
        DropEvent a = new DropEvent(DropEvent.Reason.QUEUE_FULL, ts, SAMPLE_PACKET, "deque at 1024");
        DropEvent b = new DropEvent(DropEvent.Reason.QUEUE_FULL, ts, SAMPLE_PACKET, "deque at 1024");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        DropEvent differentReason = new DropEvent(
                DropEvent.Reason.FILTERED, ts, SAMPLE_PACKET, "deque at 1024");
        assertNotEquals(a, differentReason);
    }

    /**
     * The canonical constructor rejects null in every component with a
     * {@link NullPointerException} whose message is the rejected component
     * name, matching the project-wide null-rejection convention documented
     * in each {@code package-info.java}.
     */
    @Test
    void canonicalConstructorRejectsNullComponents() {
        Instant ts = Instant.parse("2026-05-09T12:00:00Z");

        NullPointerException nReason = assertThrows(NullPointerException.class, () ->
                new DropEvent(null, ts, SAMPLE_PACKET, ""));
        assertEquals("reason", nReason.getMessage());

        NullPointerException nTime = assertThrows(NullPointerException.class, () ->
                new DropEvent(DropEvent.Reason.FILTERED, null, SAMPLE_PACKET, ""));
        assertEquals("timestamp", nTime.getMessage());

        NullPointerException nPacket = assertThrows(NullPointerException.class, () ->
                new DropEvent(DropEvent.Reason.FILTERED, ts, null, ""));
        assertEquals("packet", nPacket.getMessage());

        NullPointerException nMsg = assertThrows(NullPointerException.class, () ->
                new DropEvent(DropEvent.Reason.FILTERED, ts, SAMPLE_PACKET, null));
        assertEquals("message", nMsg.getMessage());
    }

    /**
     * The {@link DropEvent.Reason} enum carries the three drop sites the
     * v1.2.0 dispatch wiring fires on, and exactly those three. Catches an
     * accidental rename or removal as a test break rather than a release-
     * note discrepancy.
     */
    @Test
    void reasonEnumValuesCoverInitialDropSites() {
        DropEvent.Reason[] values = DropEvent.Reason.values();
        assertEquals(3, values.length, "Reason must carry exactly three values in 1.2.0");
        assertEquals(DropEvent.Reason.SUBSCRIBER_LAG, DropEvent.Reason.valueOf("SUBSCRIBER_LAG"));
        assertEquals(DropEvent.Reason.QUEUE_FULL, DropEvent.Reason.valueOf("QUEUE_FULL"));
        assertEquals(DropEvent.Reason.FILTERED, DropEvent.Reason.valueOf("FILTERED"));
    }
}
