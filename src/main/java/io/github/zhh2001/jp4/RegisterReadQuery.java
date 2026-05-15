package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.RegisterEntry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request against a register
 * array, returned by {@code P4Switch.readRegister(String)}. Optionally
 * narrow the read with {@link #index(long)} (server-side filter via the
 * wire {@code Index} field) and/or {@link #where(Predicate)} (client-side
 * filter applied after fetch), then call a terminal: {@link #all()},
 * {@link #one()}, or {@link #stream()}.
 *
 * <p>An empty filter set means "every cell in the register array".
 * Index filtering happens on the device; {@code where} filtering happens
 * on the client after the response has been received, the same shape
 * {@link CounterReadQuery#where(Predicate)} and
 * {@link MeterReadQuery#where(Predicate)} use.
 *
 * <p>Unlike {@link ReadQuery#where}, this interface's {@link #where}
 * method has no default body — the interface is new in 1.4 and there is
 * no legacy implementer to keep working through a default. The
 * {@code RegisterReadQueryImpl} returned by
 * {@code P4Switch.readRegister} is the canonical implementation.
 *
 * <p>Threading model mirrors {@link MeterReadQuery} exactly: terminal
 * operations are dispatched on the switch's outbound executor; results
 * return to the calling thread. A {@code RegisterReadQuery} instance is
 * a mutable builder; confine to a single thread.
 *
 * @since 1.4.0
 */
public interface RegisterReadQuery {

    /**
     * Restricts the read to the cell at the given array index. Default
     * (unset) reads every cell. Setting a second value replaces the first.
     */
    RegisterReadQuery index(long index);

    /**
     * Adds a client-side predicate that narrows the result of a subsequent
     * terminal call. Each call appends a predicate; entries that fail any
     * predicate are excluded.
     *
     * @throws NullPointerException if {@code filter} is null
     */
    RegisterReadQuery where(Predicate<? super RegisterEntry> filter);

    /** Reads every matching cell into a list. */
    List<RegisterEntry> all();

    /**
     * Reads at most one cell. Throws {@code P4OperationException} if the
     * query matches more than one — intended for fully-qualified index
     * reads where the result is 0 or 1.
     */
    Optional<RegisterEntry> one();

    /**
     * Streams matching cells; the underlying gRPC iterator closes when the
     * stream closes. Always use with try-with-resources.
     */
    Stream<RegisterEntry> stream();

    CompletableFuture<List<RegisterEntry>> allAsync();
    CompletableFuture<Optional<RegisterEntry>> oneAsync();
}
