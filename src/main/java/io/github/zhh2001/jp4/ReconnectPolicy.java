package io.github.zhh2001.jp4;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Strategy for re-establishing the gRPC stream after a transport failure. Consulted by
 * the auto-reconnect loop after a {@code P4ConnectionException} from gRPC; an empty
 * return ends the retry sequence and surfaces the failure to the caller.
 *
 * @since 0.1.0
 */
public interface ReconnectPolicy {

    /**
     * @param attempt the upcoming attempt number, 1-indexed (1 = first retry after the
     *                initial failure).
     * @return delay in milliseconds before attempting reconnect, or empty to give up.
     */
    OptionalLong nextDelayMillis(int attempt);

    /** No reconnect attempts: the first failure is propagated. */
    static ReconnectPolicy noRetry() {
        return attempt -> OptionalLong.empty();
    }

    /**
     * Exponential backoff: {@code initial}, {@code 2*initial}, {@code 4*initial}, ...,
     * capped at {@code max}, for at most {@code maxRetries} attempts.
     */
    static ReconnectPolicy exponentialBackoff(Duration initial, Duration max, int maxRetries) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial must be positive, got " + initial);
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max (" + max + ") must be >= initial (" + initial + ")");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        long initialMs = initial.toMillis();
        long maxMs = max.toMillis();
        return attempt -> {
            if (attempt < 1 || attempt > maxRetries) return OptionalLong.empty();
            int shift = Math.min(attempt - 1, 30);  // cap shift to avoid overflow
            long delay = Math.min(initialMs << shift, maxMs);
            return OptionalLong.of(delay);
        };
    }
}
