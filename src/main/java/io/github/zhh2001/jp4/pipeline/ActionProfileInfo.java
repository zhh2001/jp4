package io.github.zhh2001.jp4.pipeline;

import java.util.Objects;
import java.util.Set;

/**
 * Read-only metadata for one P4 action profile, derived from P4Info.
 * Construction is internal to {@link P4Info#fromBytes(byte[])} and friends;
 * users obtain instances through {@link P4Info#actionProfile(String)}.
 *
 * <p>An action profile is the indirect-action mechanism for tables: instead of
 * binding an action inline on every entry, a table can reference one or more
 * pre-configured action profile members (or groups of members when
 * {@link #withSelector()} is true). The {@link #tableIds()} set lists which
 * tables share this profile.
 *
 * <p>The {@code selector_size_semantics} field added in newer P4Info revisions
 * is intentionally not exposed in v1.4; it can be added in a future v1.x
 * release without affecting the existing surface.
 *
 * <p>Instances are constructed once during P4Info parsing and are immutable
 * thereafter; safe to share across threads.
 *
 * @since 1.4.0
 */
public final class ActionProfileInfo {

    private final String name;
    private final int id;
    private final boolean withSelector;
    private final long size;
    private final int maxGroupSize;
    private final Set<Integer> tableIds;

    ActionProfileInfo(String name, int id, boolean withSelector,
                      long size, int maxGroupSize, Set<Integer> tableIds) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.withSelector = withSelector;
        this.size = size;
        this.maxGroupSize = maxGroupSize;
        this.tableIds = Set.copyOf(Objects.requireNonNull(tableIds, "tableIds"));
    }

    /** Fully-qualified action-profile name, e.g. {@code "MyIngress.ecmp_profile"}. */
    public String name() {
        return name;
    }

    /** P4Runtime numeric id assigned by p4c. */
    public int id() {
        return id;
    }

    /** True iff the action profile uses dynamic selection (action selector). */
    public boolean withSelector() {
        return withSelector;
    }

    /** Maximum total member entries the profile can hold; if {@link #withSelector()}
     *  is true the semantics depend on the target's selector_size_semantics. */
    public long size() {
        return size;
    }

    /** Maximum weighted member entries per group; {@code 0} when the profile does
     *  not use a selector. */
    public int maxGroupSize() {
        return maxGroupSize;
    }

    /** Immutable set of table ids that share this action profile. */
    public Set<Integer> tableIds() {
        return tableIds;
    }

    @Override
    public String toString() {
        return "ActionProfileInfo(name=" + name + ", id=" + id
                + ", withSelector=" + withSelector
                + ", size=" + size
                + ", maxGroupSize=" + maxGroupSize
                + ", tableIds=" + tableIds + ")";
    }
}
