package io.github.zhh2001.jp4.entity;

/**
 * The action selected by a {@code TableEntry}: an action name plus its parameter values.
 * The read-side counterpart to the {@code action(...).param(...)} builder chain.
 *
 * <p>Skeleton in 4A; populated in Phase 5 once entry validation lands.
 */
public final class ActionInstance {

    ActionInstance() {
        // skeleton
    }

    /** Fully-qualified action name, e.g. {@code "MyIngress.set_egress"}. */
    public String name() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }
}
