/**
 * Data carriers — the immutable value types passed between the user's
 * controller and the {@link io.github.zhh2001.jp4.P4Switch} surface.
 *
 * <p>Every type in this package is constructed via a fluent builder or
 * factory and stays immutable thereafter. The same {@code TableEntry}
 * instance can be inserted into different switches sharing a pipeline,
 * the same {@code PacketOut} sent multiple times.
 *
 * <ul>
 *   <li>{@link io.github.zhh2001.jp4.entity.TableEntry} +
 *       {@link io.github.zhh2001.jp4.entity.TableEntryBuilder} +
 *       {@link io.github.zhh2001.jp4.entity.ActionInstance} — the read /
 *       write surface for table entries. Built via
 *       {@code TableEntry.in("table.name").match(...).action(...).param(...).build()}.</li>
 *   <li>{@link io.github.zhh2001.jp4.entity.PacketIn} — inbound packet
 *       delivered by the device, carrying payload + named metadata.</li>
 *   <li>{@link io.github.zhh2001.jp4.entity.PacketOut} +
 *       {@code PacketOut.builder()} — outbound packet sent over the
 *       StreamChannel. Field names are validated against the bound
 *       P4Info at switch-op time.</li>
 *   <li>{@link io.github.zhh2001.jp4.entity.UpdateFailure} — one rejected
 *       update inside a batch Write, carrying the original batch index,
 *       the gRPC code, and the device's message.</li>
 * </ul>
 *
 * <p>Match-key kinds ({@code Match.Exact} / {@code Match.Lpm} /
 * {@code Match.Ternary} / {@code Match.Range} / {@code Match.Optional})
 * are a sealed type in {@code io.github.zhh2001.jp4.match}; primitive
 * value types (Bytes / Mac / Ip4 / Ip6 / ElectionId) are in
 * {@code io.github.zhh2001.jp4.types}.
 *
 * @since 0.1.0
 */
package io.github.zhh2001.jp4.entity;
