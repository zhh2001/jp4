package io.github.zhh2001.jp4;

import com.google.protobuf.ByteString;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Wire-codec round-trip tests for {@link EntryProto}, focused on the
 * {@code idle_timeout_ns} field added in 1.3. {@code EntryProto} is
 * package-private; this test sits in the same package so it can call
 * the static converters directly.
 *
 * <p>The fixtures use {@code basic.p4info.txtpb} to keep table /
 * action / match-field ids stable across runs without needing a real
 * BMv2 device.
 */
class EntryProtoTest {

    private static final P4Info P4INFO = P4Info.fromFile(
            Path.of("src/test/resources/p4/basic.p4info.txtpb"));

    /**
     * A {@link TableEntry} built with a positive {@code idleTimeoutNs}
     * propagates the value through {@code toProto} as the wire
     * {@code idle_timeout_ns} field. The protobuf encoding treats
     * the field as set whenever a non-zero value is written, which is
     * how the device side detects the opt-in.
     */
    @Test
    void toProtoSetsIdleTimeoutNsWhenPositive() {
        TableEntry entry = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(
                        Bytes.of(ByteString.copyFrom(new byte[]{10, 0, 0, 0}).toByteArray()),
                        8))
                .action("MyIngress.forward")
                .param("port", 1)
                .idleTimeoutNs(5_000_000_000L)
                .build();
        var wire = EntryProto.toProto(entry, P4INFO);
        assertEquals(5_000_000_000L, wire.getIdleTimeoutNs(),
                "wire idle_timeout_ns should match the entry value");
    }

    /**
     * A {@link TableEntry} built without calling {@code idleTimeoutNs}
     * defaults to {@code 0}, which {@code toProto} must omit from the
     * wire encoding — matching the existing {@code setPriority}
     * convention that omits protobuf default-unset values.
     */
    @Test
    void toProtoOmitsIdleTimeoutNsWhenZero() {
        TableEntry entry = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(
                        Bytes.of(ByteString.copyFrom(new byte[]{10, 0, 0, 0}).toByteArray()),
                        8))
                .action("MyIngress.forward")
                .param("port", 1)
                .build();
        var wire = EntryProto.toProto(entry, P4INFO);
        // Builder-level "is field set" check works on int64 only via the
        // default-zero observation; the protobuf API for proto3 scalars
        // exposes only the value, not a hasField predicate.
        assertEquals(0L, wire.getIdleTimeoutNs(),
                "wire idle_timeout_ns should remain 0 when entry default");
        assertFalse(wire.toString().contains("idle_timeout_ns"),
                "textual encoding should not include idle_timeout_ns when zero");
    }
}
