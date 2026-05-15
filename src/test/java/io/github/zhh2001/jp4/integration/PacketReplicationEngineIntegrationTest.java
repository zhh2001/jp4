package io.github.zhh2001.jp4.integration;

import com.google.protobuf.ByteString;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.CloneSessionEntry;
import io.github.zhh2001.jp4.entity.MulticastGroupEntry;
import io.github.zhh2001.jp4.entity.Replica;
import io.github.zhh2001.jp4.error.P4OperationException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry;
import p4.v1.P4RuntimeOuterClass.Uint128;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.WriteRequest;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end integration coverage for the packet replication engine read
 * APIs added in {@code b893a53} (multicast group) and {@code f709b5a}
 * (clone session). The PRE family is program-agnostic, so this class
 * binds the existing {@code counters_meters_registers_groups.p4}
 * pipeline solely to satisfy the pipeline-bound gate; the multicast
 * groups and clone sessions are installed at runtime via a parallel
 * P4Runtime stub Write (jp4 v1.5 has no public write API for either).
 */
class PacketReplicationEngineIntegrationTest {

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;
    private static ManagedChannel rawChannel;
    private static P4RuntimeGrpc.P4RuntimeBlockingStub rawStub;
    private static final long ELECTION_ID = 1L;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("PacketReplicationEngineIntegrationTest").start();
        P4Info p4info = P4Info.fromFile(
                Path.of("src/test/resources/p4/counters_meters_registers_groups.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(
                Path.of("src/test/resources/p4/counters_meters_registers_groups.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);

        rawChannel = NettyChannelBuilder.forTarget(bmv2.grpcAddress())
                .usePlaintext()
                .build();
        rawStub = P4RuntimeGrpc.newBlockingStub(rawChannel);
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (rawChannel != null) rawChannel.shutdownNow();
        if (bmv2 != null) bmv2.close();
    }

    @Test
    void readMulticastGroupAfterInsertReturnsTheGroup() {
        int groupId = 50;
        byte[] portBytes = new byte[]{0x05};   // canonical-bytestring per P4Runtime 1.3+ encoding
        try {
            seedMulticastGroup(groupId, portBytes, /*instance*/ 1);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIMPLEMENTED")) {
                Assumptions.assumeTrue(false,
                        "BMv2 does not implement MulticastGroupEntry Write: " + e.getMessage());
            }
            throw e;
        }

        List<MulticastGroupEntry> groups;
        try {
            groups = sw.readMulticastGroup().all();
        } catch (P4OperationException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIMPLEMENTED")) {
                Assumptions.assumeTrue(false,
                        "BMv2 does not implement MulticastGroupEntry Read: " + e.getMessage());
            }
            throw e;
        }

        assertEquals(1, groups.size(),
                "exactly one multicast group should be present after the INSERT");
        MulticastGroupEntry g = groups.get(0);
        assertEquals(groupId, g.multicastGroupId());
        assertEquals(1, g.replicas().size());
        Replica r = g.replicas().get(0);
        assertNotNull(r.port(), "replica port should round-trip as non-null after wire write");
        assertArrayEquals(portBytes, r.port().toByteArray());
        assertEquals(1, r.instance());
    }

    @Test
    void readCloneSessionAfterInsertReturnsTheSession() {
        int sessionId = 60;
        byte[] portBytes = new byte[]{0x07};   // canonical-bytestring
        try {
            seedCloneSession(sessionId, portBytes, /*instance*/ 2, /*classOfService*/ 0,
                    /*packetLengthBytes*/ 0);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIMPLEMENTED")) {
                Assumptions.assumeTrue(false,
                        "BMv2 does not implement CloneSessionEntry Write: " + e.getMessage());
            }
            throw e;
        }

        List<CloneSessionEntry> sessions;
        try {
            sessions = sw.readCloneSession().all();
        } catch (P4OperationException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIMPLEMENTED")) {
                Assumptions.assumeTrue(false,
                        "BMv2 does not implement CloneSessionEntry Read: " + e.getMessage());
            }
            throw e;
        }

        assertEquals(1, sessions.size(),
                "exactly one clone session should be present after the INSERT");
        CloneSessionEntry s = sessions.get(0);
        assertEquals(sessionId, s.sessionId());
        assertEquals(1, s.replicas().size());
        Replica r = s.replicas().get(0);
        assertNotNull(r.port(), "replica port should round-trip as non-null after wire write");
        assertArrayEquals(portBytes, r.port().toByteArray());
        assertEquals(2, r.instance());
    }

    // ---------- raw-stub seeding helpers --------------------------------

    private static void seedMulticastGroup(int groupId, byte[] portBytes, int instance) {
        var replica = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setPort(ByteString.copyFrom(portBytes))
                .setInstance(instance)
                .build();
        var group = p4.v1.P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                .setMulticastGroupId(groupId)
                .addReplicas(replica)
                .build();
        Entity entity = Entity.newBuilder()
                .setPacketReplicationEngineEntry(
                        PacketReplicationEngineEntry.newBuilder()
                                .setMulticastGroupEntry(group)
                                .build())
                .build();
        sendInsert(entity);
    }

    private static void seedCloneSession(int sessionId, byte[] portBytes, int instance,
                                          int classOfService, int packetLengthBytes) {
        var replica = p4.v1.P4RuntimeOuterClass.Replica.newBuilder()
                .setPort(ByteString.copyFrom(portBytes))
                .setInstance(instance)
                .build();
        var session = p4.v1.P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                .setSessionId(sessionId)
                .addReplicas(replica)
                .setClassOfService(classOfService)
                .setPacketLengthBytes(packetLengthBytes)
                .build();
        Entity entity = Entity.newBuilder()
                .setPacketReplicationEngineEntry(
                        PacketReplicationEngineEntry.newBuilder()
                                .setCloneSessionEntry(session)
                                .build())
                .build();
        sendInsert(entity);
    }

    private static void sendInsert(Entity entity) {
        WriteRequest req = WriteRequest.newBuilder()
                .setDeviceId(0L)
                .setElectionId(Uint128.newBuilder()
                        .setHigh(0L)
                        .setLow(ELECTION_ID)
                        .build())
                .addUpdates(Update.newBuilder()
                        .setType(Update.Type.INSERT)
                        .setEntity(entity)
                        .build())
                .build();
        rawStub.write(req);
    }
}
