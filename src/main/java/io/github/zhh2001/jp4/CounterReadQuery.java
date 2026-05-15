package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.CounterEntry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request against a counter array,
 * returned by {@code P4Switch.readCounter(String)}. Optionally narrow the
 * read with {@link #index(long)} (server-side filter via the wire
 * {@code Index} field) and/or {@link #where(Predicate)} (client-side filter
 * applied after fetch), then call a terminal: {@link #all()}, {@link #one()},
 * or {@link #stream()}.
 *
 * <p>An empty filter set means "every cell in the counter array". Index
 * filtering happens on the device; {@code where} filtering happens on the
 * client after the response has been received, the same shape
 * {@link ReadQuery#where(Predicate)} uses for table reads.
 *
 * <p>Unlike {@link ReadQuery#where}, this interface's {@link #where} method
 * has no default body — the interface is new in 1.4 and there is no legacy
 * implementer to keep working through a default. The
 * {@code CounterReadQueryImpl} returned by {@code P4Switch.readCounter} is
 * the canonical implementation.
 *
 * <p>Threading model mirrors {@link ReadQuery} exactly: terminal operations
 * are dispatched on the switch's outbound executor; results return to the
 * calling thread. A {@code CounterReadQuery} instance is a mutable builder;
 * confine to a single thread.
 *
 * @since 1.4.0
 */
public interface CounterReadQuery {

    /**
     * Restricts the read to the cell at the given array index. Default
     * (unset) reads every cell. Setting a second value replaces the first.
     */
    CounterReadQuery index(long index);

    /**
     * Adds a client-side predicate that narrows the result of a subsequent
     * terminal call. Each call appends a predicate; entries that fail any
     * predicate are excluded.
     *
     * @throws NullPointerException if {@code filter} is null
     */
    CounterReadQuery where(Predicate<? super CounterEntry> filter);

    /** Reads every matching cell into a list. */
    List<CounterEntry> all();

    /**
     * Reads at most one cell. Throws {@code P4OperationException} if the
     * query matches more than one — intended for fully-qualified index
     * reads where the result is 0 or 1.
     */
    Optional<CounterEntry> one();

    /**
     * Streams matching cells; the underlying gRPC iterator closes when the
     * stream closes. Always use with try-with-resources.
     */
    Stream<CounterEntry> stream();

    CompletableFuture<List<CounterEntry>> allAsync();
    CompletableFuture<Optional<CounterEntry>> oneAsync();
}
