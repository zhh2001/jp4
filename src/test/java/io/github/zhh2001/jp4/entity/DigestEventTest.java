package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigestEventTest {

    private static final List<Bytes> SAMPLE_DATA = List.of(Bytes.of(new byte[]{1, 2, 3}));
    private static final String SAMPLE_NAME = "MyIngress.learn_digest";

    /**
     * Records auto-generate component-wise {@code equals} and {@code hashCode};
     * verify that the auto-generated implementations behave as deep equality
     * across the full component set so the type is safe to use as a map key
     * or in equality-based test assertions.
     */
    @Test
    void recordEqualityAndHashCodeAreDeep() {
        Instant ts = Instant.parse("2026-05-11T12:00:00Z");
        DigestEvent a = new DigestEvent(SAMPLE_NAME, 42L, SAMPLE_DATA, ts, 0x01000001);
        DigestEvent b = new DigestEvent(SAMPLE_NAME, 42L, SAMPLE_DATA, ts, 0x01000001);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        DigestEvent differentListId = new DigestEvent(SAMPLE_NAME, 43L, SAMPLE_DATA, ts, 0x01000001);
        assertNotEquals(a, differentListId);
    }

    /**
     * The canonical constructor rejects null in every reference component with
     * a {@link NullPointerException} whose message is the rejected component
     * name, matching the project-wide null-rejection convention documented
     * in each {@code package-info.java}. Primitive components ({@code listId}
     * and {@code rawDigestId}) carry no null surface.
     */
    @Test
    void canonicalConstructorRejectsNullComponents() {
        Instant ts = Instant.parse("2026-05-11T12:00:00Z");

        NullPointerException nName = assertThrows(NullPointerException.class, () ->
                new DigestEvent(null, 1L, SAMPLE_DATA, ts, 1));
        assertEquals("digestName", nName.getMessage());

        NullPointerException nData = assertThrows(NullPointerException.class, () ->
                new DigestEvent(SAMPLE_NAME, 1L, null, ts, 1));
        assertEquals("data", nData.getMessage());

        NullPointerException nTime = assertThrows(NullPointerException.class, () ->
                new DigestEvent(SAMPLE_NAME, 1L, SAMPLE_DATA, null, 1));
        assertEquals("timestamp", nTime.getMessage());
    }

    /**
     * The canonical constructor copies the {@code data} list through
     * {@link List#copyOf(java.util.Collection)}, so post-construction mutation
     * of the caller's list does not affect the event and the exposed view
     * itself refuses mutation. This is the same shape callers expect from
     * {@link PacketIn#metadata()}.
     */
    @Test
    void dataListIsCopiedAndImmutable() {
        List<Bytes> mutable = new ArrayList<>();
        mutable.add(Bytes.of(new byte[]{0x10}));
        Instant ts = Instant.parse("2026-05-11T12:00:00Z");
        DigestEvent event = new DigestEvent(SAMPLE_NAME, 1L, mutable, ts, 1);

        mutable.add(Bytes.of(new byte[]{0x20}));
        assertEquals(1, event.data().size());
        assertNotSame(mutable, event.data());

        assertThrows(UnsupportedOperationException.class, () ->
                event.data().add(Bytes.of(new byte[]{0x30})));
    }
}
