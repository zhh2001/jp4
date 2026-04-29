package io.github.zhh2001.jp4.integration;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Failure modes that don't need a running BMv2. Currently scenario g (unreachable
 * server). Kept as a separate class so it adds zero process-spawn overhead and runs
 * on any developer machine, not just one with BMv2 installed.
 */
class ConnectionFailureTest {

    /**
     * Scenario g: connecting to a port nothing listens on must surface as
     * {@link P4ConnectionException}, not a raw gRPC exception. The arbitration
     * await() inside the connector wraps the gRPC failure for us.
     */
    @Test
    void connectToUnreachablePortThrowsP4ConnectionException() {
        // Port 1 has no listener on Linux; gRPC reports UNAVAILABLE and onError fires
        // on our inbound handler within a few hundred milliseconds.
        assertThrows(P4ConnectionException.class,
                () -> P4Switch.connect("127.0.0.1:1").asPrimary());
    }
}
