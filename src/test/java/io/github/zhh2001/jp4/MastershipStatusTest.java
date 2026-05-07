package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.types.ElectionId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MastershipStatusTest {

    @Test
    void acquiredIsNotLost() {
        MastershipStatus s = new MastershipStatus.Acquired(ElectionId.of(1));
        assertFalse(s.isLost());
    }

    @Test
    void lostReportsPreviousAndCurrent() {
        MastershipStatus s = new MastershipStatus.Lost(ElectionId.of(1), ElectionId.of(2));
        assertTrue(s.isLost());
        assertEquals(ElectionId.of(1), ((MastershipStatus.Lost) s).previousElectionId());
        assertEquals(ElectionId.of(2), ((MastershipStatus.Lost) s).currentPrimaryElectionId());
    }

    @Test
    void sealedSwitchExhaustive() {
        MastershipStatus s = new MastershipStatus.Acquired(ElectionId.of(1));
        boolean lost = switch (s) {
            case MastershipStatus.Acquired a -> false;
            case MastershipStatus.Lost l -> true;
        };
        assertFalse(lost);
    }

    @Test
    void acquiredToStringIsCompact() {
        MastershipStatus s = new MastershipStatus.Acquired(ElectionId.of(10));
        assertEquals("Acquired(primary=10)", s.toString());
    }

    @Test
    void lostToStringWithNullPreviousId() {
        MastershipStatus s = new MastershipStatus.Lost(null, ElectionId.of(10));
        assertEquals("Lost(prev=null, primary=10)", s.toString());
    }

    @Test
    void lostToStringWithBothIds() {
        MastershipStatus s = new MastershipStatus.Lost(ElectionId.of(5), ElectionId.of(10));
        assertEquals("Lost(prev=5, primary=10)", s.toString());
    }

    @Test
    void toStringRendersHighWordElectionIdAsUnsigned128Bit() {
        // high != 0 → render via toBigInteger().toString(); not the
        // unsigned-low-only fast path. Exercises the full 128-bit path so a
        // future change that drops it would show up as a test break.
        ElectionId withHigh = ElectionId.of(1L, 0L);  // 2^64
        MastershipStatus s = new MastershipStatus.Acquired(withHigh);
        assertEquals("Acquired(primary=18446744073709551616)", s.toString());
    }
}
