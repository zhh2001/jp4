package io.github.zhh2001.jp4.entity;

/**
 * Rate configuration of one P4 meter cell. Models both RFC 2697 (srTCM —
 * Single Rate Three Color Marker) and RFC 2698 (trTCM — Two Rate Three
 * Color Marker) parameters as a flat record of {@code long} values; which
 * fields are meaningful is determined by the meter's type as declared in
 * P4Info.
 *
 * <p>Field names mirror the P4Runtime {@code MeterConfig} proto exactly so
 * that consumers can map back to the RFCs without translation.
 *
 * @param cir    committed information rate (units per second)
 * @param cburst committed burst size
 * @param pir    peak information rate (units per second)
 * @param pburst peak burst size
 * @param eburst excess burst size; only used by srTCM. Added in 1.4.0 of
 *               the P4Runtime spec and surfaces here as {@code 0} for
 *               trTCM meters or for devices that do not populate it.
 * @since 1.4.0
 */
public record MeterConfig(long cir, long cburst, long pir, long pburst, long eburst) {
}
