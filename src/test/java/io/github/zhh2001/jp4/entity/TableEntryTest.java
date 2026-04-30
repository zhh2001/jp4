package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the table-entry builder chain. Exercises each {@code match()} and
 * {@code param()} overload, exact-match shortcut wrapping, explicit {@link Match}
 * subtypes, the negative-value rejection contract on the {@code int}/{@code long}
 * overloads, and the delete-only build path.
 *
 * <p>P4Info-driven validation is intentionally not tested here — that is the
 * 6B switch-op layer's responsibility and is covered by the integration tests.
 */
class TableEntryTest {

    @Test
    void exactMatchByMacWrapsAsBytes() {
        TableEntry e = TableEntry.in("t")
                .match("hdr.eth.dstAddr", Mac.of("de:ad:be:ef:00:01"))
                .action("act").param("p", 1)
                .build();

        Match m = e.match("hdr.eth.dstAddr");
        Match.Exact ex = assertInstanceOf(Match.Exact.class, m);
        assertEquals(Mac.of("de:ad:be:ef:00:01").toBytes(), ex.value());
    }

    @Test
    void exactMatchByIp4Ip6Bytes() {
        TableEntry e4 = TableEntry.in("t")
                .match("hdr.ipv4.dstAddr", Ip4.of("10.0.0.1"))
                .action("act").build();
        assertEquals(Ip4.of("10.0.0.1").toBytes(),
                ((Match.Exact) e4.match("hdr.ipv4.dstAddr")).value());

        TableEntry e6 = TableEntry.in("t")
                .match("hdr.ipv6.dstAddr", Ip6.of("2001:db8::1"))
                .action("act").build();
        assertEquals(Ip6.of("2001:db8::1").toBytes(),
                ((Match.Exact) e6.match("hdr.ipv6.dstAddr")).value());

        Bytes raw = Bytes.of((byte) 0xCA, (byte) 0xFE);
        TableEntry eb = TableEntry.in("t")
                .match("hdr.foo", raw)
                .action("act").build();
        assertEquals(raw, ((Match.Exact) eb.match("hdr.foo")).value());
    }

    @Test
    void exactMatchByIntAndLong() {
        TableEntry e = TableEntry.in("t")
                .match("etherType", 0x0800)
                .match("longField", 0xCAFEBABEL)
                .action("act").build();
        assertEquals(Bytes.ofInt(0x0800), ((Match.Exact) e.match("etherType")).value());
        assertEquals(Bytes.ofLong(0xCAFEBABEL), ((Match.Exact) e.match("longField")).value());
    }

    @Test
    void exactMatchByByteArrayAcceptsAnyBitPattern() {
        // High bit set: a int overload would reject this; byte[] must accept.
        byte[] sneaky = {(byte) 0x80, (byte) 0xFF};
        TableEntry e = TableEntry.in("t")
                .match("hdr.bits", sneaky)
                .action("act").build();
        assertArrayEquals(sneaky, ((Match.Exact) e.match("hdr.bits")).value().toByteArray());
    }

    @Test
    void explicitMatchSubtypesPreservedAsIs() {
        TableEntry e = TableEntry.in("t")
                .match("dst", Match.Lpm.of("10.0.0.0/24"))
                .match("proto", Match.Ternary.of(0x06, 0xff))
                .match("port", Match.Range.of(1024, 65535))
                .match("opt", new Match.Optional(Bytes.ofInt(7)))
                .action("act").build();

        assertInstanceOf(Match.Lpm.class, e.match("dst"));
        assertEquals(24, ((Match.Lpm) e.match("dst")).prefixLen());
        assertInstanceOf(Match.Ternary.class, e.match("proto"));
        assertInstanceOf(Match.Range.class, e.match("port"));
        assertInstanceOf(Match.Optional.class, e.match("opt"));
    }

