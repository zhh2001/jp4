package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.ActionProfileGroup;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request against the groups of
 * an action profile, returned by
 * {@code P4Switch.readActionProfileGroup(String)}. Optionally narrow
 * the read with {@link #groupId(long)} (server-side filter via the wire
 * {@code group_id} field) and/or {@link #where(Predicate)} (client-side
 * filter applied after fetch), then call a terminal: {@link #all()},
 * {@link #one()}, or {@link #stream()}.
 *
 * <p>An empty filter set means "every group in the action profile".
 * Group-id filtering happens on the device; {@code where} filtering
 * happens on the client after the response has been received, the same
 * shape {@link ActionProfileMemberReadQuery#where(Predicate)} uses.
 *
 * <p>Unlike {@link ReadQuery#where}, this interface's {@link #where}
 * method has no default body — the interface is new in 1.4 and there is
 * no legacy implementer to keep working through a default. The
 * {@code ActionProfileGroupReadQueryImpl} returned by
 * {@code P4Switch.readActionProfileGroup} is the canonical
 * implementation.
 *
 * <p>Threading model mirrors {@link ActionProfileMemberReadQuery}
 * exactly: terminal operations are dispatched on the switch's outbound
 * executor; results return to the calling thread. A
 * {@code ActionProfileGroupReadQuery} instance is a mutable builder;
 * confine to a single thread.
 *
 * @since 1.4.0
 */
public interface ActionProfileGroupReadQuery {

    /**
     * Restricts the read to the group with the given id. Default
     * (unset) reads every group. Setting a second value replaces the
     * first.
     */
    ActionProfileGroupReadQuery groupId(long groupId);

    /**
     * Adds a client-side predicate that narrows the result of a subsequent
     * terminal call. Each call appends a predicate; entries that fail any
     * predicate are excluded.
     *
     * @throws NullPointerException if {@code filter} is null
     */
    ActionProfileGroupReadQuery where(Predicate<? super ActionProfileGroup> filter);

    /** Reads every matching group into a list. */
    List<ActionProfileGroup> all();

    /**
     * Reads at most one group. Throws {@code P4OperationException} if the
     * query matches more than one — intended for fully-qualified group-id
     * reads where the result is 0 or 1.
     */
    Optional<ActionProfileGroup> one();

    /**
     * Streams matching groups; the underlying gRPC iterator closes when the
     * stream closes. Always use with try-with-resources.
     */
    Stream<ActionProfileGroup> stream();

    CompletableFuture<List<ActionProfileGroup>> allAsync();
    CompletableFuture<Optional<ActionProfileGroup>> oneAsync();
}
