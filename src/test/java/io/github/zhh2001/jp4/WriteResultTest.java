package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.UpdateFailure;
import io.github.zhh2001.jp4.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteResultTest {

    @Test
    void emptyFailuresMeansAllSucceeded() {
        WriteResult r = new WriteResult(3, List.of());
        assertTrue(r.allSucceeded());
        assertEquals(3, r.submitted());
    }

    @Test
    void anyFailureFlipsAllSucceeded() {
        WriteResult r = new WriteResult(3, List.of(
                new UpdateFailure(1, ErrorCode.NOT_FOUND, "missing")));
        assertFalse(r.allSucceeded());
        assertEquals(1, r.failures().size());
        assertEquals(1, r.failures().get(0).index());
    }

    @Test
    void failuresListIsImmutable() {
        WriteResult r = new WriteResult(1, List.of(
                new UpdateFailure(0, ErrorCode.INVALID_ARGUMENT, "bad")));
        assertThrows(UnsupportedOperationException.class,
                () -> r.failures().add(new UpdateFailure(1, ErrorCode.NOT_FOUND, "x")));
    }
}
