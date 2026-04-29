package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.UpdateFailure;

import java.util.List;

/**
 * Outcome of a batch {@code Write}. {@link #submitted()} is the number of updates the
 * client sent; {@link #failures()} carries any per-update rejections reported by the
 * device, with each failure's original batch index. A whole-RPC error (transport
 * failure, mastership lost, etc.) does NOT surface here — those throw
 * {@code P4ConnectionException} instead.
 */
public record WriteResult(int submitted, List<UpdateFailure> failures) {

    public WriteResult {
        if (submitted < 0) {
            throw new IllegalArgumentException("submitted must be >= 0, got " + submitted);
        }
        failures = List.copyOf(failures);
    }

    public boolean allSucceeded() {
        return failures.isEmpty();
    }
}
