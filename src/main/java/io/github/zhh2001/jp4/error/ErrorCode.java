package io.github.zhh2001.jp4.error;

/**
 * Mirrors the canonical gRPC status codes that P4Runtime uses to report per-update
 * outcomes. Names match {@code io.grpc.Status.Code} so callers familiar with gRPC
 * recognise them immediately.
 *
 * @since 0.1.0
 */
public enum ErrorCode {
    OK,
    CANCELLED,
    UNKNOWN,
    INVALID_ARGUMENT,
    DEADLINE_EXCEEDED,
    NOT_FOUND,
    ALREADY_EXISTS,
    PERMISSION_DENIED,
    UNAUTHENTICATED,
    RESOURCE_EXHAUSTED,
    FAILED_PRECONDITION,
    ABORTED,
    OUT_OF_RANGE,
    UNIMPLEMENTED,
    INTERNAL,
    UNAVAILABLE,
    DATA_LOSS;

    /**
     * Maps a numeric gRPC code (as defined by {@code google.rpc.Code}) to the matching
     * enum value. Returns {@link #UNKNOWN} for any value not in the canonical set.
     */
    public static ErrorCode fromGrpcCode(int code) {
        ErrorCode[] values = values();
        return (code >= 0 && code < values.length) ? values[code] : UNKNOWN;
    }
}
