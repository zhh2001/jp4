package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.PacketMetadataInfo;
import io.github.zhh2001.jp4.types.Bytes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Package-private. Wire codec for the StreamChannel {@code PacketIn} and
 * {@code PacketOut} messages — symmetric companion of {@link EntryProto}.
 *
 * <p>{@link #parseIn} maps {@code p4.v1.PacketIn} → {@link PacketIn}, resolving
 * each {@code metadata.metadata_id} back to its declared name through the
 * {@code packet_in} reverse-id index in {@link P4Info}. Unknown ids surface as
 * {@link P4PipelineException} (fail-fast on P4Info ↔ device drift, same line as
 * {@code EntryProto.fromProto}).
 *
 * <p>{@link #serialize} maps {@link PacketOut} → {@code p4.v1.PacketOut},
 * resolving each metadata-field name to its id through the {@code packet_out}
 * forward index, canonicalising the payload AS-IS (the spec defines the payload
 * as the raw packet bytes, no canonicalisation), and canonicalising metadata
 * values via {@link Bytes#canonical()} at the wire boundary like
 * {@link EntryProto}. Unknown field names surface with the same known-list error
 * style used elsewhere in jp4.
 */
final class PacketProto {

    private PacketProto() { }

    static PacketIn parseIn(p4.v1.P4RuntimeOuterClass.PacketIn proto, P4Info p4info) {
        Map<String, Bytes> metadata = new LinkedHashMap<>(proto.getMetadataCount() * 2);
        for (p4.v1.P4RuntimeOuterClass.PacketMetadata md : proto.getMetadataList()) {
            PacketMetadataInfo field = p4info.packetInFieldById(md.getMetadataId());
            if (field == null) {
                throw new P4PipelineException(
                        "device returned PacketIn metadata id " + md.getMetadataId()
                                + " which is not in the bound P4Info packet_in header "
                                + "(known: " + p4info.packetInMetadata() + ")");
            }
            metadata.put(field.name(), Bytes.of(md.getValue().toByteArray()));
        }
        return new PacketIn(Bytes.of(proto.getPayload().toByteArray()), metadata);
    }

    static p4.v1.P4RuntimeOuterClass.PacketOut serialize(PacketOut packet, P4Info p4info) {
        var builder = p4.v1.P4RuntimeOuterClass.PacketOut.newBuilder()
                .setPayload(ByteString.copyFrom(packet.payload().toByteArray()));
        for (Map.Entry<String, Bytes> e : packet.metadata().entrySet()) {
            PacketMetadataInfo field = p4info.packetOutField(e.getKey());   // throws known-list on unknown
            requireBitWidth(e.getValue(), field.bitWidth(), "PacketOut metadata '" + field.name() + "'");
            builder.addMetadata(p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
                    .setMetadataId(field.id())
                    .setValue(canonical(e.getValue()))
                    .build());
        }
        return builder.build();
    }

    private static void requireBitWidth(Bytes value, int declared, String context) {
        int actual = EntryValidator.actualBitWidth(value.toByteArray());
        if (actual > declared) {
            throw new P4PipelineException(
                    "value width " + actual + " bits exceeds " + context
                            + " bitWidth " + declared);
        }
    }

    private static ByteString canonical(Bytes b) {
        return ByteString.copyFrom(b.canonical().toByteArray());
    }
}
