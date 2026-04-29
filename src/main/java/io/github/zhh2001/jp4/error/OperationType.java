package io.github.zhh2001.jp4.error;

/**
 * Kind of operation an exception or {@code UpdateFailure} relates to.
 */
public enum OperationType {
    INSERT,
    MODIFY,
    DELETE,
    READ
}
