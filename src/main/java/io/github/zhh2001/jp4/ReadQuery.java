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
 * <p>v0.2 will add {@code where(Predicate<TableEntry>)} for arbitrary client-side
 * filtering and {@code fields(...)} for projection. The signatures here are stable;
 * those extensions go on {@code ReadQuery}, not on {@code P4Switch}.
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
}
