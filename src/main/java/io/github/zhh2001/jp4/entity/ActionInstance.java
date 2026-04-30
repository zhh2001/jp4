package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

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
     */
    public Bytes param(String paramName) {
        return params.get(paramName);
    }

    @Override
    public String toString() {
        return "ActionInstance(name=" + name + ", params=" + params + ")";
    }
}
