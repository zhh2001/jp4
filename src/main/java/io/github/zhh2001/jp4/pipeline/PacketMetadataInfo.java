package io.github.zhh2001.jp4.pipeline;

/**
 * Read-only metadata for one field of a P4Runtime {@code controller_packet_metadata}
 * declaration — i.e. one field of either the {@code packet_in} or {@code packet_out}
 * controller header in P4Info. {@code packetInMetadata()} / {@code packetOutMetadata()}
 * on {@link P4Info} return lists of these.
 *
 * @param id       P4Runtime numeric id (1-based, unique within the owning
 *                 {@code controller_packet_metadata} declaration)
 * @param name     field name as written in the P4 program (e.g. {@code "ingress_port"})
 * @param bitWidth width as declared in the P4 program (e.g. 9 for an ingress port)
 *
 * @since 0.1.0
 */
public record PacketMetadataInfo(int id, String name, int bitWidth) {
}
