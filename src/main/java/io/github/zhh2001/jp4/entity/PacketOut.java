package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Mac;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outbound packet for {@code P4Switch.send(...)}. Build via {@link #builder()}.
 * Carries the raw payload and the {@code controller_packet_metadata} fields the
 * P4 program declares for the {@code packet_out} controller header. Field names
 * are validated against the bound P4Info at switch-op time, not at build time.
 *
 * <p>{@code PacketOut} is an immutable value: the same instance is safe to
 * {@code send} multiple times.
 *
 * @since 0.1.0
 */
public final class PacketOut {

    private final Bytes payload;
    private final Map<String, Bytes> metadata;

    private PacketOut(Bytes payload, Map<String, Bytes> metadata) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.metadata = Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Raw bytes the controller wants the device to emit (after the
     *  {@code packet_out} controller header). */
    public Bytes payload() {
        return payload;
    }

    /** Immutable view of the metadata fields keyed by name (insertion order). */
    public Map<String, Bytes> metadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "PacketOut(payload=" + payload.length() + " bytes, metadata=" + metadata + ")";
    }

    /**
     * Fluent builder. {@code metadata(name, int|long)} reject negative values with
     * {@link IllegalArgumentException} (pass {@link Bytes} or {@code byte[]} for an
     * explicit bit pattern). Calling {@code metadata} for the same field twice
     * replaces the prior value (last-write-wins).
     */
    public static final class Builder {

        private Bytes payload = Bytes.of(new byte[0]);
        private final Map<String, Bytes> metadata = new LinkedHashMap<>();

        Builder() { }

        public Builder payload(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            this.payload = Bytes.of(bytes);
            return this;
        }

        public Builder payload(Bytes bytes) {
            this.payload = Objects.requireNonNull(bytes, "bytes");
            return this;
        }

        public Builder metadata(String name, int value) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "metadata int value must be non-negative; pass byte[] or Bytes for an "
                                + "explicit bit pattern. Got " + value);
            }
            return metadata(name, Bytes.ofInt(value));
        }

        public Builder metadata(String name, long value) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        "metadata long value must be non-negative; pass byte[] or Bytes for an "
                                + "explicit bit pattern. Got " + value);
            }
            return metadata(name, Bytes.ofLong(value));
        }

        public Builder metadata(String name, Bytes value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            metadata.put(name, value);
            return this;
        }

        public Builder metadata(String name, Mac value) {
            Objects.requireNonNull(value, "value");
            return metadata(name, value.toBytes());
        }

        public Builder metadata(String name, byte[] value) {
            Objects.requireNonNull(value, "value");
            return metadata(name, Bytes.of(value));
        }

        public PacketOut build() {
            return new PacketOut(payload, metadata);
        }
    }
}
