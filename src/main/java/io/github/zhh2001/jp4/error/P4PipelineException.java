package io.github.zhh2001.jp4.error;

/**
 * Raised when a pipeline operation fails because of a configuration mismatch or a
 * structural problem in user-supplied data. Covers:
 * <ul>
 *   <li>{@code SetForwardingPipelineConfig} / {@code GetForwardingPipelineConfig} RPC
 *       failure.</li>
 *   <li>Parse error reading a P4Info file.</li>
 *   <li>An entry references a table, action, match field, or action parameter not
 *       declared in the bound P4Info.</li>
 *   <li>An entry's match kind does not match the table's declared kind for that
 *       field, or the value width exceeds the field's bit width.</li>
 * </ul>
 *
 * <p>These are usually programmer errors (mismatched p4info / .json, typos in names)
 * rather than runtime conditions. They are reported separately from
 * {@link P4OperationException} so callers can route them to a different handler.
 */
public class P4PipelineException extends P4RuntimeException {

    public P4PipelineException(String message) {
        super(message);
    }

    public P4PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
