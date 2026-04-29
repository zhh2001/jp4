package io.github.zhh2001.jp4.error;

/**
 * Raised when the gRPC transport fails or mastership is unavailable. Covers:
 * <ul>
 *   <li>The remote address is unreachable (refused, DNS, timeout).</li>
 *   <li>The StreamChannel was closed by the server or aborted.</li>
 *   <li>Arbitration completed but our election id did not win primary
 *       (see the {@link P4ArbitrationLost} subclass for that specific case).</li>
 *   <li>The {@code P4Switch} has been closed and an operation was attempted on it.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class P4ConnectionException extends P4RuntimeException {

    public P4ConnectionException(String message) {
        super(message);
    }

    public P4ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
