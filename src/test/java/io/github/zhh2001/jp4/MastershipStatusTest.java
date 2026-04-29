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
}
