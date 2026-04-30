package io.github.zhh2001.jp4.testsupport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bmv2Backend} that spawns a local {@code simple_switch_grpc} process via
 * {@link ProcessBuilder}. The behaviour is identical to the original 4B
 * BMv2TestSupport: dynamic port via the supplied port number, network interface
 * detected by {@link BMv2TestSupport#checkEnvironment()}, stdout/stderr to
 * {@code build/test-bmv2/<test>-<port>.log}, {@code destroy + 1 s grace +
 * destroyForcibly} fallback chain, and 5-attempt bind retry on {@link #restart()}.
 */
final class NativeBackend implements Bmv2Backend {

    private static final String SS_BIN = "/usr/local/bin/simple_switch_grpc";
    private static final Path LOG_DIR = Path.of("build", "test-bmv2");
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GRACE_AFTER_DESTROY = Duration.ofSeconds(1);

    private final String testName;
    private final int port;
    private final String iface;
    private Process process;
    private Path logFile;

    NativeBackend(String testName, int port, String iface) {
        this.testName = testName;
        this.port = port;
        this.iface = iface;
    }

    @Override
    public void start() throws IOException, InterruptedException {
        Files.createDirectories(LOG_DIR);
        this.logFile = LOG_DIR.resolve(testName + "-" + port + ".log");
        spawn(logFile);
        if (!waitForPort(port, READY_TIMEOUT)) {
            String tail = readTail(logFile, 30);
            destroyAndWait();
            throw new IllegalStateException(
                    "BMv2 (pid=" + process.pid() + ") did not bind 127.0.0.1:" + port
                            + " within " + READY_TIMEOUT + "; log tail:\n" + tail);
        }
    }

    @Override
    public void restart() throws IOException, InterruptedException {
        destroyAndWait();
        Files.createDirectories(LOG_DIR);
        this.logFile = LOG_DIR.resolve(
                testName + "-" + port + "-restart-" + System.currentTimeMillis() + ".log");

        IOException lastBindFailure = null;
        for (int bindAttempt = 1; bindAttempt <= 5; bindAttempt++) {
            spawn(logFile);
            if (waitForPort(port, READY_TIMEOUT)) {
                return;
            }
            destroyAndWait();
            Thread.sleep(200L * bindAttempt);
            lastBindFailure = new IOException(
                    "BMv2 restart bind attempt " + bindAttempt + " did not produce a listener on port " + port);
        }
        throw new IllegalStateException(
                "BMv2 could not be restarted on port " + port + " after 5 bind attempts",
                lastBindFailure);
    }

    @Override
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

    @Override public String grpcAddress() { return "127.0.0.1:" + port; }
    @Override public int grpcPort()       { return port; }
    @Override public boolean isAlive()    { return process != null && process.isAlive(); }
    @Override public Path logFile()       { return logFile; }
    @Override public long pid()           { return process == null ? -1 : process.pid(); }

    @Override
    public void close() {
        destroyAndWait();
    }

    private void spawn(Path log) throws IOException {
        List<String> cmd = List.of(
                SS_BIN,
                "--no-p4",
                "--device-id", "0",
                "--log-console",
                "-L", "info",
                "-i", "0@" + iface,
                "--",
                "--grpc-server-addr", "127.0.0.1:" + port,
                "--cpu-port", "255"         // Phase 7: triggers PacketIn when egress_spec == 255
        );
        this.process = new ProcessBuilder(cmd)
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start();
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

    static boolean waitForPort(int port, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (IOException ignored) {
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
}
