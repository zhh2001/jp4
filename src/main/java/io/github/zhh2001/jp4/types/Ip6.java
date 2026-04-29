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
