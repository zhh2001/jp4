package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.types.ElectionId;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse;
import p4.v1.P4RuntimeOuterClass.Uint128;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inbound StreamChannel observer. Runs on gRPC's executor; does no user work and
 * never blocks. Lives on the Netty side of jp4's threading-model contract: parse the
 * message, update the {@link P4Switch}'s atomic state, dispatch to the listener
 * via the callback executor, and return.
 *
 * <p>Each handler is bound to one {@link StreamSession} via its {@code generation}
 * tag. After a reconnect, the swap installs a new handler with a higher generation;
 * any late {@code onError} or stale {@code onNext} from the old handler is
 * suppressed by checking {@link P4Switch#currentGeneration()} on the side-effect
 * paths.
 */
final class InboundStreamHandler implements StreamObserver<StreamMessageResponse> {

    private final ElectionId myElectionId;
    private final long generation;
    private final CompletableFuture<MastershipStatus> firstArbitration = new CompletableFuture<>();
    private final AtomicBoolean reportedBroken = new AtomicBoolean(false);
    private volatile P4Switch sw;

    InboundStreamHandler(ElectionId myElectionId, long generation) {
        this.myElectionId = Objects.requireNonNull(myElectionId, "myElectionId");
        this.generation = generation;
    }

    /** First arbitration response future. Used by Connector.open() and by the
     *  reconnect orchestration to await the initial arbitration outcome. */
    CompletableFuture<MastershipStatus> firstResponse() {
        return firstArbitration;
    }

    long generation() {
        return generation;
    }

    /** Wires the handler to its switch once construction succeeds. */
    void attach(P4Switch sw) {
        this.sw = sw;
    }

    @Override
    public void onNext(StreamMessageResponse msg) {
        if (msg.hasArbitration()) {
            P4Switch local = sw;
            // Generation guard: if a newer session has taken over, this handler is stale.
            if (local != null && local.currentGeneration() != generation) {
                return;
            }
            MastershipStatus previous = local == null ? null : local.mastership();
            MastershipStatus newStatus = parseArbitration(msg.getArbitration(), myElectionId, previous);

            // 1. Complete the first-response future (no-op after the first call).
            firstArbitration.complete(newStatus);

            // 2. Drive switch state and dispatch to the listener (only after attach).
            if (local != null) {
                local.updateMastership(newStatus);
                local.completeReArbitration(newStatus);
                local.dispatchMastership(newStatus);
            }
        }
        // PacketIn / Digest / IdleTimeout messages: ignored in v0.1 (Phase 7+ work).
    }

    @Override
    public void onError(Throwable t) {
        if (!reportedBroken.compareAndSet(false, true)) return;
        P4ConnectionException p4 = new P4ConnectionException("stream error from server", t);
        firstArbitration.completeExceptionally(p4);
        P4Switch local = sw;
        if (local != null && local.currentGeneration() == generation) {
            local.onStreamBroken(generation, p4);
        }
    }

    @Override
    public void onCompleted() {
        if (!reportedBroken.compareAndSet(false, true)) return;
        P4ConnectionException p4 = new P4ConnectionException("server closed StreamChannel");
        firstArbitration.completeExceptionally(p4);
        P4Switch local = sw;
        if (local != null && local.currentGeneration() == generation) {
            local.onStreamBroken(generation, p4);
        }
    }

    /**
     * Maps a {@link MasterArbitrationUpdate} response to a {@link MastershipStatus}.
     * Per P4Runtime: {@code status.code == 0} means the receiver is currently primary;
     * any other code means not primary, and the included {@code election_id} is the
     * actual primary's id.
     */
    private static MastershipStatus parseArbitration(MasterArbitrationUpdate resp,
                                                     ElectionId myElectionId,
                                                     MastershipStatus previous) {
        int code = resp.getStatus().getCode();
        Uint128 reportedElectionId = resp.getElectionId();
        ElectionId reportedId = new ElectionId(reportedElectionId.getHigh(), reportedElectionId.getLow());
        if (code == 0) {
            return new MastershipStatus.Acquired(myElectionId);
        }
        ElectionId previousPrimary = (previous instanceof MastershipStatus.Acquired a)
                ? a.ourElectionId()
                : null;
        return new MastershipStatus.Lost(previousPrimary, reportedId);
    }
}
