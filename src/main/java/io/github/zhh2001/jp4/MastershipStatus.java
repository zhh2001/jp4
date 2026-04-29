package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.types.ElectionId;

/**
 * Snapshot of mastership state delivered to {@code onMastershipChange} listeners.
 * Sealed; pattern-match on the variant for compile-time exhaustive handling.
 *
 * @since 0.1.0
 */
public sealed interface MastershipStatus permits MastershipStatus.Acquired, MastershipStatus.Lost {

    boolean isLost();

    /** This client is currently primary; {@link #ourElectionId()} won the arbitration. */
    record Acquired(ElectionId ourElectionId) implements MastershipStatus {
        @Override
        public boolean isLost() {
            return false;
        }
    }

    /**
     * This client is no longer primary. {@link #previousElectionId()} is what we held;
     * {@link #currentPrimaryElectionId()} is the new primary's id, or {@code null} if
     * the device did not report it (some implementations don't).
     */
    record Lost(ElectionId previousElectionId, ElectionId currentPrimaryElectionId) implements MastershipStatus {
        @Override
        public boolean isLost() {
            return true;
        }
    }
}
