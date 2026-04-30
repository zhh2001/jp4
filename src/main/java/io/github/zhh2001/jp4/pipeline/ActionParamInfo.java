package io.github.zhh2001.jp4.pipeline;

/**
 * Read-only metadata for one parameter of an {@link ActionInfo}, derived from P4Info.
 *
 * @param id       P4Runtime numeric id (1-based, unique within the owning action)
 * @param name     parameter name as written in the P4 program
 * @param bitWidth width as declared in the P4 program
 *
 * @since 0.1.0
 */
public record ActionParamInfo(int id, String name, int bitWidth) {
}
