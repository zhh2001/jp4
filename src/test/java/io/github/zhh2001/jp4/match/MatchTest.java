package io.github.zhh2001.jp4.match;

import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchTest {

    @Test
    void exactFromBytesAndInt() {
        assertEquals(new Match.Exact(Bytes.ofInt(5)), Match.exact(5));
        assertEquals(new Match.Exact(Bytes.of((byte) 1, (byte) 2)),
                Match.exact(Bytes.of((byte) 1, (byte) 2)));
    }

    @Test
    void lpmFromCidrIPv4() {
        Match.Lpm m = Match.Lpm.of("10.0.0.0/24");
        assertEquals(24, m.prefixLen());
        assertEquals(Ip4.of("10.0.0.0").toBytes(), m.value());
    }

    @Test
    void lpmFromCidrIPv6() {
        Match.Lpm m = Match.Lpm.of("2001:db8::/32");
        assertEquals(32, m.prefixLen());
        assertEquals(Ip6.of("2001:db8::").toBytes(), m.value());
    }

    @Test
    void lpmRejectsMalformedCidr() {
        assertThrows(IllegalArgumentException.class, () -> Match.Lpm.of("10.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Match.Lpm.of("10.0.0.0/abc"));
    }

    @Test
    void ternaryFromInts() {
        Match.Ternary t = Match.Ternary.of(0x06, 0xff);
        assertEquals(Bytes.ofInt(0x06), t.value());
        assertEquals(Bytes.ofInt(0xff), t.mask());
    }

    @Test
    void rangeFromInts() {
        Match.Range r = Match.Range.of(1024, 65535);
        assertEquals(Bytes.ofInt(1024), r.low());
        assertEquals(Bytes.ofInt(65535), r.high());
    }

    @Test
    void sealedSwitchExhaustive() {
        Match m = Match.Lpm.of("10.0.0.0/24");
        // Compile-time exhaustive switch over the sealed permits list.
        String desc = switch (m) {
            case Match.Exact e    -> "exact";
            case Match.Lpm   l    -> "lpm:" + l.prefixLen();
            case Match.Ternary t  -> "ternary";
            case Match.Range r    -> "range";
            case Match.Optional o -> "optional";
        };
        assertEquals("lpm:24", desc);
    }

    @Test
    void recordsRejectNullArguments() {
        assertThrows(NullPointerException.class, () -> new Match.Exact(null));
        assertThrows(NullPointerException.class, () -> new Match.Lpm(null, 24));
    }
}
