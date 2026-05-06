/**
 * P4Info parsing and the read-only schema metadata it produces.
 *
 * <p>The entry point is {@link io.github.zhh2001.jp4.pipeline.P4Info}:
 *
 * <pre>{@code
 * P4Info p4info = P4Info.fromFile(Path.of("…/myprog.p4info.txtpb"));
 * // or:
 * P4Info p4info = P4Info.fromBytes(rawBytes);   // auto-detects binary vs text format
 * }</pre>
 *
 * <p>The parsed {@code P4Info} is then passed to
 * {@link io.github.zhh2001.jp4.P4Switch#bindPipeline} (push to device)
 * or the switch's {@code loadPipeline()} fetches the device's installed
 * P4Info into a {@code P4Info} instance for read-only consumers.
 *
 * <p>Metadata records exposed for inspection:
 * <ul>
 *   <li>{@link io.github.zhh2001.jp4.pipeline.TableInfo} — name, id,
 *       match-field set, allowed action ids, max size.</li>
 *   <li>{@link io.github.zhh2001.jp4.pipeline.MatchFieldInfo} — name,
 *       id, match kind, bit width.</li>
 *   <li>{@link io.github.zhh2001.jp4.pipeline.ActionInfo} +
 *       {@link io.github.zhh2001.jp4.pipeline.ActionParamInfo} — name,
 *       id, params with their bit widths.</li>
 *   <li>{@link io.github.zhh2001.jp4.pipeline.PacketMetadataInfo} — one
 *       field of a {@code controller_packet_metadata} declaration; jp4
 *       uses these to encode {@code PacketOut} and decode {@code PacketIn}.</li>
 *   <li>{@link io.github.zhh2001.jp4.pipeline.DeviceConfig} — sealed
 *       type for the target-specific binary the device executes.
 *       v0.1 ships {@code Bmv2} and {@code Raw}; {@code Tofino} planned
 *       for v0.2.</li>
 * </ul>
 *
 * <p>Each metadata type has both forward (name → entity) and reverse
 * (id → entity) lookups; the latter is what powers PacketIn / read
 * reverse-parsing.
 *
 * @since 0.1.0
 */
package io.github.zhh2001.jp4.pipeline;
