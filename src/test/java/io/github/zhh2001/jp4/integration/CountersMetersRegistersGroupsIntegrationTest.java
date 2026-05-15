package io.github.zhh2001.jp4.integration;

import com.google.protobuf.ByteString;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.ActionProfileGroup;
import io.github.zhh2001.jp4.entity.ActionProfileMember;
import io.github.zhh2001.jp4.entity.CounterEntry;
import io.github.zhh2001.jp4.entity.MeterEntry;
import io.github.zhh2001.jp4.entity.RegisterEntry;
import io.github.zhh2001.jp4.entity.WeightedMember;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.testsupport.BMv2TestSupport;
import io.github.zhh2001.jp4.error.P4OperationException;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import p4.v1.P4DataOuterClass.P4Data;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.Action;
import p4.v1.P4RuntimeOuterClass.ActionProfileGroup.Member;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.Uint128;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.WriteRequest;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end integration coverage for the five read API families
 * landed by {@code de3e031} (counter), {@code e29bd3e} (meter),
 * {@code dd152cf} (register), and {@code 6e43a4b} (action-profile
 * member + group). The unit-test classes for each family use an
 * in-process gRPC fake; this class exercises the same APIs against a
 * real {@code simple_switch_grpc} (BMv2) instance running the
 * {@code counters_meters_registers_groups.p4} pipeline.
 *
 * <p>Counter / meter / register reads run against the freshly-loaded
 * zero-state device — BMv2 reports every cell in the indirect array,
 * including the cells the data plane has never touched, with default
 * values. Action-profile member / group reads need at least one entry
 * present to exercise the response parser; the two relevant tests
 * seed device state through a raw P4Runtime stub Write before reading
 * through jp4. jp4 v1.4 has no public write API for those entities,
 * so the stub is the conventional integration-test workaround until a
 * future write-side release.
 */
class CountersMetersRegistersGroupsIntegrationTest {

    private static final String COUNTER_NAME  = "MyIngress.my_counter";
    private static final String METER_NAME    = "MyIngress.my_meter";
    private static final String REGISTER_NAME = "MyIngress.my_register";
    private static final String SELECTOR_NAME = "MyIngress.my_selector";
    private static final int    SELECTOR_ID   = 287486194;
    private static final int    SET_EGRESS_ID = 25797781;
    private static final int    PORT_PARAM_ID = 1;

    private static final int COUNTER_SIZE  = 64;
    private static final int METER_SIZE    = 32;
    private static final int REGISTER_SIZE = 16;

    private static BMv2TestSupport bmv2;
    private static P4Switch sw;
    private static ManagedChannel rawChannel;
    private static P4RuntimeGrpc.P4RuntimeBlockingStub rawStub;
    private static long electionId;

