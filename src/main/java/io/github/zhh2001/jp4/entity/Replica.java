package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.util.List;
import java.util.Objects;

/**
 * One replica slot in a {@link MulticastGroupEntry} or
 * {@code CloneSessionEntry}. A replica targets a single egress port
 * with an instance id; the device delivers one clone of the original
 * packet to the slot's port, carrying the instance id in egress
 * metadata so the program can distinguish replicas of the same
 * original packet.
 *
 * <p>The {@link #port} is the {@code port} bytes variant of the
 * P4Runtime {@code port_kind} oneof — a device-defined port
 * identifier, the same byte form
 * {@link io.github.zhh2001.jp4.entity.WeightedMember#watchPort} uses
 * for action-profile-group watch ports. It is {@code null} in two
 * cases that jp4 treats identically: when the {@code port_kind}
 * oneof is unset, and when the deprecated {@code egress_port} int32
 * field is set instead. Controllers that need to read the deprecated
 * path can parse the wire {@code Replica} proto directly through the
 * generated class; jp4 1.5 does not surface it on this record.
 *
 * <p>{@link #backupReplicas} is captured through {@link List#copyOf}
 * so post-construction mutations of the caller's list do not affect
 * the record and the exposed view itself refuses mutation. The
 * {@code backup_replicas} field was added in P4Runtime 1.5.0; older
 * devices return an empty list.
 *
 * @param port            device-defined port bytes, or {@code null} if
 *                        the {@code port_kind} oneof is unset or holds
 *                        the deprecated int32 variant
 * @param instance        per-clone instance id the slot emits in egress
 * @param backupReplicas  ordered fallback list (highest preference
 *                        first); never {@code null}, may be empty
 * @since 1.5.0
 */
public record Replica(Bytes port, int instance, List<BackupReplica> backupReplicas) {

    public Replica {
        Objects.requireNonNull(backupReplicas, "backupReplicas");
        backupReplicas = List.copyOf(backupReplicas);
    }
}
