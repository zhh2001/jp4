package io.github.zhh2001.jp4.testsupport;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction over a single BMv2 instance used by an integration test. Two
 * implementations exist:
 * <ul>
 *   <li>{@link NativeBackend} — spawns {@code simple_switch_grpc} from a local
 *       installation (the developer-machine path)</li>
 *   <li>{@link DockerBackend} — runs the {@code p4lang/behavioral-model} container
 *       via Testcontainers (the CI path)</li>
 * </ul>
 * Selection is by the {@code JP4_BMV2_MODE} environment variable (defaults to
 * {@code native}).
 */
interface Bmv2Backend extends AutoCloseable {

    /** Boots the backend. After return, {@link #grpcAddress()} is reachable. */
    void start() throws IOException, InterruptedException;

    /** Tears down and starts a fresh instance on the same host port — used by the
     *  reconnect tests. */
    void restart() throws IOException, InterruptedException;

    /** Force-stops the underlying process / container. Test-only escape hatch. */
    void killForciblyNow();

    /** Host-side gRPC address as seen from the test process ({@code "127.0.0.1:N"}). */
    String grpcAddress();

    int grpcPort();

    boolean isAlive();

    /** Path where stdout / stderr from the BMv2 process is captured. */
    Path logFile();

    /** OS process id when meaningful; {@code -1} when the backend has no
     *  conventional pid (e.g. Docker containers as seen from the test process). */
    long pid();

    @Override
    void close();
}
