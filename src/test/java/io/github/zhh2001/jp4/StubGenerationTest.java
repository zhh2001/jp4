package io.github.zhh2001.jp4;

import org.junit.jupiter.api.Test;
import p4.v1.P4RuntimeOuterClass.WriteRequest;
import p4.v1.P4RuntimeOuterClass.ReadRequest;
import p4.v1.P4RuntimeGrpc;
import p4.config.v1.P4InfoOuterClass.P4Info;

import static org.junit.jupiter.api.Assertions.*;

class StubGenerationTest {

    @Test
    void generatedStubsAreAccessible() {
        WriteRequest req = WriteRequest.newBuilder()
                .setDeviceId(1)
                .build();
        assertEquals(1, req.getDeviceId());
    }

    @Test
    void p4InfoClassIsAccessible() {
        P4Info info = P4Info.getDefaultInstance();
        assertNotNull(info);
        assertTrue(info.getTablesList().isEmpty());
    }

    @Test
    void grpcServiceDescriptorIsAvailable() {
        assertNotNull(P4RuntimeGrpc.getServiceDescriptor());
        assertEquals("p4.v1.P4Runtime", P4RuntimeGrpc.getServiceDescriptor().getName());
    }
}
