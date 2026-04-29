package io.github.zhh2001.jp4;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GrpcConnectivityTest {

    private Server server;
    private ManagedChannel channel;
    private final String serverName = "test-p4runtime";

    @BeforeEach
    void setUp() throws IOException {
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new FakeP4RuntimeService())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void writeRequestRoundTrips() {
        var stub = P4RuntimeGrpc.newBlockingStub(channel);

        WriteRequest request = WriteRequest.newBuilder()
                .setDeviceId(1)
                .setElectionId(Uint128.newBuilder().setHigh(0).setLow(1))
                .build();

        WriteResponse response = stub.write(request);
        assertNotNull(response);
    }

    @Test
    void readRequestReturnsEmpty() {
        var stub = P4RuntimeGrpc.newBlockingStub(channel);

        ReadRequest request = ReadRequest.newBuilder()
                .setDeviceId(1)
                .build();

        var responses = stub.read(request);
        assertFalse(responses.hasNext());
    }

    private static class FakeP4RuntimeService extends P4RuntimeGrpc.P4RuntimeImplBase {

        @Override
        public void write(WriteRequest request, StreamObserver<WriteResponse> responseObserver) {
            responseObserver.onNext(WriteResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            responseObserver.onCompleted();
        }
    }
}
