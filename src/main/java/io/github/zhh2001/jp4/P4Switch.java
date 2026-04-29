package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.ElectionId;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A live connection to one P4Runtime device, scoped to one {@code device_id} and one
 * {@code election_id}. Constructed via {@link #connectAsPrimary(String)} (one-line
 * default) or {@link #connect(String)} followed by the {@link Connector} chain.
 * Always use with try-with-resources; {@link #close()} ends the StreamChannel and
 * shuts down the gRPC channel.
 *
 * <h2>Threading model</h2>
 * A {@code P4Switch} owns three internal executors and isolates them from gRPC's own
 * Netty event loop. Users get a single, predictable contract:
 * <ul>
 *   <li><b>gRPC inbound (Netty threads).</b> The StreamChannel observer runs on
 *       gRPC's executor. jp4's handler does the minimum: enqueue the message and
 *       return. It never invokes user code on a Netty thread and never blocks.</li>
 *   <li><b>Listener callbacks (single-threaded executor).</b> Handlers registered
 *       via {@link #onPacketIn(Consumer)} and {@link #onMastershipChange(Consumer)}
 *       run on a dedicated single-threaded daemon executor that consumes the inbound
 *       queue. <b>User code may block in callbacks without affecting message
 *       reception</b> — only this one thread is held up. Callbacks are dispatched in
 *       arrival order; if you need concurrency in your handler, dispatch to your own
 *       pool from inside it.</li>
 *   <li><b>Outbound RPCs (serial executor).</b> Writes, reads, pipeline pushes, and
 *       packet sends are funnelled through a single-threaded executor that owns the
 *       outbound StreamObserver — gRPC requires single-threaded access to it. User
 *       calls submit a task and block until completion (synchronous methods) or
 *       complete a {@code CompletableFuture} (the {@code xxxAsync} variants).</li>
 *   <li><b>Reconnect scheduler.</b> One {@code ScheduledExecutorService} runs the
 *       backoff timer when {@link ReconnectPolicy} is non-trivial.</li>
 * </ul>
 * All four executors are daemon threads; {@link #close()} shuts them down with a
 * 5-second grace period.
 *
 * <h2>Lifecycle</h2>
 * Connection and arbitration are real as of v0.1; pipeline push, table operations,
 * and packet I/O ship in later sub-phases and currently throw
 * {@link UnsupportedOperationException}. Write-side methods enforce the primary
 * precondition before throwing UOE — so a switch obtained via
 * {@code asSecondary()} (or one that lost mastership to a higher election id)
 * surfaces {@link P4ConnectionException}, not UOE, when a write is attempted.
 */
public final class P4Switch implements AutoCloseable {

    private static final Duration DEFAULT_ARBITRATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String address;
    private final long deviceId;
    private final ElectionId electionId;

    private final ManagedChannel channel;
    private final StreamObserver<StreamMessageRequest> reqObserver;
    private final ExecutorService outboundExecutor;
    private final ExecutorService callbackExecutor;
    private final ScheduledExecutorService reconnectScheduler;

    private final AtomicReference<MastershipStatus> mastership = new AtomicReference<>();
    private final AtomicBoolean broken = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    P4Switch(String address,
             long deviceId,
             ElectionId electionId,
             ManagedChannel channel,
             StreamObserver<StreamMessageRequest> reqObserver,
             ExecutorService outboundExecutor,
             ExecutorService callbackExecutor,
             ScheduledExecutorService reconnectScheduler,
             MastershipStatus initialMastership) {
        this.address = address;
        this.deviceId = deviceId;
        this.electionId = electionId;
        this.channel = channel;
        this.reqObserver = reqObserver;
        this.outboundExecutor = outboundExecutor;
        this.callbackExecutor = callbackExecutor;
        this.reconnectScheduler = reconnectScheduler;
        this.mastership.set(initialMastership);
    }

    /**
     * Connects to {@code address}, claims primary with {@code device_id=0} and
     * {@code election_id=1}. Equivalent to
     * {@code connect(address).deviceId(0).electionId(1).asPrimary()}; use the
     * full chain if you need different defaults or a reconnect policy.
     */
    public static P4Switch connectAsPrimary(String address) {
        return connect(address).asPrimary();
    }

    /** Begins a customised connection. Use the {@link Connector} chain to set
     *  device id / election id / reconnect policy / packet-in queue size, then
     *  finalise with {@code asPrimary()} or {@code asSecondary()}.
     */
    public static Connector connect(String address) {
        Objects.requireNonNull(address, "address");
        return new Connector(address);
    }

    /**
     * The remote address this switch is bound to. Useful for log messages.
     */
    public String address() {
        return address;
    }

    /**
     * The device id this switch was constructed with. Stays constant for the lifetime
     * of the connection.
     */
    public long deviceId() {
        return deviceId;
    }

    /**
     * The election id this switch was constructed with. Stays constant for the
     * lifetime of the connection.
     */
    public ElectionId electionId() {
        return electionId;
    }

    /**
     * Snapshot of the current mastership state. Updates as the device sends
     * arbitration messages; safe to read at any time.
     */
    public MastershipStatus mastership() {
        return mastership.get();
    }

    public boolean isPrimary() {
        MastershipStatus s = mastership.get();
        return s != null && !s.isLost();
    }

    /**
     * Re-claims primary on a switch that has observed mastership loss. <b>v0.1
     * implements only the no-op branch.</b>
     * <p>If the switch is currently primary and the gRPC stream is healthy, returns
     * {@code this} immediately without sending an RPC. If the stream is broken
     * (or the switch is closed), throws {@link P4ConnectionException}.
     * <p>Re-arbitration after observed mastership loss (the second branch documented
     * in {@code docs/api-design.md} §2 D1) is implemented in a later release.
     */
    public P4Switch asPrimary() {
        if (closed.get()) {
            throw new P4ConnectionException("switch is closed");
        }
        if (broken.get()) {
            throw new P4ConnectionException("stream is broken; reconnect not yet implemented");
        }
        if (isPrimary()) {
            return this;
        }
        throw new UnsupportedOperationException(
                "re-claim after mastership loss is implemented in a later release");
    }

    /**
     * Subscribes a single listener for mastership changes. <b>v0.1 only stores the
     * subscription; events are not yet dispatched.</b> Wired so user code can
     * register the listener at construction without restructuring later.
     */
    public void onMastershipChange(Consumer<MastershipStatus> handler) {
        Objects.requireNonNull(handler, "handler");
        // Wired but no-op in v0.1; dispatch lands in a later release.
    }

    public P4Switch bindPipeline(P4Info p4info, DeviceConfig deviceConfig) {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public P4Switch loadPipeline() {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public Pipeline pipeline() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void insert(TableEntry e) {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void modify(TableEntry e) {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void delete(TableEntry e) {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public CompletableFuture<Void> insertAsync(TableEntry e) {
        if (closed.get() || broken.get() || !isPrimary()) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(writabilityException());
            return f;
        }
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public CompletableFuture<Void> modifyAsync(TableEntry e) {
        return insertAsync(e);   // share the gate path
    }

    public CompletableFuture<Void> deleteAsync(TableEntry e) {
        return insertAsync(e);
    }

    public BatchBuilder batch() {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public ReadQuery read(String tableName) {
        if (closed.get()) throw new P4ConnectionException("switch is closed");
        if (broken.get()) throw new P4ConnectionException("stream is broken");
        // Reads are allowed for both primary and secondary.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void onPacketIn(Consumer<PacketIn> handler) {
        Objects.requireNonNull(handler, "handler");
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public Flow.Publisher<PacketIn> packetInStream() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public PacketIn pollPacketIn(Duration timeout) throws InterruptedException {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void send(PacketOut packet) {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Ends the StreamChannel gracefully (default 5 s grace), shuts down internal
     * executors, and closes the gRPC channel. Idempotent — calling more than once
     * is a no-op.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 1. Send onCompleted on the outbound stream so the server can finish cleanly.
        //    Push it through the serial executor so it doesn't race with any other
        //    outbound request that might still be in flight.
        try {
            outboundExecutor.submit(() -> {
                try {
                    reqObserver.onCompleted();
                } catch (RuntimeException ignored) {
                    // The stream may already be broken; don't propagate.
                }
            }).get(DEFAULT_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Best-effort; we'll force-shutdown below regardless.
        }

        // 2. Shut down the executors.
        outboundExecutor.shutdown();
        callbackExecutor.shutdown();
        reconnectScheduler.shutdown();
        try {
            outboundExecutor.awaitTermination(1, TimeUnit.SECONDS);
            callbackExecutor.awaitTermination(1, TimeUnit.SECONDS);
            reconnectScheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        outboundExecutor.shutdownNow();
        callbackExecutor.shutdownNow();
        reconnectScheduler.shutdownNow();

        // 3. Shut down the gRPC channel.
        channel.shutdown();
        try {
            if (!channel.awaitTermination(DEFAULT_CLOSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    // ---------- internal -----------------------------------------------------

    /**
     * Used by {@link InboundStreamHandler} on receipt of a new arbitration message.
     * Stores the new state and updates the broken flag if the server signalled an error.
     */
    void updateMastership(MastershipStatus newStatus) {
        mastership.set(newStatus);
    }

    void markBroken() {
        broken.set(true);
    }

    boolean isBroken() {
        return broken.get();
    }

    boolean isClosed() {
        return closed.get();
    }

    /**
     * Default arbitration timeout used by {@link Connector#asPrimary()} /
     * {@link Connector#asSecondary()}.
     */
    static Duration arbitrationTimeout() {
        return DEFAULT_ARBITRATION_TIMEOUT;
    }

    private void requireWritable() {
        Throwable t = writabilityException();
        if (t != null) {
            if (t instanceof P4ConnectionException p) throw p;
            throw new RuntimeException(t);
        }
    }

    private P4ConnectionException writabilityException() {
        if (closed.get()) return new P4ConnectionException("switch is closed");
        if (broken.get()) return new P4ConnectionException("stream is broken");
        if (!isPrimary()) {
            return new P4ConnectionException(
                    "cannot write while not primary (election_id=" + electionId
                            + " on device " + deviceId + ")");
        }
        return null;
    }
}
