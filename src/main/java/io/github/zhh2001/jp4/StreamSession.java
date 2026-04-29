package io.github.zhh2001.jp4;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;

/**
 * One {@code (channel, inbound handler, outbound observer)} triple representing a
 * single live StreamChannel. Reconnect creates a new {@code StreamSession} and the
 * containing {@link P4Switch} swaps its {@code session} field atomically; old
 * sessions are GC'd once references drop.
 *
 * <p>The {@link #generation} is monotonically increasing per switch, used by stale
 * inbound handlers to detect "I belong to a session that is no longer current" and
 * skip side effects (avoiding duplicate reconnects).
 */
final class StreamSession {

    final long generation;
    final ManagedChannel channel;
    final InboundStreamHandler handler;
    final StreamObserver<StreamMessageRequest> reqObserver;

    StreamSession(long generation,
                  ManagedChannel channel,
                  InboundStreamHandler handler,
                  StreamObserver<StreamMessageRequest> reqObserver) {
        this.generation = generation;
        this.channel = channel;
        this.handler = handler;
        this.reqObserver = reqObserver;
    }

    /**
     * Best-effort cleanup of this session's resources. Used when a reconnect attempt
     * succeeds and the previous session is being retired, or when the polling-cancel
     * path needs to discard a half-built replacement.
     *
     * <p>Uses {@code channel.shutdown()} (graceful) — the device sees a normal
     * client-side termination and cleans up its end without surprises.
     */
    void shutdownGracefully() {
        try {
            channel.shutdown();
            channel.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            channel.shutdownNow();
        }
    }
}
