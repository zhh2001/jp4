package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;

/**
 * Sub-builder for the action portion of a {@link TableEntry}. Reached via
 * {@link TableEntryBuilder#action(String)}. {@link #priority(int)} delegates back to
 * the parent so chains like
 * {@code .action("foo").param("p", 1).priority(100).build()} stay flat.
 *
 * <p>Skeleton in 4A; {@link #build()} throws.
 */
public final class ActionBuilder {

    private final TableEntryBuilder parent;
    private final String actionName;

    ActionBuilder(TableEntryBuilder parent, String actionName) {
        this.parent = parent;
        this.actionName = actionName;
    }

    public ActionBuilder param(String name, int value) {
        return this;
    }

    public ActionBuilder param(String name, long value) {
        return this;
    }

    public ActionBuilder param(String name, Bytes value) {
        return this;
    }

    public ActionBuilder param(String name, Mac value) {
        return this;
    }

    public ActionBuilder param(String name, Ip4 value) {
        return this;
    }

    public ActionBuilder param(String name, Ip6 value) {
        return this;
    }

    public ActionBuilder param(String name, byte[] value) {
        return this;
    }

    public ActionBuilder priority(int priority) {
        parent.priority(priority);
        return this;
    }

    public TableEntry build() {
        return parent.build();
    }

    String actionName() {
        return actionName;
    }
}
