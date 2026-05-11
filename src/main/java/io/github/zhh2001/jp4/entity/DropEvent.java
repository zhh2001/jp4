package io.github.zhh2001.jp4.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * One backpressure-class drop on the inbound PacketIn path. Surfaces a packet
 * that was parsed by jp4 but then dropped before it reached user code,
 * together with the reason for the drop and a wall-clock timestamp.
 *
 * <p>Delivered to the listener registered via
 * {@code P4Switch.onPacketDropped(Consumer<DropEvent>)}. The listener runs on
 * the same single-threaded callback executor as
 * {@code P4Switch.onPacketIn} / {@code P4Switch.onMastershipChange}, so
 * handlers that block hold up subsequent dispatch but do not affect the gRPC
 * inbound thread.
 *
 * <p>Two adjacent dispatch-site drops in {@code P4Switch} — no-pipeline-bound
 * and parse-failure — are deliberately not reported as {@code DropEvent}s
 * because they have no parsed {@link PacketIn} to deliver. They remain
 * WARN-only logs; a separate listener with an {@code Optional<PacketIn>}
 * shape can be introduced in a future v1.x release without disturbing this
 * surface.
 *
 * <p>Records are immutable; all components are validated non-null at
 * construction.
 *
 * @param reason the dispatch site that fired this drop; never {@code null}
 * @param timestamp the wall-clock instant at which jp4 detected the drop;
 *                  never {@code null}
 * @param packet the parsed {@link PacketIn} that was dropped; never
 *               {@code null}
 * @param message a free-form human-readable description of the drop, useful
 *                for log correlation without machine-parsing
 *                {@link Reason}; never {@code null}, but may be empty
 *                ({@code ""}) when no extra detail applies
 * @since 1.2.0
 */
public record DropEvent(
        Reason reason,
        Instant timestamp,
        PacketIn packet,
        String message
) {

    public DropEvent {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Why a parsed PacketIn was dropped before reaching user code. The set
     * is open to additive growth in future v1.x releases (adding a new
     * value is a SemVer-safe extension); applications that switch on this
     * enum should handle unknown values defensively.
     */
    public enum Reason {

        /**
         * A {@code Flow.Publisher} subscriber failed to consume the offered
         * PacketIn before the per-subscriber zero-timeout deadline. Fired
         * once per slow subscriber per dropped packet from the dispatch
         * site at {@code P4Switch.dispatchPacketIn} sink 2 (Publisher).
         */
        SUBSCRIBER_LAG,

        /**
         * The poll-style packet queue ({@code P4Switch.pollPacketIn}) is at
         * capacity (default 1024, configurable via
         * {@code Connector.packetInQueueSize}); the newest PacketIn is
         * dropped rather than evicting a pre-queued one. Fired from the
         * {@code P4Switch.dispatchPacketIn} sink 3 (deque).
         */
        QUEUE_FULL,

        /**
         * A {@code Connector.packetInFilter} predicate returned
         * {@code false} for this PacketIn, so jp4 dropped it before any
         * sink received it. Fired from the post-parse pre-fan-out filter
         * point in {@code P4Switch.dispatchPacketIn}.
         */
        FILTERED
    }
}
