package io.github.zhh2001.jp4.types;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 48-bit MAC address. Always 6 bytes; constructors validate length and parse format.
 *
 * <p>Although declared as a {@code record}, the compact constructor defensively copies
 * the input array and the {@link #octets()} accessor returns a fresh copy, so callers
 * cannot mutate the underlying state.
 *
 * @since 0.1.0
 */
public record Mac(byte[] octets) {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * The all-zero MAC address ({@code 00:00:00:00:00:00}). Useful as a sentinel
     * for invalid or default-initialised source addresses — e.g. to filter out
     * stray uninitialised Ethernet frames that some test interfaces (loopback,
     * tap) carry alongside controller-injected traffic.
     *
     * @since 1.1.0
     */
    public static final Mac ZERO = new Mac(new byte[6]);

    public Mac {
        Objects.requireNonNull(octets, "octets");
        if (octets.length != 6) {
            throw new IllegalArgumentException("MAC must be 6 bytes, got " + octets.length);
        }
        octets = octets.clone();
    }

    /**
     * Parses one of the common textual MAC representations. Accepts colon-separated
     * ({@code "de:ad:be:ef:00:01"}), hyphen-separated ({@code "de-ad-be-ef-00-01"}),
     * or unseparated ({@code "deadbeef0001"}); case-insensitive.
     */
    public static Mac of(String text) {
        Objects.requireNonNull(text, "text");
        String compact = text.replace(":", "").replace("-", "");
        if (compact.length() != 12) {
            throw new IllegalArgumentException("MAC must have 12 hex digits, got: " + text);
        }
        return new Mac(HEX.parseHex(compact));
    }

    /**
     * Wraps a 6-byte octet array as a {@code Mac}.
     *
     * <p>For textual input use {@link #of(String)}; for the {@code long} primitive
     * form use {@link #of(long)}; this factory is the binary counterpart, useful
     * when a MAC arrives already as raw octets (e.g. read back from a device or
     * extracted from {@link Bytes#toByteArray()}).
     *
     * <p>The input is defensively copied; later mutation of the supplied array does
     * not affect the constructed {@code Mac}.
     *
     * @param bytes 6-byte octet array (octets 0..5 from highest-order byte to
     *              lowest-order byte of the EUI-48 representation)
     * @return a {@code Mac} wrapping a defensive copy of {@code bytes}
     * @throws NullPointerException if {@code bytes} is null
     * @throws IllegalArgumentException if {@code bytes.length != 6}
     * @since 1.0.0
     */
    public static Mac fromBytes(byte[] bytes) {
        return new Mac(bytes);
    }

    /**
     * Builds a MAC from the low 48 bits of {@code value}, big-endian.
     */
    public static Mac of(long value) {
        byte[] out = new byte[6];
        for (int i = 5; i >= 0; i--) {
            out[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        return new Mac(out);
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
        return o instanceof Mac other && Arrays.equals(this.octets, other.octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    @Override
    public String toString() {
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                octets[0] & 0xff, octets[1] & 0xff, octets[2] & 0xff,
                octets[3] & 0xff, octets[4] & 0xff, octets[5] & 0xff);
    }
}
