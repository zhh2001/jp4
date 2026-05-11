package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One {@code DigestList} stream-message received from a P4Runtime device.
 * The device fires this when a digest extern instance has gathered the
 * configured number of entries or its outstanding-timeout elapsed; jp4
 * surfaces the resulting list as one immutable record per notification.
 *
 * <p>Each element of {@link #data} is the serialised bytes of one
 * {@code p4.v1.P4Data} message — one call to {@code Digest<T>::pack()}
 * on the device side. Typed decoding is not built into this record on
 * purpose: the {@code P4DataTypeSpec} that drives decoding lives in the
 * P4Info digest definition, and surfacing a typed payload would require
 * a P4Info-aware decoder that is held for a future v1.x release.
 * Consumers that want the typed form call
 * {@code p4.v1.P4DataOuterClass.P4Data.parseFrom(b.toByteArray())} on
 * each element. The entity package itself stays free of any P4Runtime
 * protobuf class import, matching the convention shared with
 * {@link PacketIn} and {@link DropEvent}.
 *
 * <p>The record carries no {@code ack} method. The
 * {@code P4Switch.onDigest} dispatch layer auto-acknowledges every
 * delivered {@code DigestList} via the P4Runtime {@code DigestListAck}
 * message; without that, the device's spec-defined
 * {@code ack_timeout_ns} window suppresses further digests for the same
 * data and the application loses observability silently. A controller
 * that wants explicit ack control is a v1.x topic, intentionally
 * deferred so that the v1.3 surface stays minimal.
 *
 * <p>Records are immutable. The canonical constructor rejects null in
 * every reference component, and {@link #data} is copied through
 * {@link List#copyOf(java.util.Collection)} so post-construction
 * mutations of the caller's list do not affect the event and the
 * exposed view itself refuses mutation.
 *
 * @param digestName the digest extern's fully-qualified P4Info name
 *                   (resolved from {@link #rawDigestId} during dispatch);
 *                   never {@code null}
 * @param listId     the {@code list_id} field the device uses to
 *                   correlate {@code DigestListAck} responses; exposed
 *                   for diagnostics and logging
 * @param data       immutable list of serialised {@code p4.v1.P4Data}
 *                   entries, one per {@code Digest<T>::pack()}
 *                   invocation on the device; never {@code null}, may
 *                   be empty
 * @param timestamp  wall-clock instant the device generated the
 *                   {@code DigestList} (converted from the spec's
 *                   {@code timestamp} field in nanoseconds since Epoch);
 *                   never {@code null}
 * @param rawDigestId the numeric {@code digest_id} field from the wire
 *                    message; kept for diagnostics and for listener
 *                    routing without an extra P4Info lookup
 * @since 1.3.0
 */
public record DigestEvent(
        String digestName,
        long listId,
        List<Bytes> data,
        Instant timestamp,
        int rawDigestId
) {

    public DigestEvent {
        Objects.requireNonNull(digestName, "digestName");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(timestamp, "timestamp");
        data = List.copyOf(data);
    }
}
