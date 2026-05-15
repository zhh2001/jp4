package io.github.zhh2001.jp4.entity;

import java.util.Objects;

/**
 * One P4 meter cell returned by {@code P4Switch.readMeter}. The record
 * carries the meter's fully-qualified P4 name, the cell index within the
 * meter array, the rate {@link MeterConfig configuration} programmed on
 * that cell, and the per-color packet/byte counters reported back by the
 * device as a {@link MeterCounterData}.
 *
 * <p>Records are immutable. The canonical constructor rejects null in
 * every reference component; the {@code long} index has no null surface.
 *
 * @param meterName   the meter's fully-qualified P4 name (resolved from
 *                    the wire {@code meter_id} during read response
 *                    parsing); never {@code null}
 * @param index       the cell index within the meter array, as returned
 *                    by the device
 * @param config      the rate configuration (CIR / CBurst / PIR / PBurst
 *                    and v1.4 {@code EBurst}); never {@code null}
 * @param counterData the per-color cumulative counters (green / yellow /
 *                    red); never {@code null}
 * @since 1.4.0
 */
public record MeterEntry(
        String meterName,
        long index,
        MeterConfig config,
        MeterCounterData counterData
) {

    public MeterEntry {
        Objects.requireNonNull(meterName, "meterName");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(counterData, "counterData");
    }
}
