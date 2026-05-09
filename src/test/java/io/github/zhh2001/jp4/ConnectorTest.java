package io.github.zhh2001.jp4;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
}
