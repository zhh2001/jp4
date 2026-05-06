/**
 * jp4's exception hierarchy. Three concrete subclasses of
 * {@link io.github.zhh2001.jp4.error.P4RuntimeException} cover the
 * three failure layers a controller actually distinguishes:
 *
 * <ul>
 *   <li>{@link io.github.zhh2001.jp4.error.P4ConnectionException} —
 *       transport / mastership / closed-switch problems. Subclass
 *       {@link io.github.zhh2001.jp4.error.P4ArbitrationLost} fires
 *       when the device denies primary on a connect or re-claim.</li>
 *   <li>{@link io.github.zhh2001.jp4.error.P4PipelineException} —
 *       schema problems. Misspelled table / field / action / param
 *       names, no pipeline bound, value too wide for the declared
 *       bit width, match-kind mismatch, action not in the table's
 *       action set. Carries known-list error messages naming the
 *       candidate set.</li>
 *   <li>{@link io.github.zhh2001.jp4.error.P4OperationException} —
 *       device-side rejection of a Write or Read RPC. Carries
 *       {@link io.github.zhh2001.jp4.error.OperationType},
 *       {@link io.github.zhh2001.jp4.error.ErrorCode}, and (for batch
 *       writes) per-update {@code UpdateFailure} entries.</li>
 * </ul>
 *
 * <p>Async methods complete the returned {@code CompletableFuture}
 * exceptionally with the same exception type the sync method would
 * have thrown — a uniform contract: methods that return a future
 * report failures through the future, never by throwing on the
 * calling thread.
 *
 * <p>See {@code docs/error-handling.md} for guidance on which
 * exception fires when.
 *
 * @since 0.1.0
 */
package io.github.zhh2001.jp4.error;
