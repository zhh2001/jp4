package io.github.zhh2001.jp4.pipeline;

import java.nio.file.Path;
import java.util.List;

/**
 * Parsed P4Info. Built once and cached on a {@code P4Switch} via
 * {@code bindPipeline} or {@code loadPipeline}; subsequent operations look up tables /
 * actions / match fields by name through the index this object owns.
 *
 * <p>Skeleton in 4A: implementations of every accessor throw
 * {@link UnsupportedOperationException}. Full implementation lands in Phase 5 when
 * entry validation is wired up.
 */
public final class P4Info {

    P4Info() {
        // skeleton; constructor will accept parsed protobuf in Phase 5
    }

    /**
     * Reads a P4Info file and auto-detects whether it is text-format
     * ({@code name: "..."} lines) or binary protobuf. Throws
     * {@link io.github.zhh2001.jp4.error.P4PipelineException} on parse failure.
     */
    public static P4Info fromFile(Path path) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** Parses text-format P4Info. */
    public static P4Info fromText(Path path) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** Parses binary protobuf P4Info. */
    public static P4Info fromBinary(Path path) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /** Parses raw bytes; format auto-detected. */
    public static P4Info fromBytes(byte[] bytes) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    public List<String> tableNames() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    public List<String> actionNames() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /**
     * Looks up a table by its fully-qualified name (e.g. {@code "MyIngress.dmac"}).
     * Throws {@link io.github.zhh2001.jp4.error.P4PipelineException} if no such table.
     */
    public TableInfo table(String name) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    public ActionInfo action(String name) {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }
}
