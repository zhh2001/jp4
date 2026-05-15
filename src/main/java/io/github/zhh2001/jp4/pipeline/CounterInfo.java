package io.github.zhh2001.jp4.pipeline;

import java.util.Objects;

/**
 * Read-only metadata for one P4 counter array, derived from P4Info.
 * Construction is internal to {@link P4Info#fromBytes(byte[])} and friends;
 * users obtain instances through {@link P4Info#counter(String)}.
 *
 * <p>Instances are constructed once during P4Info parsing and are immutable
 * thereafter; safe to share across threads.
 *
 * @since 1.4.0
 */
public final class CounterInfo {

    private final String name;
    private final int id;
    private final Unit unit;
    private final long size;

    CounterInfo(String name, int id, Unit unit, long size) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.size = size;
    }

    /** Fully-qualified counter name, e.g. {@code "MyIngress.pkt_counter"}. */
    public String name() {
        return name;
    }

    /** P4Runtime numeric id assigned by p4c. */
    public int id() {
        return id;
    }

    /** What this counter counts ({@code BYTES}, {@code PACKETS}, or {@code BOTH}). */
    public Unit unit() {
        return unit;
    }

    /** Number of cells in the counter array; the cell at {@code Index} is the
     *  user-addressable target on a Read or Write of {@code CounterEntry}. */
    public long size() {
        return size;
    }

    @Override
    public String toString() {
        return "CounterInfo(name=" + name + ", id=" + id
                + ", unit=" + unit + ", size=" + size + ")";
    }

    /**
     * What a counter cell tracks. The {@code UNSPECIFIED} value covers the
     * proto default-unset case as well as targets that report a counter
     * without committing to a unit.
     *
     * @since 1.4.0
     */
    public enum Unit {
        UNSPECIFIED,
        BYTES,
        PACKETS,
        BOTH
    }
}
