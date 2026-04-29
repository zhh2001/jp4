package io.github.zhh2001.jp4.error;

import io.github.zhh2001.jp4.types.ElectionId;

/**
 * Thrown when {@code asPrimary()} is called but another client holds primary with a
 * higher election id, or when an in-flight write detects mastership has just been lost.
 *
 * <p>{@link #ourElectionId()} is the id we presented; {@link #currentPrimaryElectionId()}
 * is the id reported by the device as the current primary (may be {@code null} if the
 * server did not include it).
 */
public final class P4ArbitrationLost extends P4ConnectionException {

    private final ElectionId ourElectionId;
    private final ElectionId currentPrimaryElectionId;

    public P4ArbitrationLost(String message, ElectionId ours, ElectionId currentPrimary) {
        super(message);
        this.ourElectionId = ours;
        this.currentPrimaryElectionId = currentPrimary;
    }

    public ElectionId ourElectionId() {
        return ourElectionId;
    }

    public ElectionId currentPrimaryElectionId() {
        return currentPrimaryElectionId;
    }
}
