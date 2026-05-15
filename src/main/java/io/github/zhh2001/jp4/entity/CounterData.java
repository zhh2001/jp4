package io.github.zhh2001.jp4.entity;

/**
 * A pair of cumulative {@code packet_count} and {@code byte_count}
 * values, mirroring the P4Runtime {@code CounterData} proto. Used as the
 * nested per-color counter inside {@link MeterCounterData}.
 *
 * <p>Note that {@code CounterEntry} (returned by
 * {@code P4Switch.readCounter}) does <em>not</em> use this record — that
 * entity carries {@code packetCount} and {@code byteCount} as flat
 * fields, reflecting that a counter cell has a single counter datum
 * rather than three colored ones. This record is the nested helper for
 * meter responses, where three counter data are bundled per cell.
 *
 * @param packetCount cumulative packet count
 * @param byteCount   cumulative byte count
 * @since 1.4.0
 */
public record CounterData(long packetCount, long byteCount) {
}
