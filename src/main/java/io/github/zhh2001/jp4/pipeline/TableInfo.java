package io.github.zhh2001.jp4.pipeline;

import io.github.zhh2001.jp4.error.P4PipelineException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only metadata for one P4 table, derived from P4Info. Construction is internal
 * to {@link P4Info#fromBytes(byte[])} and friends; users obtain instances through
 * {@link P4Info#table(String)}.
 *
 * @since 0.1.0
 */
public final class TableInfo {

    private final String name;
    private final int id;
    private final List<MatchFieldInfo> matchFields;
    private final Map<String, MatchFieldInfo> matchFieldsByName;
    private final Map<Integer, MatchFieldInfo> matchFieldsById;
    private final Set<Integer> actionIds;
    private final long maxSize;

    TableInfo(String name, int id,
              List<MatchFieldInfo> matchFields,
              Set<Integer> actionIds,
              long maxSize) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.matchFields = List.copyOf(matchFields);
        this.actionIds = Set.copyOf(actionIds);
        this.maxSize = maxSize;

        Map<String, MatchFieldInfo> idx = new HashMap<>(this.matchFields.size() * 2);
        Map<Integer, MatchFieldInfo> idxById = new HashMap<>(this.matchFields.size() * 2);
        for (MatchFieldInfo mf : this.matchFields) {
            idx.put(mf.name(), mf);
            idxById.put(mf.id(), mf);
        }
        this.matchFieldsByName = Map.copyOf(idx);
        this.matchFieldsById = Map.copyOf(idxById);
    }

    /** Fully-qualified table name, e.g. {@code "MyIngress.ipv4_lpm"}. */
    public String name() {
        return name;
    }

    /** P4Runtime numeric id assigned by p4c. */
    public int id() {
        return id;
    }

    /** Match-key fields in the order declared in the P4 program. */
    public List<MatchFieldInfo> matchFields() {
        return matchFields;
    }

    /**
     * Looks up one match field by its fully-qualified name. Throws
     * {@link P4PipelineException} if the table does not have a field by that name —
     * surfaces typos at the call site instead of returning {@code null}.
     */
    public MatchFieldInfo matchField(String fieldName) {
        MatchFieldInfo mf = matchFieldsByName.get(fieldName);
        if (mf == null) {
            throw new P4PipelineException(
                    "table " + name + " has no match field named '" + fieldName
                            + "' (known: " + matchFieldsByName.keySet() + ")");
        }
        return mf;
    }

    /**
     * Reverse lookup: returns the {@link MatchFieldInfo} for a given numeric field id, or
     * {@code null} if the table has no field by that id. Used by the read-RPC reverse
     * parser when turning a {@code p4.v1.FieldMatch} back into a jp4 {@code Match}.
     */
    public MatchFieldInfo matchFieldById(int id) {
        return matchFieldsById.get(id);
    }

    /** Action ids permitted by this table, as a set. */
    public Set<Integer> actionIds() {
        return actionIds;
    }

    /** Maximum entry count declared by the P4 table; 0 means unspecified. */
    public long maxSize() {
        return maxSize;
    }

    @Override
    public String toString() {
        return "TableInfo(name=" + name + ", id=" + id + ", matchFields=" + matchFields.size()
                + ", actionIds=" + actionIds + ", maxSize=" + maxSize + ")";
    }
}
