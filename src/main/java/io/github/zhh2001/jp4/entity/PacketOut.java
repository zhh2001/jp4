package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Mac;

/**
 * Outbound packet for {@code P4Switch.send(...)}. Build via {@link #builder()}.
 *
 * <p>Skeleton in 4A.
 */
public final class PacketOut {

    PacketOut() {
        // skeleton
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link PacketOut}. Skeleton in 4A; {@link #build()} throws. */
    public static final class Builder {

        Builder() {
            // skeleton
        }

        public Builder payload(byte[] bytes) {
            return this;
        }

        public Builder payload(Bytes bytes) {
            return this;
        }

        public Builder metadata(String name, int value) {
            return this;
        }

        public Builder metadata(String name, long value) {
            return this;
        }

        public Builder metadata(String name, Bytes value) {
            return this;
        }

        public Builder metadata(String name, Mac value) {
            return this;
        }

        public Builder metadata(String name, byte[] value) {
            return this;
        }

        public PacketOut build() {
            throw new UnsupportedOperationException("Not yet implemented in 4A");
        }
    }
}
