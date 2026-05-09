package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the {@link ReadQuery} interface contract. Real
 * filter behaviour is exercised against a live BMv2 in
 * {@code integration/ReadRpcTest}; this file covers the parts that do not
 * require a switch — currently the {@link ReadQuery#where(Predicate)}
 * default-method fallback.
 */
class ReadQueryTest {

    /**
     * The default {@code where} method on the {@link ReadQuery} interface should
     * throw {@link UnsupportedOperationException} for any implementation that
     * does not override it (for example, a pre-1.1.0 implementation that
     * predates the addition of {@code where}). The exception message must name
     * the version and tell the caller to update the implementation.
     */
    @Test
    void defaultWhereThrowsUnsupportedOperationException() {
        ReadQuery legacyImpl = new ReadQuery() {
            @Override public ReadQuery match(String fieldName, Bytes value) { return this; }
            @Override public ReadQuery match(String fieldName, Mac value) { return this; }
            @Override public ReadQuery match(String fieldName, Ip4 value) { return this; }
            @Override public ReadQuery match(String fieldName, Ip6 value) { return this; }
            @Override public ReadQuery match(String fieldName, int value) { return this; }
            @Override public ReadQuery match(String fieldName, long value) { return this; }
            @Override public ReadQuery match(String fieldName, byte[] value) { return this; }
            @Override public ReadQuery match(String fieldName, Match match) { return this; }
            @Override public List<TableEntry> all() { return List.of(); }
            @Override public Optional<TableEntry> one() { return Optional.empty(); }
            @Override public Stream<TableEntry> stream() { return Stream.empty(); }
            @Override public CompletableFuture<List<TableEntry>> allAsync() {
                return CompletableFuture.completedFuture(List.of());
            }
            @Override public CompletableFuture<Optional<TableEntry>> oneAsync() {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            // Note: where(Predicate) is NOT overridden; default applies.
        };

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> legacyImpl.where(e -> true));
        assertTrue(ex.getMessage().contains("1.1.0"),
                "default where() message must mention the 1.1.0 addition; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("updated implementation"),
                "default where() message must direct the caller to update the implementation; was: " + ex.getMessage());
    }
}
