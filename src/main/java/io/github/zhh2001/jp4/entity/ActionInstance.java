package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

/**
 * The action selected by a {@code TableEntry}: an action name plus its parameter values
 * keyed by parameter name. Immutable.
 *
 * <p>The read-side counterpart to the {@code action(...).param(...)} builder chain.
 * Constructed indirectly via {@link ActionBuilder#build()}; not part of the public
 * construction surface — users build entries through
 * {@link TableEntry#in(String)}.
 *
 * <p>Parameter values are stored as {@link Bytes} regardless of how the user supplied
 * them ({@code int}, {@code long}, {@code Mac}, etc.). Width / canonicality
 * normalisation against the bound P4Info happens at switch-op time, not here.
 *
 * @since 0.1.0
 */
public final class ActionInstance {

    private final String name;
    private final Map<String, Bytes> params;

    ActionInstance(String name, Map<String, Bytes> params) {
        this.name = Objects.requireNonNull(name, "name");
        this.params = Map.copyOf(params);
    }

    /**
     * Constructs an {@code ActionInstance} from an already-resolved name and
     * parameter map. Intended for read-back paths that reconstruct entities
     * from wire {@code Action} messages — for example
     * {@code P4Switch.readActionProfileMember} — without going through the
     * {@link ActionBuilder} chain.
     *
     * <p>The {@code params} map is captured through {@link Map#copyOf} so
     * later mutations of the caller's map do not affect the resulting
     * instance. No P4Info validation is performed; callers that need
     * validation are expected to perform it at the wire boundary.
     *
     * @param name   fully-qualified action name; never {@code null}
     * @param params parameter values by name; never {@code null}, copied
     *               defensively
     * @return a new {@code ActionInstance}
     * @throws NullPointerException if {@code name} or {@code params} is null
     * @since 1.4.0
     */
    public static ActionInstance of(String name, Map<String, Bytes> params) {
        return new ActionInstance(name, params);
    }

    /** Fully-qualified action name, e.g. {@code "MyIngress.set_egress"}. */
    public String name() {
        return name;
    }

    /** Immutable view of the parameters by name. */
    public Map<String, Bytes> params() {
        return params;
    }

    /**
     * Returns the value bound to {@code paramName}, or {@code null} if the action
     * has no such parameter on this instance. Use this to query an entry read back
     * from the device; entries built via the user-facing chain expose params() if
     * iteration is needed.
     *
     * @param paramName the parameter name as declared in P4Info
     * @return the parameter value as raw {@link Bytes}, or {@code null} if the
     *         action has no such parameter on this instance
     * @throws NullPointerException if {@code paramName} is null
     */
    public Bytes param(String paramName) {
        Objects.requireNonNull(paramName, "paramName");
        return params.get(paramName);
    }

    /**
     * Convenience accessor that interprets the parameter as an unsigned big-endian
     * integer and returns the value as a Java {@code int}. Mirrors the read-side
     * shape of {@link io.github.zhh2001.jp4.entity.PacketIn#metadataInt(String)}:
     * width is checked against the 31-bit signed-int range (the high bit is
     * reserved for sign) and absent fields are reported with the known parameter
     * list to aid call-site debugging.
     *
     * <p>Use {@link #paramLong(String)} for parameters whose bit-width fits in 63
     * bits but exceeds 31, and {@link #param(String)} (returning {@link Bytes}) for
     * any wider value or when binary handling is preferred.
     *
     * <p>A zero-length or all-zero parameter byte sequence is interpreted as the
     * value {@code 0}; this matches P4Runtime's natural unsigned encoding.
     *
     * @param paramName the parameter name as declared in P4Info
     * @return the unsigned-big-endian integer value of the parameter
     * @throws IllegalStateException if no parameter with {@code paramName} exists
     *         on this instance, or the value is wider than 31 bits
     * @throws NullPointerException if {@code paramName} is null
     * @see #paramLong(String)
     * @see #param(String)
     * @since 1.0.0
     */
    public int paramInt(String paramName) {
        Objects.requireNonNull(paramName, "paramName");
        Bytes b = params.get(paramName);
        if (b == null) {
            throw new IllegalStateException(
                    "ActionInstance has no parameter '" + paramName
                            + "' (known: " + params.keySet() + ")");
        }
        BigInteger bi = new BigInteger(1, b.toByteArray());
        if (bi.bitLength() > 31) {
            throw new IllegalStateException(
                    "parameter '" + paramName + "' has width " + bi.bitLength()
                            + " bits, exceeds 31-bit signed int range; "
                            + "use paramLong or param(String) directly");
        }
        return bi.intValueExact();
    }

    /**
     * Convenience accessor that interprets the parameter as an unsigned big-endian
     * integer and returns the value as a Java {@code long}. Width is checked
     * against the 63-bit signed-long range; otherwise behaves identically to
     * {@link #paramInt(String)}.
     *
     * <p>Use {@link #param(String)} (returning {@link Bytes}) for parameters wider
     * than 63 bits or when binary handling is preferred.
     *
     * @param paramName the parameter name as declared in P4Info
     * @return the unsigned-big-endian long value of the parameter
     * @throws IllegalStateException if no parameter with {@code paramName} exists
     *         on this instance, or the value is wider than 63 bits
     * @throws NullPointerException if {@code paramName} is null
     * @see #paramInt(String)
     * @see #param(String)
     * @since 1.0.0
     */
    public long paramLong(String paramName) {
        Objects.requireNonNull(paramName, "paramName");
        Bytes b = params.get(paramName);
        if (b == null) {
            throw new IllegalStateException(
                    "ActionInstance has no parameter '" + paramName
                            + "' (known: " + params.keySet() + ")");
        }
        BigInteger bi = new BigInteger(1, b.toByteArray());
        if (bi.bitLength() > 63) {
            throw new IllegalStateException(
                    "parameter '" + paramName + "' has width " + bi.bitLength()
                            + " bits, exceeds 63-bit signed long range; "
                            + "use param(String) directly for wider values");
        }
        return bi.longValueExact();
    }

    @Override
    public String toString() {
        return "ActionInstance(name=" + name + ", params=" + params + ")";
    }
}
