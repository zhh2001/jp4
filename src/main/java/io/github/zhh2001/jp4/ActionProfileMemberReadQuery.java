package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.ActionProfileMember;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request against the members of
 * an action profile, returned by
 * {@code P4Switch.readActionProfileMember(String)}. Optionally narrow
 * the read with {@link #memberId(long)} (server-side filter via the
 * wire {@code member_id} field) and/or {@link #where(Predicate)}
 * (client-side filter applied after fetch), then call a terminal:
 * {@link #all()}, {@link #one()}, or {@link #stream()}.
 *
 * <p>An empty filter set means "every member in the action profile".
 * Member-id filtering happens on the device; {@code where} filtering
 * happens on the client after the response has been received, the same
 * shape {@link CounterReadQuery#where(Predicate)} and its siblings use.
 *
 * <p>Unlike {@link ReadQuery#where}, this interface's {@link #where}
 * method has no default body — the interface is new in 1.4 and there is
 * no legacy implementer to keep working through a default. The
 * {@code ActionProfileMemberReadQueryImpl} returned by
 * {@code P4Switch.readActionProfileMember} is the canonical
 * implementation.
 *
 * <p>Threading model mirrors {@link RegisterReadQuery} exactly: terminal
 * operations are dispatched on the switch's outbound executor; results
 * return to the calling thread. A {@code ActionProfileMemberReadQuery}
 * instance is a mutable builder; confine to a single thread.
 *
 * @since 1.4.0
 */
public interface ActionProfileMemberReadQuery {

    /**
     * Restricts the read to the member with the given id. Default
     * (unset) reads every member. Setting a second value replaces the
     * first.
     */
    ActionProfileMemberReadQuery memberId(long memberId);

    /**
     * Adds a client-side predicate that narrows the result of a subsequent
     * terminal call. Each call appends a predicate; entries that fail any
     * predicate are excluded.
     *
     * @throws NullPointerException if {@code filter} is null
     */
    ActionProfileMemberReadQuery where(Predicate<? super ActionProfileMember> filter);

    /** Reads every matching member into a list. */
    List<ActionProfileMember> all();

    /**
     * Reads at most one member. Throws {@code P4OperationException} if the
     * query matches more than one — intended for fully-qualified member-id
     * reads where the result is 0 or 1.
     */
    Optional<ActionProfileMember> one();

    /**
     * Streams matching members; the underlying gRPC iterator closes when the
     * stream closes. Always use with try-with-resources.
     */
    Stream<ActionProfileMember> stream();

    CompletableFuture<List<ActionProfileMember>> allAsync();
    CompletableFuture<Optional<ActionProfileMember>> oneAsync();
}
