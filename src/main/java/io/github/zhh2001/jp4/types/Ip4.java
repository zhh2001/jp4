package io.github.zhh2001.jp4.types;

import java.util.Arrays;
import java.util.Objects;

/**
 * 32-bit IPv4 address. Always 4 bytes; constructors validate length and dotted-quad
 * format. Defensive copy semantics matching {@link Mac}.
 */
public record Ip4(byte[] octets) {

    public Ip4 {
        Objects.requireNonNull(octets, "octets");
        if (octets.length != 4) {
            throw new IllegalArgumentException("IPv4 must be 4 bytes, got " + octets.length);
        }
        octets = octets.clone();
    }

    /**
     * Parses dotted-quad form ({@code "10.0.0.1"}). Each octet must be in [0, 255].
     */
    public static Ip4 of(String dotted) {
        Objects.requireNonNull(dotted, "dotted");
        String[] parts = dotted.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("IPv4 must have 4 octets, got: " + dotted);
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v;
            try {
                v = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid IPv4 octet \"" + parts[i] + "\" in " + dotted, e);
            }
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException("IPv4 octet out of [0, 255]: " + v + " in " + dotted);
            }
            out[i] = (byte) v;
        }
        return new Ip4(out);
    }

    /**
     * Builds an IPv4 address from the 32 bits of {@code value}, big-endian.
     */
    public static Ip4 of(int value) {
        return new Ip4(new byte[]{
                (byte) ((value >>> 24) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        });
    }

    @Override
    public byte[] octets() {
        return octets.clone();
    }

    public Bytes toBytes() {
        return Bytes.of(octets);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ip4 other && Arrays.equals(this.octets, other.octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    @Override
    public String toString() {
        return (octets[0] & 0xff) + "." + (octets[1] & 0xff) + "." + (octets[2] & 0xff) + "." + (octets[3] & 0xff);
    }
}
