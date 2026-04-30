package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent builder returned by {@link TableEntry#in(String)}. Match-field setters infer
 * the {@code Match} kind: bare {@link Bytes} / {@link Mac} / {@link Ip4} / {@link Ip6}
 * / {@code int} / {@code long} / {@code byte[]} are wrapped as exact match; pass an
 * explicit {@link Match} subtype for LPM, ternary, range, or optional.
 *
 * <p>The {@code int} and {@code long} overloads reject negative values with
 * {@link IllegalArgumentException}; pass a {@code byte[]} or {@link Bytes} explicitly
 * if you need to express a bit pattern with the high bit set. Other overloads
 * (Bytes, byte[], Mac, Ip4, Ip6) accept any value the type itself accepts.
 *
 * <p>Calling {@code .match} for the same field twice replaces the earlier value.
 *
 * @since 0.1.0
 */
public final class TableEntryBuilder {

    private final String tableName;
    private final Map<String, Match> matchByField = new LinkedHashMap<>();
    private ActionInstance action;
    private int priority;

    TableEntryBuilder(String tableName) {
        this.tableName = tableName;
    }

    public TableEntryBuilder match(String fieldName, Bytes value) {
        Objects.requireNonNull(value, "value");
        return record(fieldName, new Match.Exact(value));
    }

    public TableEntryBuilder match(String fieldName, Mac value) {
        Objects.requireNonNull(value, "value");
        return record(fieldName, new Match.Exact(value.toBytes()));
    }

    public TableEntryBuilder match(String fieldName, Ip4 value) {
        Objects.requireNonNull(value, "value");
        return record(fieldName, new Match.Exact(value.toBytes()));
    }

    public TableEntryBuilder match(String fieldName, Ip6 value) {
        Objects.requireNonNull(value, "value");
        return record(fieldName, new Match.Exact(value.toBytes()));
    }

    public TableEntryBuilder match(String fieldName, int value) {
        requireNonNegative(value);
        return record(fieldName, new Match.Exact(Bytes.ofInt(value)));
    }

    public TableEntryBuilder match(String fieldName, long value) {
        requireNonNegative(value);
        return record(fieldName, new Match.Exact(Bytes.ofLong(value)));
    }

    public TableEntryBuilder match(String fieldName, byte[] value) {
        Objects.requireNonNull(value, "value");
        return record(fieldName, new Match.Exact(Bytes.of(value)));
    }

    public TableEntryBuilder match(String fieldName, Match match) {
        Objects.requireNonNull(match, "match");
        return record(fieldName, match);
    }

    /** Begins describing the action this entry installs. Returns the action sub-builder;
     *  call {@code .param(...)}, {@code .priority(...)}, and {@code .build()} on it. */
    public ActionBuilder action(String actionName) {
        Objects.requireNonNull(actionName, "actionName");
        return new ActionBuilder(this, actionName);
    }

    /** Sets the entry's priority (required for ternary / range tables, ignored otherwise). */
    public TableEntryBuilder priority(int priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Builds the table entry. The {@code action} step is optional: entries built without
     * an action are valid for {@code delete} but will be rejected by {@code insert} and
     * {@code modify} at the operation site (with a clear error message at runtime).
     *
     * <p>Most callers should call {@code .action(...)} before {@code .build()}; the
     * no-action shortcut exists specifically for deletes that match by key only.
     */
    public TableEntry build() {
        Objects.requireNonNull(tableName, "tableName");
        return new TableEntry(tableName, matchByField, action, priority);
    }

    String tableName() {
        return tableName;
    }

    void recordAction(ActionInstance action) {
        this.action = action;
    }

    private TableEntryBuilder record(String fieldName, Match value) {
        Objects.requireNonNull(fieldName, "fieldName");
        matchByField.put(fieldName, value);
        return this;
    }

    private static void requireNonNegative(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "value must be non-negative; use byte[] / Bytes for explicit bit pattern");
        }
    }
}
