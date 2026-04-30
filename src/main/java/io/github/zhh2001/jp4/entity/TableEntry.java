package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.match.Match;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of one P4 table entry: table name, match key, optional action,
 * optional priority. Construction goes through {@link #in(String)} and the builder
 * chain. Same instance can be reused across switches that share the same pipeline.
 *
 * <p>This type holds the user-supplied raw values; {@link Match} subtypes wrap match
 * fields, {@link ActionInstance} wraps the chosen action plus its parameters. The
 * library does not validate against any P4Info at this layer — that happens when the
 * entry is handed to a {@code P4Switch} operation (insert / modify / delete / read).
 * P4Info-driven failures surface as {@code P4PipelineException} with a known-list at
 * the switch-op site.
 *
 * @since 0.1.0
 */
public final class TableEntry {

    private final String tableName;
    private final Map<String, Match> matchByField;
    private final ActionInstance action;
    private final int priority;

    TableEntry(String tableName,
               Map<String, Match> matchByField,
               ActionInstance action,
               int priority) {
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.matchByField = Map.copyOf(matchByField);
        this.action = action;   // null is valid: delete-only entries
        this.priority = priority;
    }

    /** Starts building a {@code TableEntry} for the named table. */
    public static TableEntryBuilder in(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        return new TableEntryBuilder(tableName);
    }

    /** Fully-qualified table name as written in the P4 program. */
    public String tableName() {
        return tableName;
    }

    /**
     * Returns the {@link Match} value bound to {@code fieldName}, or {@code null} if
     * the field is not part of this entry's key. Use {@code switch} on the returned
     * {@code Match} for exhaustive handling of the five match kinds.
     */
    public Match match(String fieldName) {
        return matchByField.get(fieldName);
    }

    /** Immutable view of the entry's key as a {@code (fieldName -> Match)} map. */
    public Map<String, Match> matches() {
        return matchByField;
    }

    /**
     * The action selected by this entry, or {@code null} for delete-only entries
     * (built via {@link TableEntryBuilder#build()} without calling {@code .action(...)}).
     */
    public ActionInstance action() {
        return action;
    }

    /** Priority for ternary / range tables; {@code 0} means unspecified. */
    public int priority() {
        return priority;
    }

    @Override
    public String toString() {
        return "TableEntry(table=" + tableName
                + ", matches=" + matchByField
                + ", action=" + action
                + ", priority=" + priority + ")";
    }
}
