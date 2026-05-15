package io.github.zhh2001.jp4.pipeline;

import java.util.Objects;

/**
 * Read-only metadata for one P4 meter array, derived from P4Info.
 * Construction is internal to {@link P4Info#fromBytes(byte[])} and friends;
 * users obtain instances through {@link P4Info#meter(String)}.
 *
 * <p>Instances are constructed once during P4Info parsing and are immutable
 * thereafter; safe to share across threads.
 *
 * @since 1.4.0
 */
public final class MeterInfo {

    private final String name;
    private final int id;
    private final Unit unit;
    private final long size;

    MeterInfo(String name, int id, Unit unit, long size) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.size = size;
    }

    /** Fully-qualified meter name, e.g. {@code "MyIngress.rate_meter"}. */
    public String name() {
        return name;
    }

    /** P4Runtime numeric id assigned by p4c. */
    public int id() {
        return id;
    }

    /** Whether the meter measures {@code BYTES}, {@code PACKETS}, or {@code BOTH}. */
    public Unit unit() {
        return unit;
    }

    /** Number of cells in the meter array. */
    public long size() {
        return size;
    }

    @Override
    public String toString() {
        return "MeterInfo(name=" + name + ", id=" + id
                + ", unit=" + unit + ", size=" + size + ")";
    }

    /**
     * What a meter cell measures. {@code UNSPECIFIED} covers the proto
     * default-unset case as well as targets that report a meter without
     * committing to a unit. Unlike {@link CounterInfo.Unit} there is no
     * {@code BOTH}: the P4Runtime meter spec defines only
     * bytes-or-packets meters, not combined.
     *
     * @since 1.4.0
     */
    public enum Unit {
        UNSPECIFIED,
        BYTES,
        PACKETS
    }
}
