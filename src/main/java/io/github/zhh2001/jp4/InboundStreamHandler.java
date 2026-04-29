package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.types.ElectionId;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse;
import p4.v1.P4RuntimeOuterClass.Uint128;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Inbound StreamChannel observer. Runs on gRPC's executor; does no user work and
 * never blocks. Lives on the Netty side of jp4's threading-model contract: parse the
 * message, update the {@link P4Switch}'s atomic state, and return.
 *
 * <p>The handler is created before the {@code P4Switch} exists (the first arbitration
 * response is what determines whether we became primary). Once the switch is built it
 * is {@link #attach attached}; subsequent inbound messages then update the switch's
 * mastership state in addition to completing the first-response future (a no-op after
 * the first message).
 */
final class InboundStreamHandler implements StreamObserver<StreamMessageResponse> {

    private final ElectionId myElectionId;
    private final CompletableFuture<MastershipStatus> firstArbitration = new CompletableFuture<>();
    private volatile P4Switch sw;

    InboundStreamHandler(ElectionId myElectionId) {
        this.myElectionId = Objects.requireNonNull(myElectionId, "myElectionId");
    }

    /** Returns the future that completes (exactly once) on the first arbitration response. */
    CompletableFuture<MastershipStatus> firstResponse() {
        return firstArbitration;
    }

    /** Wires the handler to its switch once construction succeeds. */
    void attach(P4Switch sw) {
        this.sw = sw;
    }

    @Override
    public void onNext(StreamMessageResponse msg) {
        if (msg.hasArbitration()) {
            MastershipStatus previous = sw == null ? null : sw.mastership();
            MastershipStatus newStatus = parseArbitration(msg.getArbitration(), myElectionId, previous);
            firstArbitration.complete(newStatus);   // wins only the first time
            if (sw != null) {
                sw.updateMastership(newStatus);
            }
        }
        // 4B scope: PacketIn / Digest / IdleTimeout messages are ignored.
    }

    @Override
    public void onError(Throwable t) {
        if (sw != null) {
            sw.markBroken();
        }
        firstArbitration.completeExceptionally(
                new P4ConnectionException("stream error from server", t));
    }

    @Override
    public void onCompleted() {
        if (sw != null) {
            sw.markBroken();
        }
        firstArbitration.completeExceptionally(
                new P4ConnectionException("server closed StreamChannel"));
    }

    /**
     * Maps a {@link MasterArbitrationUpdate} response to a {@link MastershipStatus}.
     * Per P4Runtime: {@code status.code == 0} means the receiver is currently primary;
     * any other code means the receiver is not primary, and the included
     * {@code election_id} reports the actual primary's id.
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
