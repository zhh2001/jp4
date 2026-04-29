package io.github.zhh2001.jp4;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectPolicyTest {

    @Test
    void noRetryAlwaysGivesUp() {
        ReconnectPolicy p = ReconnectPolicy.noRetry();
        assertTrue(p.nextDelayMillis(1).isEmpty());
        assertTrue(p.nextDelayMillis(100).isEmpty());
    }

    @Test
    void exponentialBackoffDoublesUntilMax() {
        ReconnectPolicy p = ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(1), 5);
        assertEquals(OptionalLong.of(100), p.nextDelayMillis(1));
        assertEquals(OptionalLong.of(200), p.nextDelayMillis(2));
        assertEquals(OptionalLong.of(400), p.nextDelayMillis(3));
        assertEquals(OptionalLong.of(800), p.nextDelayMillis(4));
        assertEquals(OptionalLong.of(1000), p.nextDelayMillis(5));   // capped at max=1000
    }

    @Test
    void exponentialBackoffEndsAfterMaxRetries() {
        ReconnectPolicy p = ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(1), 3);
        assertTrue(p.nextDelayMillis(4).isEmpty());
    }

    @Test
    void exponentialBackoffRejectsBadInputs() {
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.exponentialBackoff(
                Duration.ZERO, Duration.ofSeconds(1), 3));
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.exponentialBackoff(
                Duration.ofSeconds(2), Duration.ofSeconds(1), 3));
        assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(1), -1));
    }
}