    @BeforeAll
    static void start() throws Exception {
        BMv2TestSupport.checkEnvironment();
        bmv2 = new BMv2TestSupport("CountersMetersRegistersGroupsIntegrationTest").start();
        P4Info p4info = P4Info.fromFile(
                Path.of("src/test/resources/p4/counters_meters_registers_groups.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(
                Path.of("src/test/resources/p4/counters_meters_registers_groups.json"));
        sw = P4Switch.connectAsPrimary(bmv2.grpcAddress()).bindPipeline(p4info, dc);

        // Parallel stub used only for seeding device state for the action-profile
        // member / group tests; never used for read-side assertions.
        rawChannel = NettyChannelBuilder.forTarget(bmv2.grpcAddress())
                .usePlaintext()
                .build();
        rawStub = P4RuntimeGrpc.newBlockingStub(rawChannel);
        // jp4 picks election ids monotonically; we reuse one that we know it
        // has already mastered on this channel (it is the primary).
        electionId = 1L;
    }

    @AfterAll
    static void stop() {
        if (sw != null) sw.close();
        if (rawChannel != null) rawChannel.shutdownNow();
        if (bmv2 != null) bmv2.close();
    }

    @Test
    void readCounterReturnsAllCellsInitiallyZero() {
        List<CounterEntry> entries = sw.readCounter(COUNTER_NAME).all();
        assertEquals(COUNTER_SIZE, entries.size(),
                "BMv2 should return every counter cell, even the unwritten ones");
        for (CounterEntry e : entries) {
            assertEquals(COUNTER_NAME, e.counterName());
            assertEquals(0L, e.packetCount(),
                    "freshly-loaded counter cell " + e.index() + " should report zero packets");
            assertEquals(0L, e.byteCount(),
                    "freshly-loaded counter cell " + e.index() + " should report zero bytes");
        }
    }

    @Test
    void readMeterReturnsAllCellsInitiallyDefault() {
        List<MeterEntry> entries = sw.readMeter(METER_NAME).all();
        assertEquals(METER_SIZE, entries.size(),
                "BMv2 should return every meter cell, even the unwritten ones");
        for (MeterEntry e : entries) {
            assertEquals(METER_NAME, e.meterName());
            assertNotNull(e.config(),
                    "MeterConfig should never be null on the read path");
            assertNotNull(e.counterData(),
                    "MeterCounterData should never be null on the read path");
        }
    }

    @Test
    void readRegisterReturnsAllCellsInitiallyZero() throws Exception {
        List<RegisterEntry> entries;
        try {
            entries = sw.readRegister(REGISTER_NAME).all();
        } catch (P4OperationException e) {
            // BMv2 simple_switch_grpc 1.15.1 returns
            // "UNIMPLEMENTED: Register reads are not supported yet" for the
            // RegisterEntry read RPC. The P4Runtime spec permits the server
            // to refuse a read with UNIMPLEMENTED, and that is what BMv2
            // currently does for indirect-register cells. The jp4 read API
            // is exercised end-to-end by the unit test in
            // P4SwitchReadRegisterTest; this integration test will start
            // running automatically once BMv2 adds register-read support.
            if (e.getMessage() != null && e.getMessage().contains("UNIMPLEMENTED")) {
                Assumptions.assumeTrue(false,
                        "BMv2 does not yet implement register reads: " + e.getMessage());
            }
            throw e;
        }
        assertEquals(REGISTER_SIZE, entries.size(),
                "BMv2 should return every register cell, even the unwritten ones");
        for (RegisterEntry e : entries) {
            assertEquals(REGISTER_NAME, e.registerName());
            // Decode the serialised P4Data — the convention RegisterInfo
            // promised in a280a1e — and assert the bit<32> value is zero.
            P4Data decoded = P4Data.parseFrom(e.data().toByteArray());
            byte[] bits = decoded.getBitstring().toByteArray();
            for (byte b : bits) {
                assertEquals((byte) 0, b,
                        "freshly-loaded register cell " + e.index()
                                + " should hold zero in every byte; got " + bits.length + " bytes");
            }
        }
    }

    @Test
    void readActionProfileMemberReturnsInsertedMember() {
        int memberId = 42;
        // The P4Runtime 1.3+ canonical-bytestring rule strips leading zero
        // bytes, so a 9-bit port value of 5 round-trips as a single byte.
        // Send the canonical form to match what BMv2 stores and returns.
        byte[] portBytes = new byte[]{0x05};
        seedMember(memberId, portBytes);

        List<ActionProfileMember> members = sw.readActionProfileMember(SELECTOR_NAME).all();
        assertEquals(1, members.size(),
                "exactly one member should be present after the INSERT");
        ActionProfileMember m = members.get(0);
        assertEquals(SELECTOR_NAME, m.actionProfileName());
        assertEquals(memberId, m.memberId());
        assertEquals("MyIngress.set_egress", m.action().name());
        assertArrayEquals(portBytes, m.action().param("port").toByteArray(),
                "the canonical set_egress(port=...) parameter should round-trip end-to-end");
    }

    @Test
    void readActionProfileGroupReturnsInsertedGroup() {
        int memberId = 43;
        int groupId  = 7;
        // Canonical-bytestring (P4Runtime 1.3+) — leading zeros stripped.
        byte[] portBytes  = new byte[]{0x06};
        byte[] watchBytes = new byte[]{0x01};
        seedMember(memberId, portBytes);
        seedGroup(groupId, memberId, /*weight*/ 3, watchBytes);

        List<ActionProfileGroup> groups = sw.readActionProfileGroup(SELECTOR_NAME).all();
        assertEquals(1, groups.size(),
                "exactly one group should be present after the INSERT");
        ActionProfileGroup g = groups.get(0);
        assertEquals(SELECTOR_NAME, g.actionProfileName());
        assertEquals(groupId, g.groupId());
        assertEquals(1, g.members().size(), "group should carry one weighted member");
        WeightedMember wm = g.members().get(0);
        assertEquals(memberId, wm.memberId());
        assertEquals(3, wm.weight());
        assertNotNull(wm.watchPort(),
                "watch_port bytes set on the wire should surface as a non-null watchPort");
        assertArrayEquals(watchBytes, wm.watchPort().toByteArray());
    }

    // ---------- raw-stub seeding helpers --------------------------------

    private static void seedMember(int memberId, byte[] portBytes) {
        Action action = Action.newBuilder()
                .setActionId(SET_EGRESS_ID)
                .addParams(Action.Param.newBuilder()
                        .setParamId(PORT_PARAM_ID)
                        .setValue(ByteString.copyFrom(portBytes))
                        .build())
                .build();
        var member = p4.v1.P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(SELECTOR_ID)
                .setMemberId(memberId)
                .setAction(action)
                .build();
        Entity entity = Entity.newBuilder().setActionProfileMember(member).build();
        sendInsert(entity);
    }

    private static void seedGroup(int groupId, int memberId, int weight, byte[] watchBytes) {
        Member slot = Member.newBuilder()
                .setMemberId(memberId)
                .setWeight(weight)
                .setWatchPort(ByteString.copyFrom(watchBytes))
                .build();
        var group = p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(SELECTOR_ID)
                .setGroupId(groupId)
                .setMaxSize(8)
                .addMembers(slot)
                .build();
        Entity entity = Entity.newBuilder().setActionProfileGroup(group).build();
        sendInsert(entity);
    }

    private static void sendInsert(Entity entity) {
        WriteRequest req = WriteRequest.newBuilder()
                .setDeviceId(0L)
                .setElectionId(Uint128.newBuilder()
                        .setHigh(0L)
                        .setLow(electionId)
                        .build())
                .addUpdates(Update.newBuilder()
                        .setType(Update.Type.INSERT)
                        .setEntity(entity)
                        .build())
                .build();
        rawStub.write(req);
    }
}
