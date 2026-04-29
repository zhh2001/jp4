package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.match.Match;

import java.util.Objects;

/**
 * Immutable description of one P4 table entry: table name, match key, action, and
 * optional priority. Construction goes through {@link #in(String)} and the builder
 * chain. The same instance can be reused across switches that share the same pipeline.
 *
 * <p>Skeleton in 4A; full validation against the bound P4Info is implemented in Phase 5.
 */
public final class TableEntry {

    TableEntry() {
        // skeleton
    }

    /** Starts building a {@code TableEntry} for the named table. */
    public static TableEntryBuilder in(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        return new TableEntryBuilder(tableName);
    }

    public String tableName() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** The {@link Match} value for the named field, or {@code null} if not part of this entry's key. */
    public Match match(String fieldName) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** The action this entry installs. {@code null} for delete-key-only entries. */
    public ActionInstance action() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    public int priority() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }
}
