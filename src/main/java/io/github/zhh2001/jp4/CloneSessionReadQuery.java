package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.CloneSessionEntry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request against clone
 * sessions on the device's packet replication engine, returned by
 * {@code P4Switch.readCloneSession()}. Like
 * {@link MulticastGroupReadQuery}, this query takes no P4 name — the
 * packet replication engine is program-agnostic and clone sessions
 * are addressed by a controller-assigned numeric id only.
 *
 * <p>Optionally narrow the read with {@link #sessionId(long)}
 * (server-side filter via the wire {@code session_id} field) and/or
 * {@link #where(Predicate)} (client-side filter applied after fetch),
 * then call a terminal: {@link #all()}, {@link #one()}, or
 * {@link #stream()}.
 *
 * <p>An empty filter set means "every clone session programmed on the
 * device". Session-id filtering happens on the device; {@code where}
 * filtering happens on the client after the response has been
 * received, the same shape
 * {@link MulticastGroupReadQuery#where(Predicate)} and its siblings
 * use.
 *
 * <p>Unlike {@link ReadQuery#where}, this interface's {@link #where}
 * method has no default body — the interface is new in 1.5 and there
 * is no legacy implementer to keep working through a default. The
 * {@code CloneSessionReadQueryImpl} returned by
 * {@code P4Switch.readCloneSession} is the canonical implementation.
 *
 * <p>Threading model mirrors {@link MulticastGroupReadQuery} exactly:
 * terminal operations are dispatched on the switch's outbound
 * executor; results return to the calling thread. A
 * {@code CloneSessionReadQuery} instance is a mutable builder; confine
 * to a single thread.
 *
 * @since 1.5.0
 */
public interface CloneSessionReadQuery {

    /**
     * Restricts the read to the clone session with the given id.
     * Default (unset) reads every session. Setting a second value
     * replaces the first.
     */
    CloneSessionReadQuery sessionId(long sessionId);

    /**
     * Adds a client-side predicate that narrows the result of a subsequent
     * terminal call. Each call appends a predicate; entries that fail any
     * predicate are excluded.
     *
     * @throws NullPointerException if {@code filter} is null
     */
    CloneSessionReadQuery where(Predicate<? super CloneSessionEntry> filter);

    /** Reads every matching session into a list. */
    List<CloneSessionEntry> all();

    /**
     * Reads at most one session. Throws {@code P4OperationException} if
     * the query matches more than one — intended for fully-qualified
     * session-id reads where the result is 0 or 1.
     */
    Optional<CloneSessionEntry> one();

    /**
     * Streams matching sessions; the underlying gRPC iterator closes when
     * the stream closes. Always use with try-with-resources.
     */
    Stream<CloneSessionEntry> stream();

    CompletableFuture<List<CloneSessionEntry>> allAsync();
    CompletableFuture<Optional<CloneSessionEntry>> oneAsync();
}
