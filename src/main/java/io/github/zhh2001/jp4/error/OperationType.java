package io.github.zhh2001.jp4.error;

/**
 * Kind of operation an exception or {@code UpdateFailure} relates to.
 *
 * @since 0.1.0
 */
public enum OperationType {
    INSERT,
    MODIFY,
    DELETE,
    READ
}
