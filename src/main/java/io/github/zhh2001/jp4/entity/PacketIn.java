package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

/**
 * One packet delivered to the controller via the StreamChannel
 * {@code PacketIn} message. Carries the raw payload and the
 * {@code controller_packet_metadata} fields declared in P4Info, keyed by
 * field name.
 *
 * <p>Constructed by the inbound parser inside {@code P4Switch}; users only
 * consume. Fields are immutable.
 *
 * @since 0.1.0
 */
public final class PacketIn {

    private final Bytes payload;
    private final Map<String, Bytes> metadata;

    /** Public for the package-internal wire codec; users consume via
     *  {@code P4Switch.onPacketIn / packetInStream / pollPacketIn}. */
    public PacketIn(Bytes payload, Map<String, Bytes> metadata) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.metadata = Map.copyOf(metadata);
    }

    /** Raw packet bytes as delivered by the device, after stripping the
     *  {@code packet_in} controller header. */
    public Bytes payload() {
        return payload;
    }

    /** Returns the metadata field by name, or {@code null} if the device did not
     *  carry a value for that field on this packet. */
    public Bytes metadata(String name) {
        return metadata.get(name);
    }

    /** Immutable view of the full metadata map, in iteration order matching the
     *  P4Info field-declaration order. */
    public Map<String, Bytes> metadata() {
        return metadata;
    }

    /**
     * Convenience accessor that interprets the metadata as an unsigned integer.
     * Throws {@link IllegalStateException} if the field is absent or wider than
     * 31 bits (use {@link #metadata(String)} and convert to {@code long} or
     * {@code BigInteger} for wider values).
     */
    public int metadataInt(String name) {
        Bytes b = metadata.get(name);
        if (b == null) {
            throw new IllegalStateException(
                    "PacketIn has no metadata field '" + name
                            + "' (known: " + metadata.keySet() + ")");
        }
        BigInteger bi = new BigInteger(1, b.toByteArray());
        if (bi.bitLength() > 31) {
            throw new IllegalStateException(
                    "metadata field '" + name + "' is " + bi.bitLength()
                            + " bits wide; does not fit in int");
        }
        return bi.intValueExact();
    }

    @Override
    public String toString() {
        return "PacketIn(payload=" + payload.length() + " bytes, metadata=" + metadata + ")";
    }
}
