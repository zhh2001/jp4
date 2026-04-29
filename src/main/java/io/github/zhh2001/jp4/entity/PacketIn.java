package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

/**
 * One packet delivered to the controller via the StreamChannel
 * {@code PacketIn} message. Carries the raw payload and the
 * {@code controller_packet_metadata} fields declared in P4Info.
 *
 * <p>Skeleton in 4A.
 *
 * @implNote Implementation pending; payload and metadata accessors throw {@link
 *           UnsupportedOperationException} until Phase 7.
 *
 * @since 0.1.0
 */
public final class PacketIn {

    PacketIn() {
        // skeleton
    }

    public Bytes payload() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** Returns the metadata field by name, or {@code null} if absent. */
    public Bytes metadata(String name) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** Convenience accessor that interprets the metadata as an unsigned integer. */
    public int metadataInt(String name) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }
}
