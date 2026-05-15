package io.github.zhh2001.jp4.entity;

import java.util.Objects;

/**
 * One P4 counter cell returned by {@code P4Switch.readCounter}. The record
 * carries the counter's fully-qualified P4 name, the cell index within the
 * counter array, and the two cumulative values BMv2 reports back:
 * {@link #packetCount} and {@link #byteCount}.
 *
 * <p>Which of the two counts is meaningful depends on the counter's unit,
 * available through {@code P4Info.counter(counterName).unit()}: a
 * {@code BYTES} counter reports byte_count and leaves packet_count at
 * {@code 0}; a {@code PACKETS} counter does the opposite; a {@code BOTH}
 * counter populates both. The record stores both fields uniformly as
 * primitive {@code long} to keep the wire mapping unambiguous; consumers
 * interpret the appropriate field for their counter's unit.
 *
 * <p>Records are immutable. The canonical constructor rejects null in the
 * reference component; the {@code long} components carry no null surface.
 *
 * @param counterName the counter's fully-qualified P4 name (resolved from
 *                    the wire {@code counter_id} during read response
 *                    parsing); never {@code null}
 * @param index       the cell index within the counter array, as
 *                    returned by the device
 * @param packetCount cumulative packet count for the cell; meaningful for
 *                    {@code PACKETS} and {@code BOTH} unit counters
 * @param byteCount   cumulative byte count for the cell; meaningful for
 *                    {@code BYTES} and {@code BOTH} unit counters
 * @since 1.4.0
 */
public record CounterEntry(
        String counterName,
        long index,
        long packetCount,
        long byteCount
) {

    public CounterEntry {
        Objects.requireNonNull(counterName, "counterName");
    }
}
