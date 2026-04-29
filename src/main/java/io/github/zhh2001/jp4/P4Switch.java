package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.error.P4ArbitrationLost;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.ElectionId;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.Uint128;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 *       gRPC's executor. jp4's handler does the minimum: parse, update atomic state,
 *       submit to the callback executor, return. It never invokes user code on a
 *       Netty thread and never blocks.</li>
 *   <li><b>Listener callbacks (single-threaded executor).</b> Handlers registered
 *       via {@link #onPacketIn(Consumer)} and {@link #onMastershipChange(Consumer)}
 *       run on a dedicated single-threaded daemon executor that consumes the inbound
 *       queue. <b>User code may block in callbacks without affecting message
 *       reception</b> — only this one thread is held up. Callbacks are dispatched in
 *       arrival order.</li>
 *   <li><b>Outbound RPCs (serial executor).</b> Writes, reads, pipeline pushes, and
 *       packet sends are funnelled through a single-threaded executor that owns the
 *       outbound StreamObserver — gRPC requires single-threaded access to it.</li>
 *   <li><b>Reconnect scheduler.</b> One {@code ScheduledExecutorService} runs the
 *       backoff timer when {@link ReconnectPolicy} is non-trivial.</li>
 * </ul>
 * All four executors are daemon threads; {@link #close()} shuts them down with a
 * 5-second grace period and is safe to invoke from a listener callback (the actual
 * teardown runs on a separate closer thread to avoid the self-shutdown deadlock).
 *
 * <h2>Reconnect</h2>
 * On stream error or completion, jp4 consults the configured {@link ReconnectPolicy}.
 * Each retry rebuilds the entire {@link io.grpc.ManagedChannel}, opens a new
 * StreamChannel call, and re-issues {@code MasterArbitrationUpdate} with the same
 * {@code election_id}. The seven polling checkpoints in {@code attemptReconnect}
 * cooperate with {@link #close()} to ensure no resource leaks if the user closes
 * the switch mid-attempt. Reconnect can be disabled with
 * {@link ReconnectPolicy#noRetry()} (the default).
 *
 * @implNote Connection, arbitration, mastership-change events, the synchronous and
 *           idempotent forms of {@code asPrimary}, {@code close}, and the read-only
 *           accessors are real as of v0.1.0. Pipeline push, table operations, packet
 *           I/O, and the read query builder still throw
 *           {@link UnsupportedOperationException}; behaviour lands in later v0.1.0
 *           milestones (Phase 5 = pipeline, Phase 6 = entries, Phase 7 = packets).
 *           Write-side methods enforce the primary-state precondition before throwing
 *           UOE — a switch obtained via {@code asSecondary()} or one demoted by a
 *           higher election id surfaces {@link P4ConnectionException}, not UOE, when a
 *           write is attempted.
 *
 * @since 0.1.0
 */
public final class P4Switch implements AutoCloseable {

    private static final Duration DEFAULT_ARBITRATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String address;
    private final long deviceId;
    private final ElectionId electionId;
    private final ReconnectPolicy reconnectPolicy;

    private final ExecutorService outboundExecutor;
    private final ExecutorService callbackExecutor;
    private final ScheduledExecutorService reconnectScheduler;
    private final Thread callbackThread;     // captured at construction; for self-detect in close
    private final Thread outboundThread;

    private final AtomicReference<StreamSession> session = new AtomicReference<>();
    private final AtomicLong currentGeneration = new AtomicLong(0L);
    private final AtomicReference<MastershipStatus> mastership = new AtomicReference<>();
    private final AtomicBoolean broken = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private volatile Consumer<MastershipStatus> listener;
    private final AtomicReference<ScheduledFuture<?>> nextReconnect = new AtomicReference<>();
    private final AtomicLong reconnectAttempt = new AtomicLong(0L);
    private final AtomicReference<CompletableFuture<MastershipStatus>> reArbWaiter = new AtomicReference<>();

    P4Switch(String address,
             long deviceId,
             ElectionId electionId,
             ReconnectPolicy reconnectPolicy,
             ExecutorService outboundExecutor,
             ExecutorService callbackExecutor,
             ScheduledExecutorService reconnectScheduler,
             Thread outboundThread,
             Thread callbackThread,
             StreamSession initialSession,
             MastershipStatus initialMastership) {
        this.address = address;
        this.deviceId = deviceId;
        this.electionId = electionId;
        this.reconnectPolicy = reconnectPolicy;
        this.outboundExecutor = outboundExecutor;
        this.callbackExecutor = callbackExecutor;
        this.reconnectScheduler = reconnectScheduler;
        this.outboundThread = outboundThread;
        this.callbackThread = callbackThread;
        this.session.set(initialSession);
        this.currentGeneration.set(initialSession.generation);
        this.mastership.set(initialMastership);
    }

    public static P4Switch connectAsPrimary(String address) {
        return connect(address).asPrimary();
    }

    public static Connector connect(String address) {
        Objects.requireNonNull(address, "address");
        return new Connector(address);
    }

    public String address()             { return address; }
    public long deviceId()              { return deviceId; }
    public ElectionId electionId()      { return electionId; }
    public MastershipStatus mastership(){ return mastership.get(); }
    public boolean isPrimary() {
        MastershipStatus s = mastership.get();
        return s != null && !s.isLost();
    }

    /**
     * Re-claims primary on a switch that has observed mastership loss. Idempotent:
     * if currently primary and the stream is healthy, returns {@code this} without
     * sending an RPC. If the switch has internally observed mastership loss but the
     * stream is healthy, re-sends {@code MasterArbitrationUpdate} with the same
     * election id and blocks until a response arrives — throws
     * {@link P4ArbitrationLost} if the device denies primary again.
     *
     * <p>Throws {@link P4ConnectionException} if the switch is closed or the stream
     * is broken; in the broken case automatic reconnect (if configured) handles the
     * recovery and the user observes it via {@link #onMastershipChange}.
     */
    public synchronized P4Switch asPrimary() {
        if (closing.get()) throw new P4ConnectionException("switch is closed");
        if (broken.get()) {
            throw new P4ConnectionException(
                    "stream is broken; reconnect happens automatically when a ReconnectPolicy is configured");
        }
        if (isPrimary()) return this;

        StreamSession sess = session.get();
        if (sess == null) throw new P4ConnectionException("no active session");

        CompletableFuture<MastershipStatus> waiter = new CompletableFuture<>();
        reArbWaiter.set(waiter);
        try {
            StreamMessageRequest req = buildArbitrationRequest();
            outboundExecutor.submit(() -> sess.reqObserver.onNext(req))
                    .get(2, TimeUnit.SECONDS);

            MastershipStatus result;
            try {
                result = waiter.get(
                        DEFAULT_ARBITRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // Some target implementations (notably BMv2's PI library) do not always
                // emit an arbitration response when the resending controller's role does
                // not change — they treat a duplicate MAU as a no-op. Fall back to the
                // last known mastership snapshot so the call resolves rather than hangs.
                MastershipStatus snapshot = mastership.get();
                if (snapshot != null && !snapshot.isLost()) {
                    return this;
                }
                ElectionId currentPrimary = (snapshot instanceof MastershipStatus.Lost lost)
                        ? lost.currentPrimaryElectionId() : null;
                throw new P4ArbitrationLost(
                        "re-arbitration produced no response; latest mastership snapshot is "
                                + snapshot, electionId, currentPrimary);
            }

            if (result.isLost()) {
                ElectionId currentPrimary =
                        ((MastershipStatus.Lost) result).currentPrimaryElectionId();
                throw new P4ArbitrationLost(
                        "re-arbitration denied primary on device " + deviceId
                                + " at " + address + "; current primary=" + currentPrimary,
                        electionId, currentPrimary);
            }
            return this;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new P4ConnectionException("interrupted during re-arbitration", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof P4ConnectionException p4) throw p4;
            throw new P4ConnectionException("re-arbitration failed", cause);
        } catch (TimeoutException te) {
            // Outbound submit timed out (the inner future); treat as connection issue.
            throw new P4ConnectionException("re-arbitration outbound timed out", te);
        } finally {
            reArbWaiter.compareAndSet(waiter, null);
        }
    }

    /**
     * Registers a single listener for mastership changes. Replaces any prior
     * listener. The callback runs on a dedicated single-threaded executor (see
     * the threading-model section above) and may block — only the dispatch thread
     * is held up.
     */
    public void onMastershipChange(Consumer<MastershipStatus> handler) {
        listener = Objects.requireNonNull(handler, "handler");
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
        if (closing.get() || broken.get() || !isPrimary()) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(writabilityException());
            return f;
        }
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public CompletableFuture<Void> modifyAsync(TableEntry e) { return insertAsync(e); }
    public CompletableFuture<Void> deleteAsync(TableEntry e) { return insertAsync(e); }

    public BatchBuilder batch() {
        requireWritable();
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public ReadQuery read(String tableName) {
        if (closing.get()) throw new P4ConnectionException("switch is closed");
        if (broken.get()) throw new P4ConnectionException("stream is broken");
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
     * Ends the StreamChannel, cancels any pending reconnect, shuts down internal
     * executors, and closes the gRPC channel. Idempotent. Safe to call from inside a
     * listener callback or any other context: the actual teardown runs on a separate
     * closer thread, and self-shutdown of the calling executor is skipped (it drains
     * naturally after the calling task returns).
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;

        // 1. Cancel future reconnect — cancel(false) means "don't start new tasks";
        //    any in-flight attempt cooperates via the closing flag.
        ScheduledFuture<?> pending = nextReconnect.getAndSet(null);
        if (pending != null) pending.cancel(false);

        // 2. Wake any re-arbitration waiter so the calling thread doesn't deadlock.
        CompletableFuture<MastershipStatus> w = reArbWaiter.getAndSet(null);
        if (w != null) {
            w.completeExceptionally(new P4ConnectionException("switch closed"));
        }

        // 3. Run the actual cleanup on a fresh daemon thread; pass caller-thread
        //    identity so doClose can skip self-shutdown of the calling executor.
        Thread caller = Thread.currentThread();
        boolean fromCallback = caller == callbackThread;
        boolean fromOutbound = caller == outboundThread;
        Thread closer = new Thread(() -> doClose(fromCallback, fromOutbound),
                "jp4-closer-" + System.identityHashCode(this));
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(DEFAULT_CLOSE_TIMEOUT.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void doClose(boolean fromCallback, boolean fromOutbound) {
        StreamSession sess = session.getAndSet(null);

        // Best-effort onCompleted on the outbound stream so the device sees a clean
        // termination. Skipped if we're called from the outbound thread itself
        // (channel.shutdown will still terminate the stream from the underlying
        // transport's perspective).
        if (sess != null && !fromOutbound) {
            try {
                outboundExecutor.submit(() -> {
                    try { sess.reqObserver.onCompleted(); }
                    catch (RuntimeException ignored) { }
                }).get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) { /* best effort */ }
        }

        outboundExecutor.shutdown();
        callbackExecutor.shutdown();
        reconnectScheduler.shutdown();
        try {
            if (!fromOutbound) outboundExecutor.awaitTermination(1, TimeUnit.SECONDS);
            if (!fromCallback) callbackExecutor.awaitTermination(1, TimeUnit.SECONDS);
            reconnectScheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        outboundExecutor.shutdownNow();
        callbackExecutor.shutdownNow();
        reconnectScheduler.shutdownNow();

        if (sess != null) {
            sess.channel.shutdown();
            try {
                if (!sess.channel.awaitTermination(DEFAULT_CLOSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    sess.channel.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sess.channel.shutdownNow();
            }
        }
    }

    // ---------- internal: called from InboundStreamHandler --------------------

    /** Used by the handler to discard side effects from a stale session. */
    long currentGeneration() {
        return currentGeneration.get();
    }

    void updateMastership(MastershipStatus newStatus) {
        mastership.set(newStatus);
    }

    void completeReArbitration(MastershipStatus newStatus) {
        CompletableFuture<MastershipStatus> w = reArbWaiter.get();
        if (w != null) w.complete(newStatus);
    }

    void dispatchMastership(MastershipStatus newStatus) {
        Consumer<MastershipStatus> l = listener;
        if (l == null) return;
        try {
            callbackExecutor.execute(() -> {
                try { l.accept(newStatus); }
                catch (RuntimeException ignored) { /* user code; don't kill the thread */ }
            });
        } catch (RejectedExecutionException ignored) {
            // executor shut down (closing); event drops cleanly.
        }
    }

    void onStreamBroken(long sourceGeneration, P4ConnectionException cause) {
        // Stale handler: ignore (a newer session is already current).
        if (sourceGeneration != currentGeneration.get()) return;
        if (closing.get()) return;

        // First-time broken: dispatch a Lost event so the listener sees the demotion.
        if (broken.compareAndSet(false, true)) {
            MastershipStatus current = mastership.get();
            if (current instanceof MastershipStatus.Acquired acquired) {
                MastershipStatus lost = new MastershipStatus.Lost(acquired.ourElectionId(), null);
                mastership.set(lost);
                dispatchMastership(lost);
            }
            // Wake any blocked re-arbitration with the broken signal.
            CompletableFuture<MastershipStatus> w = reArbWaiter.get();
            if (w != null) w.completeExceptionally(cause);
        }

        scheduleNextReconnect();
    }

    private void scheduleNextReconnect() {
        if (closing.get()) return;
        long attempt = reconnectAttempt.incrementAndGet();
        OptionalLong delay = reconnectPolicy.nextDelayMillis((int) Math.min(attempt, Integer.MAX_VALUE));
        if (delay.isEmpty()) {
            // Policy gave up. broken stays true; subsequent ops surface P4ConnectionException.
            return;
        }
        try {
            ScheduledFuture<?> task = reconnectScheduler.schedule(
                    this::attemptReconnect, delay.getAsLong(), TimeUnit.MILLISECONDS);
            nextReconnect.set(task);
        } catch (RejectedExecutionException ignored) {
            // scheduler shut down (closing); fine.
        }
    }

    /**
     * Tries to re-establish the stream. Polls {@link #closing} at every step so a
     * concurrent {@link #close()} can short-circuit without the caller relying on
     * thread interrupts (cancel(false) only stops not-yet-started tasks).
     */
    private void attemptReconnect() {
        // POLLING CHECKPOINT 1: runnable entry.
        if (closing.get()) return;

        io.grpc.ManagedChannel newChannel = null;
        StreamSession newSession = null;
        try {
            // POLLING CHECKPOINT 2: about to start work after scheduler delay.
            if (closing.get()) return;

            newChannel = NettyChannelBuilder.forTarget(address).usePlaintext().build();

            // POLLING CHECKPOINT 3: channel built, before opening stream.
            if (closing.get()) {
                newChannel.shutdownNow();
                return;
            }

            long newGen = currentGeneration.get() + 1;
            InboundStreamHandler newHandler = new InboundStreamHandler(electionId, newGen);
            StreamObserver<StreamMessageRequest> newReq =
                    P4RuntimeGrpc.newStub(newChannel).streamChannel(newHandler);
            newSession = new StreamSession(newGen, newChannel, newHandler, newReq);

            // POLLING CHECKPOINT 4: about to send arbitration.
            if (closing.get()) {
                newSession.shutdownGracefully();
                return;
            }

            outboundExecutor.submit(() -> newReq.onNext(buildArbitrationRequest()))
                    .get(2, TimeUnit.SECONDS);

            // POLLING CHECKPOINT 5: about to await first arbitration response.
            if (closing.get()) {
                newSession.shutdownGracefully();
                return;
            }

            MastershipStatus initial = newHandler.firstResponse().get(
                    DEFAULT_ARBITRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // POLLING CHECKPOINT 6: response received, before swapping session
            // (graceful — the new channel was fully built and BMv2 saw the stream).
            if (closing.get()) {
                newSession.shutdownGracefully();
                return;
            }

            // Swap atomically: install new session, advance generation, attach handler.
            StreamSession oldSession = session.getAndSet(newSession);
            currentGeneration.set(newGen);
            newHandler.attach(this);
            broken.set(false);
            reconnectAttempt.set(0L);
            updateMastership(initial);
            completeReArbitration(initial);
            dispatchMastership(initial);

            // Old session's channel is gone (transport already broken); a graceful
            // shutdown is still polite for any lingering state.
            if (oldSession != null) oldSession.shutdownGracefully();

            // POLLING CHECKPOINT 7: post-swap; if close raced with us, tear the new
            // session down too — close() couldn't see it because we just installed it.
            if (closing.get()) {
                newSession.shutdownGracefully();
            }
        } catch (Exception ex) {
            // Cleanup half-built artifacts.
            if (newSession != null) {
                newSession.shutdownGracefully();
            } else if (newChannel != null) {
                newChannel.shutdownNow();
            }
            // Schedule the next attempt unless we're closing.
            if (!closing.get()) scheduleNextReconnect();
        }
    }

    static Duration arbitrationTimeout() {
        return DEFAULT_ARBITRATION_TIMEOUT;
    }

    private StreamMessageRequest buildArbitrationRequest() {
        Uint128 eid = Uint128.newBuilder().setHigh(electionId.high()).setLow(electionId.low()).build();
        MasterArbitrationUpdate mau = MasterArbitrationUpdate.newBuilder()
                .setDeviceId(deviceId)
                .setElectionId(eid)
                .build();
        return StreamMessageRequest.newBuilder().setArbitration(mau).build();
    }

    private void requireWritable() {
        P4ConnectionException t = writabilityException();
        if (t != null) throw t;
    }

    private P4ConnectionException writabilityException() {
        if (closing.get()) return new P4ConnectionException("switch is closed");
        if (broken.get()) return new P4ConnectionException("stream is broken");
        if (!isPrimary()) {
            return new P4ConnectionException(
                    "cannot write while not primary (election_id=" + electionId
                            + " on device " + deviceId + ")");
        }
        return null;
    }
}
