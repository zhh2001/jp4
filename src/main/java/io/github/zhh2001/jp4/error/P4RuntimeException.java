package io.github.zhh2001.jp4.error;

/**
 * Base type for every exception raised by jp4. Catch this when you need a single
 * site to handle all jp4 failures regardless of category; otherwise prefer one of
 * the three subclasses ({@link P4ConnectionException}, {@link P4PipelineException},
 * {@link P4OperationException}).
 *
 * <p>All jp4 exceptions are unchecked.
 */
public class P4RuntimeException extends RuntimeException {

    public P4RuntimeException(String message) {
        super(message);
    }

    public P4RuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
