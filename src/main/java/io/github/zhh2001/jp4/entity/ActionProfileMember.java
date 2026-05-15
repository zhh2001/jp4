package io.github.zhh2001.jp4.entity;

import java.util.Objects;

/**
 * One {@code ActionProfileMember} returned by
 * {@code P4Switch.readActionProfileMember}. The record carries the
 * owning action profile's fully-qualified P4 name, the member id within
 * that profile, and the action bound to this member.
 *
 * <p>The {@link #action} field reuses the existing {@link ActionInstance}
 * value type, the same shape table-entry reads return for a direct
 * action. An action-profile member is, in effect, a named action
 * instance that one or more selector-driven tables can dispatch to.
 *
 * <p>Records are immutable. The canonical constructor rejects null in
 * every reference component; the {@code long} member id has no null
 * surface.
 *
 * @param actionProfileName the action profile's fully-qualified P4 name
 *                          (resolved from the wire
 *                          {@code action_profile_id} during read
 *                          response parsing); never {@code null}
 * @param memberId          the member id, as assigned by the controller
 *                          when the member was inserted
 * @param action            the action bound to this member; never
 *                          {@code null}
 * @since 1.4.0
 */
public record ActionProfileMember(
        String actionProfileName,
        long memberId,
        ActionInstance action
) {

    public ActionProfileMember {
        Objects.requireNonNull(actionProfileName, "actionProfileName");
        Objects.requireNonNull(action, "action");
    }
}
