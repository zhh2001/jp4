package io.github.zhh2001.jp4.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One {@code IdleTimeoutNotification} stream-message received from a
 * P4Runtime device. The device fires this when one or more table entries
 * with a configured {@code idle_timeout_ns} have not been hit within
 * their timeout window; jp4 surfaces the resulting list as one immutable
 * record per notification.
 *
 * <p>{@link #entries} preserves the wire shape directly. A single
 * notification can carry entries from multiple tables — {@code table_id}
 * is per-entry on the wire — and the library does not regroup them.
 * Listener code that wants per-table handling can group by
 * {@link TableEntry#tableName()} on its own. Each entry is parsed
 * through the same reverse-protobuf path as the read RPC, so unknown
 * table / match-field / action / param ids surface as
 * {@code P4PipelineException} at the dispatch site rather than silently
 * skipping entries.
 *
 * <p>The current reverse-parse path rejects entries whose action came
 * back from the device as an action-profile member or selector group
 * reference; those are tracked as a separate v1.x roadmap item and a
 * notification that contains such an entry will be WARN-logged and
 * dropped wholesale at the {@code dispatchIdleTimeout} site, rather
 * than propagated as a partial event. This keeps the dispatch path
 * fail-open per-message, the same shape jp4 already uses for
 * unparseable {@code PacketIn} messages.
 *
 * <p>Records are immutable. The canonical constructor rejects null in
 * both reference components, and {@link #entries} is copied through
 * {@link List#copyOf(java.util.Collection)} so post-construction
 * mutations of the caller's list do not affect the event and the
 * exposed view itself refuses mutation. Note that {@link TableEntry}
 * uses identity equality (its {@code equals} is the default
 * reference-equality implementation), so two {@code IdleTimeoutEvent}s
 * built from independently parsed {@link TableEntry} instances compare
 * unequal even when the underlying wire entries match.
 *
 * @param entries   immutable list of the table entries the device
 *                  reported as idled out; never {@code null}, may
 *                  be empty
 * @param timestamp wall-clock instant the device generated the
 *                  notification (converted from the spec's
 *                  {@code timestamp} field in nanoseconds since Epoch);
 *                  never {@code null}
 * @since 1.3.0
 */
public record IdleTimeoutEvent(
        List<TableEntry> entries,
        Instant timestamp
) {

    public IdleTimeoutEvent {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(timestamp, "timestamp");
        entries = List.copyOf(entries);
    }
}
