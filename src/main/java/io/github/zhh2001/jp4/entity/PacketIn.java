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
     * Convenience accessor that interprets the metadata as an unsigned big-endian
     * integer and returns the value as a Java {@code int}. Width is checked against
     * the 31-bit signed-int range (the high bit is reserved for sign); absent
     * fields are reported with the known field list to aid call-site debugging.
     *
     * <p>Use {@link #metadataLong(String)} for fields whose bit-width fits in 63
     * bits but exceeds 31, and {@link #metadata(String)} (returning {@link Bytes})
     * for any wider value or when binary handling is preferred.
     *
     * <p>A zero-length or all-zero field byte sequence is interpreted as the value
     * {@code 0}; this matches P4Runtime's natural unsigned encoding.
     *
     * @param name the field name as declared in the {@code controller_packet_metadata}
     * @return the unsigned-big-endian integer value of the field
     * @throws IllegalStateException if no field with {@code name} exists on this
     *         packet, or the value is wider than 31 bits
     * @throws NullPointerException if {@code name} is null
     * @see #metadataLong(String)
     * @see #metadata(String)
     */
    public int metadataInt(String name) {
        Objects.requireNonNull(name, "name");
        Bytes b = metadata.get(name);
        if (b == null) {
            throw new IllegalStateException(
                    "PacketIn has no metadata field '" + name
                            + "' (known: " + metadata.keySet() + ")");
        }
        BigInteger bi = new BigInteger(1, b.toByteArray());
        if (bi.bitLength() > 31) {
            throw new IllegalStateException(
                    "metadata field '" + name + "' has width " + bi.bitLength()
                            + " bits, exceeds 31-bit signed int range; "
                            + "use metadataLong or metadata(String) directly");
        }
        return bi.intValueExact();
    }

    /**
     * Convenience accessor that interprets the metadata as an unsigned big-endian
     * integer and returns the value as a Java {@code long}. Width is checked
     * against the 63-bit signed-long range; otherwise behaves identically to
     * {@link #metadataInt(String)}.
     *
     * <p>Use {@link #metadata(String)} (returning {@link Bytes}) for fields wider
     * than 63 bits or when binary handling is preferred.
     *
     * @param name the field name as declared in the {@code controller_packet_metadata}
     * @return the unsigned-big-endian long value of the field
     * @throws IllegalStateException if no field with {@code name} exists on this
     *         packet, or the value is wider than 63 bits
     * @throws NullPointerException if {@code name} is null
     * @see #metadataInt(String)
     * @see #metadata(String)
     * @since 1.0.0
     */
    public long metadataLong(String name) {
        Objects.requireNonNull(name, "name");
        Bytes b = metadata.get(name);
        if (b == null) {
            throw new IllegalStateException(
                    "PacketIn has no metadata field '" + name
                            + "' (known: " + metadata.keySet() + ")");
        }
        BigInteger bi = new BigInteger(1, b.toByteArray());
        if (bi.bitLength() > 63) {
            throw new IllegalStateException(
                    "metadata field '" + name + "' has width " + bi.bitLength()
                            + " bits, exceeds 63-bit signed long range; "
                            + "use metadata(String) directly for wider values");
        }
        return bi.longValueExact();
    }

    @Override
    public String toString() {
        return "PacketIn(payload=" + payload.length() + " bytes, metadata=" + metadata + ")";
    }
}
