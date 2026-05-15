package io.github.zhh2001.jp4.entity;

import java.util.List;
import java.util.Objects;

/**
 * One {@code ActionProfileGroup} returned by
 * {@code P4Switch.readActionProfileGroup}. The record carries the
 * owning action profile's fully-qualified P4 name, the group id within
 * that profile, the device-side maximum number of weighted members
 * (zero when the static {@code max_group_size} from P4Info should
 * apply), and the ordered list of weighted members the group dispatches
 * across.
 *
 * <p>The members list is captured through {@link List#copyOf} so that
 * post-construction mutations of the caller's list do not affect the
 * record and the exposed view itself refuses mutation.
 *
 * @param actionProfileName the action profile's fully-qualified P4 name
 *                          (resolved from the wire
 *                          {@code action_profile_id} during read
 *                          response parsing); never {@code null}
 * @param groupId           the group id, as assigned by the controller
 *                          when the group was inserted
 * @param maxSize           the per-group maximum number of weighted
 *                          members the device will enforce; zero means
 *                          the static {@code max_group_size} from
 *                          P4Info applies instead
 * @param members           ordered list of weighted members the group
 *                          dispatches across; never {@code null}, may
 *                          be empty
 * @since 1.4.0
 */
public record ActionProfileGroup(
        String actionProfileName,
        long groupId,
        int maxSize,
        List<WeightedMember> members
) {

    public ActionProfileGroup {
        Objects.requireNonNull(actionProfileName, "actionProfileName");
        Objects.requireNonNull(members, "members");
        members = List.copyOf(members);
    }
}
