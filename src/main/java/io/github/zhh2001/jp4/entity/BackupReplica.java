package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.util.Objects;

/**
 * A backup port for a {@link Replica} slot, used as a fallback when the
 * primary replica's port and all higher-preference backup ports are
 * down. The list of backup replicas in {@link Replica#backupReplicas}
 * is ordered by preference: the device falls through to the next entry
 * when the current entry's port is unreachable.
 *
 * <p>The {@code BackupReplica} message was added in P4Runtime 1.5.0;
 * devices on older spec versions do not produce or consume it, and
 * jp4 reads of older devices will return entries with empty
 * {@code backupReplicas} lists.
 *
 * @param port     the device-defined backup port identifier; never
 *                 {@code null} but may be empty bytes
 * @param instance the per-clone instance id the backup replica
 *                 produces in the egress; matches the primary
 *                 replica's instance convention
 * @since 1.5.0
 */
public record BackupReplica(Bytes port, int instance) {

    public BackupReplica {
        Objects.requireNonNull(port, "port");
    }
}
