package io.github.zhh2001.jp4.pipeline;

/**
 * The {@code action} field of {@code SetForwardingPipelineConfigRequest}. v0.1 exposes
 * only the two values in active use; the remaining spec values
 * ({@code VERIFY}, {@code VERIFY_AND_SAVE}, {@code COMMIT}) will be added when a real
 * use case appears.
 */
public enum PipelineAction {
    /** Verify the pipeline and atomically activate it. The default for first push. */
    VERIFY_AND_COMMIT,
    /** Used internally on auto-reconnect to re-apply the bound pipeline without disrupting state. */
    RECONCILE_AND_COMMIT
}
