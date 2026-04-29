package io.github.zhh2001.jp4.types;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 128-bit election id used for P4Runtime mastership arbitration. Compared as an
 * unsigned 128-bit integer; higher value wins primary.
 */
public record ElectionId(long high, long low) implements Comparable<ElectionId> {

    public static final ElectionId ZERO = new ElectionId(0L, 0L);
    public static final ElectionId MAX  = new ElectionId(-1L, -1L);   // 0xffff...ffff (unsigned)

    /**
     * Election id with high=0, low={@code low}. The 64-bit space is enough for almost
     * every controller; use {@link #of(long, long)} or {@link #fromBigInteger} when you
     * need the full 128 bits.
     */
    public static ElectionId of(long low) {
        return new ElectionId(0L, low);
    }

    public static ElectionId of(long high, long low) {
        return new ElectionId(high, low);
    }

    /**
     * Builds an election id from a non-negative {@link BigInteger} no wider than 128 bits.
     */
    public static ElectionId fromBigInteger(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("ElectionId must be non-negative, got " + value);
        }
        if (value.bitLength() > 128) {
            throw new IllegalArgumentException("ElectionId must fit in 128 bits, got bitLength=" + value.bitLength());
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        long lo = value.and(mask).longValueExact();
        long hi = value.shiftRight(64).and(mask).longValueExact();
        return new ElectionId(hi, lo);
    }

    public BigInteger toBigInteger() {
        BigInteger hi = BigInteger.valueOf(high).and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE));
        BigInteger lo = BigInteger.valueOf(low).and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE));
        return hi.shiftLeft(64).or(lo);
    }

    @Override
    public int compareTo(ElectionId other) {
        int hcmp = Long.compareUnsigned(this.high, other.high);
        if (hcmp != 0) return hcmp;
        return Long.compareUnsigned(this.low, other.low);
    }

    @Override
    public String toString() {
        if (high == 0L) {
            return "ElectionId(" + Long.toUnsignedString(low) + ")";
        }
        return "ElectionId(" + toBigInteger() + ")";
    }
}
