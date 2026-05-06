package io.github.zhh2001.jp4.testsupport;

import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@link Bmv2Backend} that runs BMv2 inside a Testcontainers-managed Docker
 * container. Used in CI where the runner has Docker available but no native
 * {@code simple_switch_grpc} binary.
 *
 * <p>Image selection cascades down a candidate list — the first image that pulls and
 * starts wins. The picked image is logged so CI failures can be diagnosed without
 * trial-and-error. The container's gRPC port (50051 inside) is mapped to a fixed host
 * port supplied at construction so {@link #restart()} can preserve the address the
 * controller is connected to (matches the native backend's behaviour).
 */
final class DockerBackend implements Bmv2Backend {

    /**
     * Candidate images in priority order. The first one that successfully pulls and
     * starts wins. The primary entry pins {@code p4lang/behavioral-model} to a manifest
     * digest (the {@code :latest} content as of 2026-05-05) so CI runs against the
     * same image content regardless of when the workflow fires; p4lang does not publish
     * dated immutable tags, so digest pinning is the only stable reference Docker Hub
     * offers. The mutable {@code :latest} stays as a fallback in case the digest is
     * pruned. See NOTES.md "Docker BMv2 image tag pinning" for the rotation procedure.
     *
     * <p>If all candidates fail, {@link #start()} surfaces an aggregated error listing
     * what each image attempt produced.
     */
    private static final List<String> CANDIDATE_IMAGES = List.of(
            "p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247",
            "p4lang/behavioral-model:latest",
            "opennetworkinglab/p4mn:latest"
    );

    private static final int CONTAINER_GRPC_PORT = 50051;
    private static final Path LOG_DIR = Path.of("build", "test-bmv2");
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private final String testName;
    private final int hostPort;
    private FixedHostPortGenericContainer<?> container;
    private String selectedImage;
    private Path logFile;

    DockerBackend(String testName, int hostPort) {
        this.testName = testName;
        this.hostPort = hostPort;
    }

    @Override
    public void start() throws IOException {
        Files.createDirectories(LOG_DIR);
        this.logFile = LOG_DIR.resolve(testName + "-" + hostPort + "-docker.log");

        IllegalStateException lastFailure = null;
        StringBuilder attempts = new StringBuilder();
        for (String image : CANDIDATE_IMAGES) {
            try {
                FixedHostPortGenericContainer<?> c = new FixedHostPortGenericContainer<>(image)
                        .withFixedExposedPort(hostPort, CONTAINER_GRPC_PORT)
                        .withCommand(
                                "simple_switch_grpc",
                                "--no-p4",
                                "--device-id", "0",
                                "--log-console",
                                "-L", "info",
                                "-i", "0@lo",
                                "--",
                                "--grpc-server-addr", "0.0.0.0:" + CONTAINER_GRPC_PORT,
                                "--cpu-port", "255"    // Phase 7: PacketIn on egress_spec=255
                        )
                        .waitingFor(Wait.forListeningPort()
                                .withStartupTimeout(STARTUP_TIMEOUT));
                c.start();

                this.container = c;
                this.selectedImage = image;
                System.err.println("[DockerBackend] using image: " + image
                        + " (host port " + hostPort + " → container 50051)");
                Files.writeString(logFile,
                        "[DockerBackend] selected image: " + image + "\n"
                                + c.getLogs() + "\n");
                return;
            } catch (RuntimeException e) {
                attempts.append("\n  - ").append(image).append(": ").append(e.getMessage());
                lastFailure = new IllegalStateException("image " + image + " failed", e);
                if (container != null) {
                    safeStop();
                    container = null;
                }
            }
        }
        throw new IllegalStateException(
                "DockerBackend could not start any candidate image:" + attempts, lastFailure);
    }

    @Override
    public void restart() throws IOException {
        if (container != null) {
            safeStop();
            container = null;
        }
        // Reuse the originally selected image first; if it fails for some reason,
        // the candidate cascade kicks in again.
        if (selectedImage != null) {
            List<String> ordered = new java.util.ArrayList<>();
            ordered.add(selectedImage);
            for (String i : CANDIDATE_IMAGES) {
                if (!i.equals(selectedImage)) ordered.add(i);
            }
            tryImages(ordered);
        } else {
            start();
        }
    }

    private void tryImages(List<String> images) throws IOException {
        Files.createDirectories(LOG_DIR);
        this.logFile = LOG_DIR.resolve(
                testName + "-" + hostPort + "-docker-restart-" + System.currentTimeMillis() + ".log");
        IllegalStateException lastFailure = null;
        StringBuilder attempts = new StringBuilder();
        for (String image : images) {
            try {
                FixedHostPortGenericContainer<?> c = new FixedHostPortGenericContainer<>(image)
                        .withFixedExposedPort(hostPort, CONTAINER_GRPC_PORT)
                        .withCommand(
                                "simple_switch_grpc",
                                "--no-p4",
                                "--device-id", "0",
                                "--log-console",
                                "-L", "info",
                                "-i", "0@lo",
                                "--",
                                "--grpc-server-addr", "0.0.0.0:" + CONTAINER_GRPC_PORT,
                                "--cpu-port", "255"    // Phase 7: PacketIn on egress_spec=255
                        )
                        .waitingFor(Wait.forListeningPort()
                                .withStartupTimeout(STARTUP_TIMEOUT));
                c.start();
                this.container = c;
                this.selectedImage = image;
                Files.writeString(logFile,
                        "[DockerBackend restart] selected image: " + image + "\n"
                                + c.getLogs() + "\n");
                return;
            } catch (RuntimeException e) {
                attempts.append("\n  - ").append(image).append(": ").append(e.getMessage());
                lastFailure = new IllegalStateException("image " + image + " failed", e);
            }
        }
        throw new IllegalStateException(
                "DockerBackend restart could not start any image:" + attempts, lastFailure);
    }

    @Override
    public void killForciblyNow() {
        // Testcontainers' stop() is the equivalent — Docker's SIGKILL semantics on
        // shutdown match what tests need (the controller sees an abrupt RST).
        safeStop();
    }

    @Override public String grpcAddress() { return "127.0.0.1:" + hostPort; }
    @Override public int grpcPort()       { return hostPort; }
    @Override public boolean isAlive()    { return container != null && container.isRunning(); }
    @Override public Path logFile()       { return logFile; }
    @Override public long pid()           { return -1L; }

    @Override
    public void close() {
        safeStop();
    }

    private void safeStop() {
        if (container == null) return;
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // best effort; Testcontainers may have already cleaned up
        }
    }
}
