package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;

/**
 * Fluent builder returned by {@link TableEntry#in(String)}. Match-field setters infer
 * the {@code Match} kind: bare {@link Bytes} / {@link Mac} / {@link Ip4} / {@link Ip6}
 * / {@code int} / {@code long} / {@code byte[]} are wrapped as exact match; pass an
 * explicit {@link Match} subtype for LPM, ternary, range, or optional.
 *
 * <p>Skeleton in 4A: every method returns {@code this} (or hands off to
 * {@link ActionBuilder}) so the API compiles and chains correctly, but
 * {@link #build()} throws {@link UnsupportedOperationException}. Full P4Info-driven
 * validation lands in Phase 5.
 */
public final class TableEntryBuilder {

    private final String tableName;

    TableEntryBuilder(String tableName) {
        this.tableName = tableName;
    }

    public TableEntryBuilder match(String fieldName, Bytes value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, Mac value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, Ip4 value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, Ip6 value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, int value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, long value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, byte[] value) {
        return this;
    }

    public TableEntryBuilder match(String fieldName, Match match) {
        return this;
    }

    /** Begins describing the action this entry installs. Returns to the action sub-builder. */
    public ActionBuilder action(String actionName) {
        return new ActionBuilder(this, actionName);
    }

    /** Sets the entry's priority (required for ternary / range tables, ignored otherwise). */
    public TableEntryBuilder priority(int priority) {
        return this;
    }

    /**
     * Produces a {@link TableEntry} with the configured key, action, and priority.
     * Skeleton in 4A.
     */
    public TableEntry build() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    String tableName() {
        return tableName;
    }
}
