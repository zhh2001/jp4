package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sub-builder for the action portion of a {@link TableEntry}. Reached via
 * {@link TableEntryBuilder#action(String)}. {@link #priority(int)} delegates back to
 * the parent so chains like
 * {@code .action("foo").param("p", 1).priority(100).build()} stay flat.
 *
 * <p>Same negative-value rule as {@link TableEntryBuilder#match}: the {@code int} and
 * {@code long} overloads reject negative values with {@link IllegalArgumentException}.
 * Use {@code byte[]} or {@link Bytes} explicitly for bit patterns with the high bit set.
 *
 * <p>Calling {@code .param} twice for the same name replaces the earlier value.
 *
 * @since 0.1.0
 */
public final class ActionBuilder {

    private final TableEntryBuilder parent;
    private final String actionName;
    private final Map<String, Bytes> params = new LinkedHashMap<>();

    ActionBuilder(TableEntryBuilder parent, String actionName) {
        this.parent = parent;
        this.actionName = actionName;
    }

    public ActionBuilder param(String name, int value) {
        requireNonNegative(value);
        return record(name, Bytes.ofInt(value));
    }

    public ActionBuilder param(String name, long value) {
        requireNonNegative(value);
        return record(name, Bytes.ofLong(value));
    }

    public ActionBuilder param(String name, Bytes value) {
        Objects.requireNonNull(value, "value");
        return record(name, value);
    }

    public ActionBuilder param(String name, Mac value) {
        Objects.requireNonNull(value, "value");
        return record(name, value.toBytes());
    }

    public ActionBuilder param(String name, Ip4 value) {
        Objects.requireNonNull(value, "value");
        return record(name, value.toBytes());
    }

    public ActionBuilder param(String name, Ip6 value) {
        Objects.requireNonNull(value, "value");
        return record(name, value.toBytes());
    }

    public ActionBuilder param(String name, byte[] value) {
        Objects.requireNonNull(value, "value");
        return record(name, Bytes.of(value));
    }

    /** Sets the entry's priority through the parent builder so chains stay flat. */
    public ActionBuilder priority(int priority) {
        parent.priority(priority);
        return this;
    }

    /**
     * Records the action on the parent builder, then delegates to
     * {@link TableEntryBuilder#build()}.
     */
    public TableEntry build() {
        parent.recordAction(new ActionInstance(actionName, params));
        return parent.build();
    }

    String actionName() {
        return actionName;
    }

    private ActionBuilder record(String name, Bytes value) {
        Objects.requireNonNull(name, "name");
        params.put(name, value);
        return this;
    }

    private static void requireNonNegative(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "value must be non-negative; use byte[] / Bytes for explicit bit pattern");
        }
    }
}
