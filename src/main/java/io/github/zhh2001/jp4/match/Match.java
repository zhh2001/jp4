package io.github.zhh2001.jp4.match;

import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;

import java.util.Objects;

/**
 * One value in a {@code TableEntry} match key. Sealed; subtypes correspond to the
 * P4Runtime {@code FieldMatch} oneof variants. Use {@code switch} on a returned
 * {@code Match} for compile-time exhaustive handling.
 *
 * @since 0.1.0
 */
public sealed interface Match permits Match.Exact, Match.Lpm, Match.Ternary, Match.Range, Match.Optional {

    /** Exact match against a single value. */
    record Exact(Bytes value) implements Match {
        public Exact {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Longest-prefix match: {@code prefixLen} most significant bits of {@code value}. */
    record Lpm(Bytes value, int prefixLen) implements Match {
        public Lpm {
            Objects.requireNonNull(value, "value");
            if (prefixLen < 0) {
                throw new IllegalArgumentException("prefixLen must be >= 0, got " + prefixLen);
            }
        }

        /** Parses CIDR notation, e.g. {@code "10.0.0.0/24"} or {@code "2001:db8::/32"}. */
        public static Lpm of(String cidr) {
            Objects.requireNonNull(cidr, "cidr");
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("CIDR must contain '/', got: " + cidr);
            }
            String addr = cidr.substring(0, slash);
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid prefix length in: " + cidr, e);
            }
            // Heuristic: ':' → IPv6, '.' → IPv4.
            if (addr.contains(":")) {
                return new Lpm(Ip6.of(addr).toBytes(), prefix);
            }
            return new Lpm(Ip4.of(addr).toBytes(), prefix);
        }

        public static Lpm of(Ip4 addr, int prefixLen) {
            return new Lpm(addr.toBytes(), prefixLen);
        }

        public static Lpm of(Ip6 addr, int prefixLen) {
            return new Lpm(addr.toBytes(), prefixLen);
        }
    }

    /** Ternary match: {@code value} masked by {@code mask}. */
    record Ternary(Bytes value, Bytes mask) implements Match {
        public Ternary {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(mask, "mask");
        }

        public static Ternary of(int value, int mask) {
            return new Ternary(Bytes.ofInt(value), Bytes.ofInt(mask));
        }

        public static Ternary of(Bytes value, Bytes mask) {
            return new Ternary(value, mask);
        }
    }

    /** Range match: {@code low} ≤ field ≤ {@code high}. */
    record Range(Bytes low, Bytes high) implements Match {
        public Range {
            Objects.requireNonNull(low, "low");
            Objects.requireNonNull(high, "high");
        }

        public static Range of(int low, int high) {
            return new Range(Bytes.ofInt(low), Bytes.ofInt(high));
        }
    }

    /** P4Runtime "optional" match kind: present (treated as exact) or absent (skipped). */
    record Optional(Bytes value) implements Match {
        public Optional {
            Objects.requireNonNull(value, "value");
        }
    }

    static Match exact(Bytes value) {
        return new Exact(value);
    }

    static Match exact(int value) {
        return new Exact(Bytes.ofInt(value));
    }

    static Match lpm(String cidr) {
        return Lpm.of(cidr);
    }

    static Match ternary(int value, int mask) {
        return Ternary.of(value, mask);
    }
}
