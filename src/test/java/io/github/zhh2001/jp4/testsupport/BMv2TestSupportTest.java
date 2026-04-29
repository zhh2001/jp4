package io.github.zhh2001.jp4.testsupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link BMv2TestSupport} itself: start, port-reachability, stop,
 * and the kill-forcibly fallback.
 */
class BMv2TestSupportTest {

    @BeforeAll
    static void env() {
        BMv2TestSupport.checkEnvironment();
    }

    @Test
    void startThenPortIsReachable_thenCloseStopsProcess() throws Exception {
        try (BMv2TestSupport bmv2 = new BMv2TestSupport("startStop").start()) {
            assertTrue(bmv2.isAlive(), "BMv2 should be alive after start()");
            assertTrue(bmv2.pid() > 0, "pid should be set");

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", bmv2.grpcPort()), 1000);
                assertTrue(s.isConnected(), "TCP connect to gRPC port should succeed");
            }

            assertTrue(Files.exists(bmv2.logFile()), "log file should be created");
        }
        // After try-with-resources: process must be dead.
    }

    @Test
    void closeIsIdempotent() throws Exception {
        BMv2TestSupport bmv2 = new BMv2TestSupport("idempotentClose").start();
        long pid = bmv2.pid();
        bmv2.close();
        assertFalse(bmv2.isAlive(), "after first close: process gone");
        // Calling close again must not throw or revive anything.
        bmv2.close();
        assertFalse(bmv2.isAlive(), "after second close: still gone");
        // Sanity: the OS no longer has this PID running our binary.
        assertFalse(processIsAlive(pid), "OS reports pid " + pid + " gone");
    }

    @Test
    void killForciblyExitsImmediately() throws Exception {
        BMv2TestSupport bmv2 = new BMv2TestSupport("forcefulKill").start();
        long pid = bmv2.pid();
        assertTrue(bmv2.isAlive());

        bmv2.killForciblyNow();
        assertFalse(bmv2.isAlive(), "after killForciblyNow: process gone");
        assertFalse(processIsAlive(pid), "OS reports pid " + pid + " gone");

        // close() after a forceful kill is a no-op (process already dead).
        bmv2.close();
    }

    @Test
    void uniquePortPerInstanceAllowsConcurrentBmv2() throws Exception {
        try (BMv2TestSupport a = new BMv2TestSupport("concurrentA").start();
             BMv2TestSupport b = new BMv2TestSupport("concurrentB").start()) {
            assertNotEquals(a.grpcPort(), b.grpcPort(), "ports must differ");
            assertTrue(a.isAlive());
            assertTrue(b.isAlive());
        }
    }

    private static boolean processIsAlive(long pid) {
        try {
            Process probe = new ProcessBuilder("kill", "-0", Long.toString(pid))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            probe.waitFor();
            return probe.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
