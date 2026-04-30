package io.github.zhh2001.jp4.pipeline;

/**
 * Read-only metadata for one match field of a {@link TableInfo}, derived from P4Info.
 *
 * @param id        P4Runtime numeric id (1-based, unique within the owning table)
 * @param name      fully-qualified field name as written in the P4 program, e.g.
 *                  {@code "hdr.ipv4.dstAddr"}
 * @param matchKind one of {@link Kind#EXACT}, {@link Kind#LPM}, {@link Kind#TERNARY},
 *                  {@link Kind#RANGE}, {@link Kind#OPTIONAL}, or
 *                  {@link Kind#UNSPECIFIED}
 * @param bitWidth  width of the field as declared in the P4 program (e.g. 32 for
 *                  IPv4, 48 for MAC)
 *
 * @since 0.1.0
 */
public record MatchFieldInfo(int id, String name, Kind matchKind, int bitWidth) {

    /**
     * Mirrors P4Runtime's {@code MatchField.MatchType} enum at the public-API layer
     * so callers don't depend on generated protobuf types.
     */
    public enum Kind {
        UNSPECIFIED,
        EXACT,
        LPM,
        TERNARY,
        RANGE,
        OPTIONAL
    }
}
