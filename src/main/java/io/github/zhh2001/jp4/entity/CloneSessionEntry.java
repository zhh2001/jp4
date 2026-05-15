package io.github.zhh2001.jp4.entity;

import java.util.List;
import java.util.Objects;

/**
 * One clone session programmed on the device's packet replication
 * engine (PRE), returned by {@code P4Switch.readCloneSession}. A clone
 * session is identified by a controller-assigned numeric id; like
 * {@link MulticastGroupEntry} it has no P4-program name. The session
 * fans out a copy of any packet whose egress metadata names this
 * session id to every {@link Replica} in the {@link #replicas} list,
 * tagging each copy with the session's
 * {@link #classOfService class of service} and optionally truncating
 * the cloned payload to {@link #packetLengthBytes} bytes.
 *
 * <p>{@link #replicas} is captured through {@link List#copyOf} so
 * post-construction mutations of the caller's list do not affect the
 * record and the exposed view itself refuses mutation.
 *
 * <p>{@link #packetLengthBytes} carries the truncation length: a value
 * of {@code 0} means "do not truncate" — every clone carries the
 * original payload in full. A positive value means "truncate to this
 * many bytes" — the device drops any trailing payload beyond the
 * limit. Negative values are invalid by spec but tolerated on the
 * read path: the device returns whatever {@code packet_length_bytes}
 * it has stored unchanged.
 *
 * @param sessionId         controller-assigned numeric session id; the
 *                          primary key for this entry
 * @param replicas          ordered list of per-port fan-out slots;
 *                          never {@code null}, may be empty
 * @param classOfService    the class-of-service value the device
 *                          stamps on every clone produced by this
 *                          session; widened from the proto's
 *                          {@code uint32} to a Java {@code long} to
 *                          preserve the full unsigned range
 * @param packetLengthBytes truncation length in bytes; {@code 0}
 *                          means no truncation
 * @since 1.5.0
 */
public record CloneSessionEntry(
        long sessionId,
        List<Replica> replicas,
        long classOfService,
        int packetLengthBytes
) {

    public CloneSessionEntry {
        Objects.requireNonNull(replicas, "replicas");
        replicas = List.copyOf(replicas);
    }
}
