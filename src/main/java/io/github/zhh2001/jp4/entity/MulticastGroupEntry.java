package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.util.List;
import java.util.Objects;

/**
 * One multicast group programmed on the device's packet replication
 * engine (PRE), returned by {@code P4Switch.readMulticastGroup}. A
 * multicast group is identified by a controller-assigned numeric id;
 * unlike table-driven entities (table, counter, meter, register,
 * action profile) it has no P4-program name. The group's effect is to
 * fan out a copy of any packet whose egress metadata names this group
 * id to every {@link Replica} in the {@link #replicas} list.
 *
 * <p>{@link #replicas} is captured through {@link List#copyOf} so
 * post-construction mutations of the caller's list do not affect the
 * record and the exposed view itself refuses mutation.
 *
 * <p>{@link #metadata} is opaque controller-defined bytes that the
 * target stores unchanged and returns on read; jp4 does not interpret
 * the payload. The {@code metadata} field was added in P4Runtime
 * 1.4.0; devices on older spec versions return empty bytes.
 *
 * @param multicastGroupId controller-assigned numeric group id; the
 *                         primary key for this entry
 * @param replicas         ordered list of per-port fan-out slots;
 *                         never {@code null}, may be empty
 * @param metadata         opaque controller-defined bytes; never
 *                         {@code null}, may be empty
 * @since 1.5.0
 */
public record MulticastGroupEntry(
        long multicastGroupId,
        List<Replica> replicas,
        Bytes metadata
) {

    public MulticastGroupEntry {
        Objects.requireNonNull(replicas, "replicas");
        Objects.requireNonNull(metadata, "metadata");
        replicas = List.copyOf(replicas);
    }
}
