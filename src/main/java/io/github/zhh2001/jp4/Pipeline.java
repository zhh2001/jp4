package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;

/**
 * Snapshot of the {@code P4Info} and {@code DeviceConfig} bound to a {@code P4Switch}.
 * Returned by {@code P4Switch.pipeline()}; immutable.
 *
 * @since 0.1.0
 */
public record Pipeline(P4Info p4info, DeviceConfig deviceConfig) {
}
