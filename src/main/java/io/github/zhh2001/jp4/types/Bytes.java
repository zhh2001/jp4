package io.github.zhh2001.jp4.types;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable byte sequence used for P4Runtime field values, action parameters, and
 * packet payloads. Implemented as a {@code final class} (not a {@code record}) because
 * its backing array is defensively copied on construction and on every accessor; a
 * record would re-expose the array and break value semantics.
 *
 * <p><b>Canonical form.</b> P4Runtime 1.3 requires field values to be in canonical form:
 * no leading zero bytes, but at least one byte. Use {@link #canonical()} to coerce, and
 * the {@code ofXxx} factories that produce canonical results by default.
 */
public final class Bytes {

    private static final byte[] EMPTY = new byte[0];
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] data;

    private Bytes(byte[] data) {
        this.data = data;
    }

    public static Bytes of(byte... b) {
        Objects.requireNonNull(b, "b");
        return new Bytes(b.clone());
    }

    /**
     * Canonical big-endian encoding of {@code value} as an unsigned integer: no leading
     * zero bytes, but at least one byte (so {@code ofInt(0)} returns a single 0x00).
     */
    public static Bytes ofInt(int value) {
        return canonicalFromLong(Integer.toUnsignedLong(value));
    }

    /**
     * Fixed-width big-endian encoding of {@code value}, zero-padded or truncated to the
     * minimum number of bytes that holds {@code bitWidth} bits. Use this only when the
     * P4Runtime peer requires non-canonical width (rare); prefer {@link #ofInt(int)}
     * otherwise.
     */
    public static Bytes ofInt(int value, int bitWidth) {
        if (bitWidth <= 0 || bitWidth > 32) {
            throw new IllegalArgumentException("bitWidth must be in [1, 32], got " + bitWidth);
        }
        int byteWidth = (bitWidth + 7) / 8;
        byte[] out = new byte[byteWidth];
        for (int i = byteWidth - 1; i >= 0; i--) {
            out[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        return new Bytes(out);
    }

    public static Bytes ofLong(long value) {
        return canonicalFromLong(value);
    }

    public static Bytes ofHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        return new Bytes(HEX.parseHex(hex));
    }

    private static Bytes canonicalFromLong(long value) {
        if (value == 0L) return new Bytes(new byte[]{0});
        // Find the highest non-zero byte.
        int high = 7;
        while (high > 0 && ((value >>> (high * 8)) & 0xff) == 0) high--;
        byte[] out = new byte[high + 1];
        for (int i = high; i >= 0; i--) {
            out[high - i] = (byte) ((value >>> (i * 8)) & 0xff);
        }
        return new Bytes(out);
    }

    /**
     * Returns a new {@code Bytes} with leading zero bytes stripped. The empty input and
     * an all-zero input both collapse to a single-byte 0x00.
     */
    public Bytes canonical() {
        if (data.length == 0) return new Bytes(new byte[]{0});
        int i = 0;
        while (i < data.length - 1 && data[i] == 0) i++;
        if (i == 0) return this;
        return new Bytes(Arrays.copyOfRange(data, i, data.length));
    }

    public byte[] toByteArray() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }

    /**
     * Internal accessor that returns the backing array without copying. Package-private:
     * jp4 internals can avoid the per-call copy on hot paths but external callers must
     * use {@link #toByteArray()}.
     */
    byte[] internalArray() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bytes other && Arrays.equals(this.data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Bytes(0x" + HEX.formatHex(data) + ")";
    }
}
