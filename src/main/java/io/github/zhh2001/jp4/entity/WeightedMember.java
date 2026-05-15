package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

/**
 * One weighted member slot inside an {@link ActionProfileGroup}. The
 * tuple binds an existing {@link ActionProfileMember} (by id) to a
 * weight that controls how often the group selects this member, plus
 * an optional watch port whose link state the device monitors to decide
 * whether the slot is dispatchable.
 *
 * <p>The {@link #watchPort} is the {@code watch_port} bytes variant of
 * the P4Runtime {@code watch_kind} oneof — a device-defined port
 * identifier, in the same byte form {@code Replica.port} uses elsewhere.
 * It is {@code null} in two cases that v1.4 treats identically: when the
 * {@code watch_kind} oneof is unset, and when the deprecated
 * {@code watch} int32 field is set instead. Controllers that need to
 * read the deprecated path can parse the wire {@code ActionProfileGroup}
 * proto directly through the generated class; v1.4 does not surface it
 * on this record.
 *
 * @param memberId  the action-profile member this slot binds to; the
 *                  member must exist in the same action profile
 * @param weight    the selection weight the device applies to this slot
 *                  during dispatch; typically a positive integer
 * @param watchPort device-defined watch port bytes, or {@code null} if
 *                  the {@code watch_kind} oneof is unset or holds the
 *                  deprecated int32 variant
 * @since 1.4.0
 */
public record WeightedMember(long memberId, int weight, Bytes watchPort) {
}
