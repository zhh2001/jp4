package io.github.zhh2001.jp4.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Spawns a real {@code simple_switch_grpc} on a free port for integration tests.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #checkEnvironment()} — call from {@code @BeforeAll}; gates the entire
 *       test class on having a usable network interface and the {@code simple_switch_grpc}
 *       binary with the right capabilities. Skips the class via
 *       {@link Assumptions#assumeTrue(boolean, String)} otherwise, with a one-shot
 *       message that names the missing piece.</li>
 *   <li>{@code new BMv2TestSupport(testName).start()} — pick a free TCP port, spawn the
 *       process, wait for the gRPC port to become connectable. Default deadline 10 s.</li>
 *   <li>{@link #grpcAddress()} / {@link #grpcPort()} / {@link #pid()} — for use by tests.</li>
 *   <li>{@link #close()} — graceful {@code destroy()}, 1 s grace, then
 *       {@code destroyForcibly()}. Always called from {@code @AfterAll} /
 *       {@code @AfterEach}; safe to call twice.</li>
 * </ol>
 *
 * <p>Tests <b>never</b> invoke {@code sudo}. Network interface creation is the
 * developer's one-time setup; if it's missing, the class skips with an explicit hint.
 */
public final class BMv2TestSupport implements AutoCloseable {

    private static final String SS_BIN = "/usr/local/bin/simple_switch_grpc";
    private static final Path LOG_DIR = Path.of("build", "test-bmv2");
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GRACE_AFTER_DESTROY = Duration.ofSeconds(1);

    private final String testName;
    private final String iface;
    private final int port;
    private Process process;
    private Path logFile;

    public BMv2TestSupport(String testName) {
        this.testName = sanitise(Objects.requireNonNull(testName, "testName"));
        this.iface = pickInterface();   // already validated by checkEnvironment()
        this.port = pickFreePort();
    }

    /**
     * Returns immediately if the environment is usable; otherwise calls
     * {@link Assumptions#assumeTrue} with a hint that names the missing piece, which
     * skips the entire test class cleanly. Call once from {@code @BeforeAll}.
     */
    public static void checkEnvironment() {
        if (!Files.isExecutable(Path.of(SS_BIN))) {
            Assumptions.assumeTrue(false,
                    "simple_switch_grpc not found at " + SS_BIN
                            + " — install BMv2 from https://github.com/p4lang/behavioral-model");
        }
        if (!hasCapabilities(SS_BIN)) {
            Assumptions.assumeTrue(false,
                    "simple_switch_grpc lacks cap_net_admin / cap_net_raw — run:\n"
                            + "  sudo setcap cap_net_admin,cap_net_raw=eip " + SS_BIN);
        }
        if (!interfaceExists("veth0") && !interfaceExists("bm0")) {
            Assumptions.assumeTrue(false,
                    "No usable network interface for BMv2 — create veth pair with:\n"
                            + "  sudo ip link add veth0 type veth peer name veth1\n"
                            + "  sudo ip link set veth0 up && sudo ip link set veth1 up\n"
                            + "or fall back to dummy:\n"
                            + "  sudo ip link add dev bm0 type dummy && sudo ip link set bm0 up");
        }
    }

    /** Spawns the BMv2 process, waits for the gRPC port to be reachable. */
    public BMv2TestSupport start() throws IOException, InterruptedException {
        Files.createDirectories(LOG_DIR);
        this.logFile = LOG_DIR.resolve(testName + "-" + port + ".log");

        List<String> cmd = List.of(
                SS_BIN,
                "--no-p4",
                "--device-id", "0",
                "--log-console",
                "-L", "info",
                "-i", "0@" + iface,
                "--",
                "--grpc-server-addr", "127.0.0.1:" + port
        );

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectOutput(logFile.toFile())
                .redirectErrorStream(true);
        this.process = pb.start();

        if (!waitForPort(port, READY_TIMEOUT)) {
            // Don't leave a zombie; capture the log in the failure message.
            String logTail = readTail(logFile, 30);
            destroyAndWait();
            throw new IllegalStateException(
                    "BMv2 (pid=" + process.pid() + ") did not bind 127.0.0.1:" + port
                            + " within " + READY_TIMEOUT + "; log tail:\n" + logTail);
        }
        return this;
    }

    public String grpcAddress() {
        return "127.0.0.1:" + port;
    }

    public int grpcPort() {
        return port;
    }

    public String iface() {
        return iface;
    }

    public Path logFile() {
        return logFile;
    }

    public long pid() {
        return process == null ? -1 : process.pid();
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Forcefully kills the process group. Used by tests that need to simulate a
     * server crash mid-stream (scenario h). Safe to call before normal {@link #close()}.
     */
    public void killForciblyNow() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(GRACE_AFTER_DESTROY.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        destroyAndWait();
    }

    private void destroyAndWait() {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(GRACE_AFTER_DESTROY.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(GRACE_AFTER_DESTROY.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    // ---------- helpers ---------------------------------------------------

    private static int pickFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not allocate a free TCP port", e);
        }
    }

    private static String pickInterface() {
        if (interfaceExists("veth0")) return "veth0";
        if (interfaceExists("bm0")) return "bm0";
        throw new IllegalStateException("checkEnvironment() should have skipped first");
    }

    private static boolean interfaceExists(String name) {
        try {
            Process p = new ProcessBuilder("ip", "link", "show", name)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean hasCapabilities(String binary) {
        try {
            Process p = new ProcessBuilder("getcap", binary)
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(2, TimeUnit.SECONDS);
            String text = new String(out);
            return text.contains("cap_net_admin") && text.contains("cap_net_raw");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Polls {@code 127.0.0.1:port} for connectability. Uses a short {@link Socket}
     * connect attempt — TCP is non-blocking and gives an unambiguous "ready" signal
     * the moment the gRPC server starts accepting; no fixed sleeps.
     */
    private static boolean waitForPort(int port, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (IOException ignored) {
                // not yet up; brief yield so we don't busy-spin
                Thread.sleep(50);
            }
        }
        return false;
    }

    private static String readTail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "(could not read log: " + e.getMessage() + ")";
        }
    }

    private static String sanitise(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
