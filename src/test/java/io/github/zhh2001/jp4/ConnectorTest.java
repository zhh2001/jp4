package io.github.zhh2001.jp4;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link Connector} configuration that does not require an
 * actual gRPC connection. Live-connect behaviour is in
 * {@code integration/ReconnectTest} and the other integration tests.
 */
class ConnectorTest {

    /**
     * The {@code preserveRoleOnReconnect} flag defaults to {@code false} so the
     * v1.0 silent-Secondary behaviour is preserved for any caller that does not
     * opt into the new fail-fast path.
     */
    @Test
    void preserveRoleOnReconnectDefaultsToFalse() {
        Connector c = P4Switch.connect("localhost:50051");
        assertFalse(c.preserveRoleOnReconnect(),
                "preserveRoleOnReconnect must default to false to preserve v1.0 behaviour");
    }

    /**
     * The setter returns the same {@link Connector} instance so the fluent
     * builder chain composes cleanly. After enabling, the package-private
     * getter reflects the new value.
     */
    @Test
    void preserveRoleOnReconnectSetterReturnsSameConnectorAndStoresValue() {
        Connector c = P4Switch.connect("localhost:50051");
        Connector returned = c.preserveRoleOnReconnect(true);
        assertSame(c, returned, "setter must return this for chaining");
        assertTrue(c.preserveRoleOnReconnect(),
                "value must be stored after setter call");
    }

    /**
     * The {@code packetInFilter} defaults to {@code null} so v1.0 / v1.1 callers
     * see every parsed PacketIn unchanged — no filtering applied unless the
     * caller opts in.
     */
    @Test
    void packetInFilterDefaultsToNull() {
        Connector c = P4Switch.connect("localhost:50051");
        assertNull(c.packetInFilter(),
                "packetInFilter must default to null so no filtering is applied");
    }

    /**
     * The setter returns the same {@link Connector} for chaining, stores the
     * supplied predicate, and rejects {@code null} with
     * {@link NullPointerException} per the project null-rejection convention.
     */
    @Test
    void packetInFilterSetterReturnsSameConnectorAndStoresValueAndRejectsNull() {
        Connector c = P4Switch.connect("localhost:50051");
        Predicate<Object> alwaysTrue = p -> true;
        Connector returned = c.packetInFilter(alwaysTrue);
        assertSame(c, returned, "setter must return this for chaining");
        assertSame(alwaysTrue, c.packetInFilter(),
                "value must be stored after setter call");

        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> c.packetInFilter(null));
        assertEquals("filter", ex.getMessage(),
                "NPE message should name the rejected parameter per Objects.requireNonNull convention");
    }
}
