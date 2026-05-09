package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Builder for one P4Runtime {@code Read} request, returned by
 * {@code P4Switch.read(tableName)}. Add zero or more {@code match(...)} filters then
 * call a terminal: {@link #all()}, {@link #one()}, or {@link #stream()}.
 *
 * <p>Empty filter set means "every entry in the table". Partial filters narrow the
 * read on the server side where the kind permits, otherwise client-side filtering is
 * applied transparently.
 *
 * <p>Added in 1.1.0: {@link #where(Predicate)} for arbitrary client-side
 * filtering. Server-side field projection is not supported by the P4Runtime
 * {@code ReadRequest} spec; a future release may add a client-side projection
 * helper.
 *
 * <p>Threading model: terminal operations are dispatched on the switch's
 * outbound executor; results return to the calling thread.
 * <ul>
 *   <li>{@link #all()} / {@link #one()} block the caller until the device
 *       responds, then return on the calling thread.</li>
 *   <li>{@link #allAsync()} / {@link #oneAsync()} return a
 *       {@link CompletableFuture} that completes on the outbound executor;
 *       {@code thenApply} / {@code thenAccept} stages run wherever the caller
 *       dispatches them.</li>
 *   <li>{@link #stream()} initiates the read on the outbound executor but
 *       consumes on the calling thread; multiple stream consumers are
 *       independent.</li>
 *   <li>A {@code ReadQuery} instance is a mutable builder; the typical usage
 *       pattern chains a terminal call (such as {@link #all()}) on the same
 *       thread that constructed the builder. Do not share an in-progress
 *       builder across threads. Once a terminal call returns, the resulting
 *       {@link List}, {@link Optional}, or {@link Stream} is a separate
 *       value, safe to pass across threads per its own thread-safety
 *       contract.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface ReadQuery {

    ReadQuery match(String fieldName, Bytes value);
    ReadQuery match(String fieldName, Mac value);
    ReadQuery match(String fieldName, Ip4 value);
    ReadQuery match(String fieldName, Ip6 value);
    ReadQuery match(String fieldName, int value);
    ReadQuery match(String fieldName, long value);
    ReadQuery match(String fieldName, byte[] value);
    ReadQuery match(String fieldName, Match match);

    /** Reads every matching entry into a list. */
    List<TableEntry> all();

    /**
     * Reads at most one entry. Throws {@code P4OperationException} if the query
     * matches more than one — intended for full-key lookups where the result is 0 or 1.
     */
    Optional<TableEntry> one();

    /**
     * Streams matching entries; the underlying gRPC iterator closes when the stream
     * closes. Always use with try-with-resources.
     */
    Stream<TableEntry> stream();

    CompletableFuture<List<TableEntry>> allAsync();
    CompletableFuture<Optional<TableEntry>> oneAsync();

    /**
     * Adds a client-side filter that narrows the result of a subsequent terminal
     * call ({@link #all()} / {@link #one()} / {@link #stream()} or their async
     * variants). Each call to {@code where} appends a predicate; entries that
     * fail any predicate are excluded from the result. Filters are applied in
     * the order added, after the device's own server-side {@code match(...)}
     * filtering has narrowed the read.
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}; the
     * built-in {@code ReadQuery} returned by
     * {@link io.github.zhh2001.jp4.P4Switch#read(String) P4Switch.read} overrides
     * this with a real implementation.
     *
     * @param filter the predicate to apply; entries where {@code filter.test(e)}
     *               returns {@code false} are excluded
     * @return this builder, for chaining
     * @throws NullPointerException if {@code filter} is null
     * @since 1.1.0
     */
    default ReadQuery where(Predicate<? super TableEntry> filter) {
        throw new UnsupportedOperationException(
                "ReadQuery implementation does not support .where(); "
                        + "this is a 1.1.0 addition and requires an updated implementation.");
    }
}
