package io.github.zhh2001.jp4.entity;

import java.util.Objects;

/**
 * Per-color cumulative packet and byte counters for one P4 meter cell.
 * The three slots correspond to the green / yellow / red traffic-color
 * marking defined by RFC 2697 (srTCM) and RFC 2698 (trTCM): a packet
 * conforming to both the CIR and the CBurst is marked green, a packet
 * exceeding the CIR but conforming to the PIR (trTCM) or the EBS (srTCM)
 * is marked yellow, and a packet exceeding the PIR or the EBS is marked
 * red.
 *
 * <p>The {@code MeterCounterData} message was added in P4Runtime
 * v1.4.0; devices on older spec versions may return zero counters across
 * the board. Each color slot is non-null — the record stores zero
 * counters explicitly rather than {@code null}.
 *
 * @param green  cumulative counter for green-marked packets
 * @param yellow cumulative counter for yellow-marked packets
 * @param red    cumulative counter for red-marked packets
 * @since 1.4.0
 */
public record MeterCounterData(CounterData green, CounterData yellow, CounterData red) {

    public MeterCounterData {
        Objects.requireNonNull(green, "green");
        Objects.requireNonNull(yellow, "yellow");
        Objects.requireNonNull(red, "red");
    }
}
