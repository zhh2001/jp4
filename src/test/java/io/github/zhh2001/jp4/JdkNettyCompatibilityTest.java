package io.github.zhh2001.jp4;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.Test;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.WriteRequest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Smoke test that exercises the {@code grpc-netty-shaded} runtime end-to-end on the
 * current JVM. Forces Netty initialisation and one real RPC attempt; the RPC is
 * expected to fail with {@code UNAVAILABLE} because the target port has no server.
 *
 * <p>The point is not to verify P4Runtime semantics; it's to verify that JDK 25's
 * stricter rules around {@code sun.misc.Unsafe} and native access do not turn into
 * runtime errors when Netty loads. If this test fails, every later integration test
 * built on real channels would also fail in the same way — better to find out here
 * with a focused signal than at the bottom of an arbitration log.
 */
class JdkNettyCompatibilityTest {

    @Test
    void nettyChannelOpensAndIssuesRpcWithoutRuntimeError() {
        // Port 1 is well-known to have nothing listening on Linux; gRPC reports UNAVAILABLE.
        ManagedChannel channel = NettyChannelBuilder
                .forAddress("127.0.0.1", 1)
                .usePlaintext()
                .build();
        try {
            var stub = P4RuntimeGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS);
            WriteRequest req = WriteRequest.newBuilder().setDeviceId(0).build();
            // We accept any StatusRuntimeException; the only assertion that matters is
            // that *no* JDK-level error (LinkageError, UnsupportedOperationException
            // from a blocked Unsafe call, etc.) escapes from the Netty stack.
            assertThrows(StatusRuntimeException.class, () -> stub.write(req));
        } finally {
            channel.shutdownNow();
        }
    }
}
