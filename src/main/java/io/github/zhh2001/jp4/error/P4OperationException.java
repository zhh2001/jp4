package io.github.zhh2001.jp4.error;

import io.github.zhh2001.jp4.entity.UpdateFailure;

import java.util.List;

/**
 * Raised when a Write or Read RPC ran but the device rejected the result. Carries the
 * P4Runtime canonical failure detail:
 * <ul>
 *   <li>{@link #operationType()} — the kind of operation that failed.</li>
 *   <li>{@link #errorCode()} — the gRPC status code returned by the device
 *       (e.g. {@code NOT_FOUND}, {@code ALREADY_EXISTS}, {@code INVALID_ARGUMENT}).</li>
 *   <li>{@link #failures()} — for batch writes, one entry per rejected update with the
 *       original index in the batch; empty for read failures and for whole-RPC errors
 *       (which surface as {@link P4ConnectionException} instead).</li>
 * </ul>
 */
public class P4OperationException extends P4RuntimeException {

    private final OperationType operationType;
    private final ErrorCode errorCode;
    private final List<UpdateFailure> failures;

    public P4OperationException(String message,
                                OperationType operationType,
                                ErrorCode errorCode,
                                List<UpdateFailure> failures) {
        super(message);
        this.operationType = operationType;
        this.errorCode = errorCode;
        this.failures = List.copyOf(failures);
    }

    public OperationType operationType() {
        return operationType;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<UpdateFailure> failures() {
        return failures;
    }
}
