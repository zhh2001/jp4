package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.types.ElectionId;

import java.util.Objects;

/**
 * Snapshot of mastership state delivered to {@code onMastershipChange} listeners.
 * Sealed; pattern-match on the variant for compile-time exhaustive handling.
 *
 * <p>Both variants override {@code toString()} with a compact, grep-friendly form
 * intended for log lines:
 *
 * <pre>
 *   Acquired(primary=10)
 *   Lost(prev=null, primary=10)
 *   Lost(prev=5, primary=10)
 * </pre>
 *
 * The nested election id is rendered as just its numeric value rather than the
 * {@code ElectionId(N)} wrap form that {@link ElectionId#toString()} produces, so
 * a grep on {@code "primary=10"} catches both states symmetrically.
 *
 * <p>Both variants are immutable records, safe to share across threads.
 *
 * @since 0.1.0
 */
public sealed interface MastershipStatus permits MastershipStatus.Acquired, MastershipStatus.Lost {

    boolean isLost();

    /**
     * Renders an election id for inclusion in a {@link MastershipStatus} {@code toString()}:
     * just the numeric value (unsigned), or the literal {@code "null"} when {@code id} is null.
     */
    private static String renderElectionId(ElectionId id) {
        if (id == null) {
            return "null";
        }
        if (id.high() == 0L) {
            return Long.toUnsignedString(id.low());
        }
        return id.toBigInteger().toString();
    }

    /**
     * This client is currently primary; {@link #ourElectionId()} won the arbitration.
     * The election id is never null on an {@code Acquired} event.
     */
    record Acquired(ElectionId ourElectionId) implements MastershipStatus {
        public Acquired {
            Objects.requireNonNull(ourElectionId, "ourElectionId");
        }

        @Override
        public boolean isLost() {
            return false;
        }

        @Override
        public String toString() {
            return "Acquired(primary=" + renderElectionId(ourElectionId) + ")";
        }
    }

    /**
     * This client is no longer primary. {@link #previousElectionId()} is the
     * election id this client previously held, or {@code null} when this client
     * never held primary (e.g., a secondary observing the existing primary on
     * first connect). {@link #currentPrimaryElectionId()} is the new primary's
     * id, or {@code null} if the device did not report it (some implementations
     * don't).
     */
    record Lost(ElectionId previousElectionId, ElectionId currentPrimaryElectionId) implements MastershipStatus {
        @Override
        public boolean isLost() {
            return true;
        }

        @Override
        public String toString() {
            return "Lost(prev=" + renderElectionId(previousElectionId)
                    + ", primary=" + renderElectionId(currentPrimaryElectionId) + ")";
        }
    }
}
