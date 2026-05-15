package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.MulticastGroupEntry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request against multicast
 * groups on the device's packet replication engine, returned by
 * {@code P4Switch.readMulticastGroup()}. Unlike the table-driven read
 * APIs ({@link CounterReadQuery}, {@link MeterReadQuery},
 * {@link RegisterReadQuery}, {@link ActionProfileMemberReadQuery},
 * {@link ActionProfileGroupReadQuery}), this query takes no P4 name —
 * the packet replication engine is program-agnostic and multicast
 * groups are addressed by a controller-assigned numeric id only.
 *
 * <p>Optionally narrow the read with {@link #groupId(long)}
 * (server-side filter via the wire {@code multicast_group_id} field)
 * and/or {@link #where(Predicate)} (client-side filter applied after
 * fetch), then call a terminal: {@link #all()}, {@link #one()}, or
 * {@link #stream()}.
 *
 * <p>An empty filter set means "every multicast group programmed on
 * the device". Group-id filtering happens on the device;
 * {@code where} filtering happens on the client after the response
 * has been received, the same shape
 * {@link CounterReadQuery#where(Predicate)} and its siblings use.
 *
 * <p>Unlike {@link ReadQuery#where}, this interface's {@link #where}
 * method has no default body — the interface is new in 1.5 and there
 * is no legacy implementer to keep working through a default. The
 * {@code MulticastGroupReadQueryImpl} returned by
 * {@code P4Switch.readMulticastGroup} is the canonical implementation.
 *
 * <p>Threading model mirrors {@link ActionProfileGroupReadQuery}
 * exactly: terminal operations are dispatched on the switch's outbound
 * executor; results return to the calling thread. A
 * {@code MulticastGroupReadQuery} instance is a mutable builder;
 * confine to a single thread.
 *
 * @since 1.5.0
 */
public interface MulticastGroupReadQuery {

    /**
     * Restricts the read to the multicast group with the given id.
     * Default (unset) reads every group. Setting a second value
     * replaces the first.
     */
    MulticastGroupReadQuery groupId(long multicastGroupId);

    /**
     * Adds a client-side predicate that narrows the result of a subsequent
     * terminal call. Each call appends a predicate; entries that fail any
     * predicate are excluded.
     *
     * @throws NullPointerException if {@code filter} is null
     */
    MulticastGroupReadQuery where(Predicate<? super MulticastGroupEntry> filter);

    /** Reads every matching group into a list. */
    List<MulticastGroupEntry> all();

    /**
     * Reads at most one group. Throws {@code P4OperationException} if the
     * query matches more than one — intended for fully-qualified group-id
     * reads where the result is 0 or 1.
     */
    Optional<MulticastGroupEntry> one();

    /**
     * Streams matching groups; the underlying gRPC iterator closes when the
     * stream closes. Always use with try-with-resources.
     */
    Stream<MulticastGroupEntry> stream();

    CompletableFuture<List<MulticastGroupEntry>> allAsync();
    CompletableFuture<Optional<MulticastGroupEntry>> oneAsync();
}
