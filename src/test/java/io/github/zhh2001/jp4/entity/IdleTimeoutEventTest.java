package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdleTimeoutEventTest {

    private static final TableEntry SAMPLE_ENTRY = TableEntry.in("MyIngress.ipv4_lpm")
            .match("hdr.ipv4.dstAddr",
                    new Match.Lpm(Bytes.of(new byte[]{10, 0, 0, 0}), 8))
            .build();

    /**
     * The canonical constructor rejects null in both reference components with
     * a {@link NullPointerException} whose message is the rejected component
     * name, matching the project-wide null-rejection convention.
     */
    @Test
    void canonicalConstructorRejectsNullComponents() {
        Instant ts = Instant.parse("2026-05-11T12:00:00Z");

        NullPointerException nEntries = assertThrows(NullPointerException.class, () ->
                new IdleTimeoutEvent(null, ts));
        assertEquals("entries", nEntries.getMessage());

        NullPointerException nTime = assertThrows(NullPointerException.class, () ->
                new IdleTimeoutEvent(List.of(SAMPLE_ENTRY), null));
        assertEquals("timestamp", nTime.getMessage());
    }

    /**
     * The canonical constructor copies the {@code entries} list through
     * {@link List#copyOf(java.util.Collection)}, so post-construction mutation
     * of the caller's list does not affect the event and the exposed view
     * itself refuses mutation. Identity inside the list is preserved — the
     * copied view references the same {@link TableEntry} instances the caller
     * supplied, since {@code TableEntry} uses identity equality.
     */
    @Test
    void entriesListIsCopiedAndImmutable() {
        List<TableEntry> mutable = new ArrayList<>();
        mutable.add(SAMPLE_ENTRY);
        Instant ts = Instant.parse("2026-05-11T12:00:00Z");
        IdleTimeoutEvent event = new IdleTimeoutEvent(mutable, ts);

        mutable.clear();
        assertEquals(1, event.entries().size());
        assertSame(SAMPLE_ENTRY, event.entries().get(0));
        assertNotSame(mutable, event.entries());

        assertThrows(UnsupportedOperationException.class, () ->
                event.entries().add(SAMPLE_ENTRY));
    }
}
