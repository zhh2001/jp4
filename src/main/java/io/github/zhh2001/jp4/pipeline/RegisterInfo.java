package io.github.zhh2001.jp4.pipeline;

import java.util.Objects;

/**
 * Read-only metadata for one P4 register array, derived from P4Info.
 * Construction is internal to {@link P4Info#fromBytes(byte[])} and friends;
 * users obtain instances through {@link P4Info#register(String)}.
 *
 * <p>The register's per-cell {@code P4DataTypeSpec} is intentionally not
 * exposed on this value type; v1.4 surfaces register cell data as raw
 * {@code Bytes} on the read path, matching the convention {@link
 * io.github.zhh2001.jp4.entity.DigestEvent} uses for {@code P4Data}.
 * Typed register payloads are a future v1.x topic.
 *
 * <p>Instances are constructed once during P4Info parsing and are immutable
 * thereafter; safe to share across threads.
 *
 * @since 1.4.0
 */
public final class RegisterInfo {

    private final String name;
    private final int id;
    private final long size;

    RegisterInfo(String name, int id, long size) {
        this.name = Objects.requireNonNull(name, "name");
        this.id = id;
        this.size = size;
    }

    /** Fully-qualified register name, e.g. {@code "MyIngress.flow_counters"}. */
    public String name() {
        return name;
    }

    /** P4Runtime numeric id assigned by p4c. */
    public int id() {
        return id;
    }

    /** Number of cells in the register array; the cell at {@code Index} is the
     *  user-addressable target on a Read or Write of {@code RegisterEntry}. */
    public long size() {
        return size;
    }

    @Override
    public String toString() {
        return "RegisterInfo(name=" + name + ", id=" + id + ", size=" + size + ")";
    }
}