    @Test
    void matchOnSameFieldTwiceReplacesValue() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .match("k", 2)
                .action("act").build();
        assertEquals(Bytes.ofInt(2), ((Match.Exact) e.match("k")).value());
    }

    @Test
    void unknownMatchFieldReturnsNull() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1).action("act").build();
        assertNull(e.match("bogus"));
    }

    @Test
    void negativeIntAndLongMatchRejected() {
        IllegalArgumentException eInt = assertThrows(IllegalArgumentException.class,
                () -> TableEntry.in("t").match("k", -1).action("act").build());
        assertTrue(eInt.getMessage().contains("non-negative"),
                "message must guide the user to byte[]/Bytes; got: " + eInt.getMessage());

        IllegalArgumentException eLong = assertThrows(IllegalArgumentException.class,
                () -> TableEntry.in("t").match("k", -1L).action("act").build());
        assertTrue(eLong.getMessage().contains("non-negative"));
    }

    @Test
    void actionParamOverloadsCoverAllValueTypes() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .action("act")
                    .param("intP", 5)
                    .param("longP", 9_999_999L)
                    .param("bytesP", Bytes.of((byte) 0xAB))
                    .param("rawP", new byte[]{(byte) 0x80})
                    .param("macP", Mac.of("aa:bb:cc:dd:ee:ff"))
                    .param("ip4P", Ip4.of("1.2.3.4"))
                    .param("ip6P", Ip6.of("::1"))
                .build();

        var instance = e.action();
        assertEquals("act", instance.name());
        assertEquals(Bytes.ofInt(5), instance.param("intP"));
        assertEquals(Bytes.ofLong(9_999_999L), instance.param("longP"));
        assertEquals(Bytes.of((byte) 0xAB), instance.param("bytesP"));
        assertArrayEquals(new byte[]{(byte) 0x80}, instance.param("rawP").toByteArray());
        assertEquals(Mac.of("aa:bb:cc:dd:ee:ff").toBytes(), instance.param("macP"));
        assertEquals(Ip4.of("1.2.3.4").toBytes(), instance.param("ip4P"));
        assertEquals(Ip6.of("::1").toBytes(), instance.param("ip6P"));
    }

    @Test
    void negativeIntAndLongParamRejected() {
        IllegalArgumentException eInt = assertThrows(IllegalArgumentException.class,
                () -> TableEntry.in("t").match("k", 1).action("act").param("p", -1).build());
        assertTrue(eInt.getMessage().contains("non-negative"));

        IllegalArgumentException eLong = assertThrows(IllegalArgumentException.class,
                () -> TableEntry.in("t").match("k", 1).action("act").param("p", -1L).build());
        assertTrue(eLong.getMessage().contains("non-negative"));
    }

    @Test
    void paramByteArrayAndBytesAcceptHighBitSet() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .action("act")
                    .param("a", new byte[]{(byte) 0xFF})
                    .param("b", Bytes.of((byte) 0xFF))
                .build();
        assertArrayEquals(new byte[]{(byte) 0xFF}, e.action().param("a").toByteArray());
        assertEquals(Bytes.of((byte) 0xFF), e.action().param("b"));
    }

    @Test
    void paramOnSameNameTwiceReplaces() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .action("act").param("p", 1).param("p", 2)
                .build();
        assertEquals(Bytes.ofInt(2), e.action().param("p"));
    }

    @Test
    void priorityViaActionBuilderDelegatesToParent() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .action("act").priority(100)
                .build();
        assertEquals(100, e.priority());
    }

    @Test
    void priorityViaTableBuilderDirect() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .priority(50)
                .action("act")
                .build();
        assertEquals(50, e.priority());
    }

    @Test
    void deleteOnlyEntryHasNullAction() {
        TableEntry e = TableEntry.in("t")
                .match("k", 1)
                .build();
        assertEquals("t", e.tableName());
        assertNotNull(e.match("k"));
        assertNull(e.action(), "delete-only entry must surface a null action");
        assertEquals(0, e.priority());
    }

    @Test
    void matchesViewIsImmutable() {
        TableEntry e = TableEntry.in("t").match("k", 1).action("act").build();
        assertThrows(UnsupportedOperationException.class,
                () -> e.matches().put("k2", new Match.Exact(Bytes.ofInt(2))));
    }

    @Test
    void actionParamsViewIsImmutable() {
        TableEntry e = TableEntry.in("t").match("k", 1).action("act").param("p", 1).build();
        assertThrows(UnsupportedOperationException.class,
                () -> e.action().params().put("p2", Bytes.ofInt(2)));
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> TableEntry.in(null));
        assertThrows(NullPointerException.class,
                () -> TableEntry.in("t").match("k", (Bytes) null));
        assertThrows(NullPointerException.class,
                () -> TableEntry.in("t").match("k", (Mac) null));
        assertThrows(NullPointerException.class,
                () -> TableEntry.in("t").match(null, 1));
        assertThrows(NullPointerException.class,
                () -> TableEntry.in("t").match("k", (Match) null));
        assertThrows(NullPointerException.class,
                () -> TableEntry.in("t").action(null));
    }
}
