package io.github.zhh2001.jp4.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Public test fixture for spawning a real BMv2 instance during integration tests.
 *
 * <p>The actual mechanism is selected at construction time by the {@code JP4_BMV2_MODE}
 * environment variable:
 * <ul>
 *   <li><b>{@code native}</b> (default) — local {@code simple_switch_grpc} via
 *       {@link NativeBackend}; the developer-machine path</li>
 *   <li><b>{@code docker}</b> — Testcontainers + {@code p4lang/behavioral-model} via
 *       {@link DockerBackend}; the CI path</li>
 * </ul>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #checkEnvironment()} — call from {@code @BeforeAll}; gates the entire
 *       test class on the chosen backend's prerequisites being satisfied. Skips the
 *       class via {@link Assumptions#assumeTrue(boolean, String)} otherwise.</li>
 *   <li>{@code new BMv2TestSupport(testName).start()} — pick a free TCP port (host
 *       side), spawn the backend, wait for the gRPC port to be reachable.</li>
 *   <li>{@link #grpcAddress()} / {@link #grpcPort()} / {@link #pid()} — for use by tests.</li>
 *   <li>{@link #close()} — graceful teardown via the backend's destroy chain.</li>
 * </ol>
 *
 * <p>Tests <b>never</b> invoke {@code sudo}. Network interface creation is the
 * developer's one-time setup; if it's missing in native mode, the class skips with
 * an explicit hint.
 */
public final class BMv2TestSupport implements AutoCloseable {

    private static final String SS_BIN = "/usr/local/bin/simple_switch_grpc";

    private final Bmv2Backend backend;

    public BMv2TestSupport(String testName) {
        Objects.requireNonNull(testName, "testName");
        String safe = sanitise(testName);
        int port = pickFreePort();
        String mode = currentMode();
        if ("docker".equals(mode)) {
            this.backend = new DockerBackend(safe, port);
        } else {
            this.backend = new NativeBackend(safe, port, pickInterface());
        }
    }

    /**
     * Returns immediately if the environment supports the selected backend; otherwise
     * skips the entire test class via {@link Assumptions#assumeTrue} with a hint that
     * names the missing piece. Call once from {@code @BeforeAll}.
     */
    public static void checkEnvironment() {
        if ("docker".equals(currentMode())) {
            // Docker mode: Testcontainers will validate the daemon at start time.
            // We only check here that the Docker socket / TC strategy is wired,
            // which Testcontainers itself reports clearly on first use.
            return;
        }
        // Native mode: check binary, capabilities, and at least one usable interface.
        if (!Files.isExecutable(Path.of(SS_BIN))) {
            Assumptions.assumeTrue(false,
                    "simple_switch_grpc not found at " + SS_BIN
                            + " — install BMv2 from https://github.com/p4lang/behavioral-model"
                            + " or run with JP4_BMV2_MODE=docker");
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

    /** Tells tests whether the current mode is Docker (used by tests that need to
     *  skip OS-pid-specific assertions). */
    public static boolean isDockerMode() {
        return "docker".equals(currentMode());
    }

    public BMv2TestSupport start() throws IOException, InterruptedException {
        backend.start();
        return this;
    }

    public BMv2TestSupport restart() throws IOException, InterruptedException {
        backend.restart();
        return this;
    }

    public void killForciblyNow()        { backend.killForciblyNow(); }
    public String grpcAddress()          { return backend.grpcAddress(); }
    public int grpcPort()                { return backend.grpcPort(); }
    public String iface()                {
        return backend instanceof NativeBackend ? "veth0/bm0 (native)" : "lo (docker)";
    }
    public Path logFile()                { return backend.logFile(); }
    public long pid()                    { return backend.pid(); }
    public boolean isAlive()             { return backend.isAlive(); }

    @Override
    public void close()                  { backend.close(); }

    // ---------- helpers ---------------------------------------------------

    private static String currentMode() {
        String env = System.getenv("JP4_BMV2_MODE");
        return env == null ? "native" : env.trim().toLowerCase(Locale.ROOT);
    }

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

    private static String sanitise(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
