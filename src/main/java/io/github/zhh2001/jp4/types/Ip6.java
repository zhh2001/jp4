package io.github.zhh2001.jp4.types;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * 128-bit IPv6 address. Always 16 bytes; constructors validate length and parse the
 * standard literal forms ({@code "2001:db8::1"}, {@code "::1"}, etc.). Defensive copy
 * semantics matching {@link Mac}.
 *
 * @since 0.1.0
 */
public record Ip6(byte[] octets) {

    public Ip6 {
        Objects.requireNonNull(octets, "octets");
        if (octets.length != 16) {
            throw new IllegalArgumentException("IPv6 must be 16 bytes, got " + octets.length);
        }
        octets = octets.clone();
    }

    /**
     * Parses any IPv6 literal supported by {@link InetAddress}. Throws if the input is
     * a hostname or an IPv4 address; never performs DNS lookup (the input is required
     * to contain {@code ':'}).
     */
    public static Ip6 of(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.contains(":")) {
            throw new IllegalArgumentException("Not an IPv6 literal (no ':'): " + text);
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IPv6 literal: " + text, e);
        }
        if (!(addr instanceof Inet6Address)) {
            throw new IllegalArgumentException("Not an IPv6 address: " + text);
        }
        return new Ip6(addr.getAddress());
    }

    /**
     * Wraps a 16-byte big-endian octet array as an {@code Ip6}.
     *
     * <p>For textual input use {@link #of(String)}; this factory is the binary
     * counterpart, useful when an IPv6 address arrives already as raw octets (e.g.
     * read back from a device or extracted from {@link Bytes#toByteArray()}).
     *
     * <p>The input is defensively copied; later mutation of the supplied array does
     * not affect the constructed {@code Ip6}.
     *
     * @param bytes 16-byte big-endian octet array
     * @return an {@code Ip6} wrapping a defensive copy of {@code bytes}
     * @throws NullPointerException if {@code bytes} is null
     * @throws IllegalArgumentException if {@code bytes.length != 16}
     * @since 1.0.0
     */
    public static Ip6 fromBytes(byte[] bytes) {
        return new Ip6(bytes);
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
        return o instanceof Ip6 other && Arrays.equals(this.octets, other.octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    @Override
    public String toString() {
        try {
            return Inet6Address.getByAddress(octets).getHostAddress();
        } catch (UnknownHostException e) {
            // 16-byte input is always valid; this branch is unreachable.
            throw new AssertionError(e);
        }
    }
}
