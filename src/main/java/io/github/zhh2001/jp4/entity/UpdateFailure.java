package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.error.ErrorCode;

/**
 * One rejected update inside a batch {@code Write} response. The {@link #index()} is the
 * position of the failed update inside the original batch (0-based), so callers can map
 * back to the {@code TableEntry} that caused it.
 *
 * @since 0.1.0
 */
public record UpdateFailure(int index, ErrorCode code, String message) {
}
