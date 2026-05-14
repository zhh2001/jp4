package io.github.zhh2001.jp4.entity;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration knobs the controller hands to the device when enabling
 * a digest extern via {@code P4Switch.enableDigest}. Mirrors the
 * P4Runtime {@code DigestEntry.Config} wire shape, holding the three
 * fields the device uses to throttle and batch digest notifications:
 *
 * <ul>
 *   <li>{@link #maxTimeout()} — the outstanding-data deadline; once
 *       the oldest unbatched digest entry has been waiting this long,
 *       the device flushes whatever it has accumulated as one
 *       {@code DigestList} message regardless of size.</li>
 *   <li>{@link #maxListSize()} — the size threshold; once the device
 *       has gathered this many entries for a single digest extern, it
 *       flushes them as one {@code DigestList} regardless of the
 *       timeout. Must be strictly positive.</li>
 *   <li>{@link #ackTimeout()} — how long the device waits for the
 *       controller's {@code DigestListAck} before retrying. While the
 *       controller has not acknowledged a list, the device suppresses
 *       any further digests for the same data; {@code jp4} auto-acks
 *       every dispatched list so this value mainly constrains the
 *       worst-case observability window after a controller restart.</li>
 * </ul>
 *
 * <p>All three components are required. Both {@code Duration} fields
 * reject null and negative values; {@code maxListSize} must be strictly
 * positive (zero would mean "never flush by size" but {@code 0} is also
 * the protobuf default-unset value, so requiring a positive integer
 * keeps the wire encoding unambiguous). The canonical constructor
 * surfaces violations as {@link NullPointerException} or
 * {@link IllegalArgumentException} naming the offending component.
 *
 * @param maxTimeout  outstanding-data deadline; never {@code null} and
 *                    must be non-negative
 * @param maxListSize size threshold; must be strictly positive
 * @param ackTimeout  ack-wait deadline; never {@code null} and must be
 *                    non-negative
 * @since 1.3.0
 */
public record DigestConfig(
        Duration maxTimeout,
        int maxListSize,
        Duration ackTimeout
) {

    public DigestConfig {
        Objects.requireNonNull(maxTimeout, "maxTimeout");
        Objects.requireNonNull(ackTimeout, "ackTimeout");
        if (maxTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "maxTimeout must be >= 0, got " + maxTimeout);
        }
        if (ackTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "ackTimeout must be >= 0, got " + ackTimeout);
        }
        if (maxListSize <= 0) {
            throw new IllegalArgumentException(
                    "maxListSize must be > 0, got " + maxListSize);
        }
    }
}
