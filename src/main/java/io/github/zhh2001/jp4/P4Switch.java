package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.entity.UpdateFailure;
import io.github.zhh2001.jp4.error.ErrorCode;
import io.github.zhh2001.jp4.error.OperationType;
import io.github.zhh2001.jp4.error.P4ArbitrationLost;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.error.P4OperationException;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.match.Match;
import io.grpc.Context;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.MatchFieldInfo;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.pipeline.TableInfo;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.ElectionId;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Ip6;
import io.github.zhh2001.jp4.types.Mac;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.Uint128;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.Spliterators;
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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
 * @implNote Write-side methods (insert / modify / delete / batch / send) enforce the
 *           primary-state precondition: a switch obtained via {@code asSecondary()},
 *           or one demoted by a higher election id, surfaces {@link P4ConnectionException}
 *           when a write is attempted. Read-side methods ({@code read},
 *           {@code pollPacketIn}, {@code packetInStream}) work on secondary clients per
 *           P4Runtime spec §6.4; only the gate on closed/broken stream applies.
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

    private final AtomicReference<Pipeline> pipeline = new AtomicReference<>();

    /** PacketIn fan-out plumbing: SubmissionPublisher for the {@code packetInStream}
     *  multi-subscriber path, a bounded deque for {@code pollPacketIn}, and a single
     *  replaceable handler for {@code onPacketIn}. Fan-out semantics — each PacketIn
     *  is delivered to every active sink. See v3 §D6 / §5 Scenario E. */
    private static final int PACKET_QUEUE_CAPACITY = 1024;
    private final java.util.concurrent.SubmissionPublisher<PacketIn> packetPublisher;
    private final java.util.concurrent.LinkedBlockingDeque<PacketIn> packetDeque
            = new java.util.concurrent.LinkedBlockingDeque<>(PACKET_QUEUE_CAPACITY);
    private volatile Consumer<PacketIn> packetHandler;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(P4Switch.class);

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
        // Subscriber callbacks share callbackExecutor with onPacketIn / onMastershipChange,
        // preserving the FIFO single-thread contract. Buffer per subscriber matches the
        // poll-deque capacity for symmetry.
        this.packetPublisher = new java.util.concurrent.SubmissionPublisher<>(
                callbackExecutor, PACKET_QUEUE_CAPACITY);
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

    /**
     * Pushes the supplied {@code p4info} and {@code deviceConfig} to the device via
     * {@code SetForwardingPipelineConfig(VERIFY_AND_COMMIT)}, then atomically caches
     * the pair on the switch. Returns {@code this}.
     *
     * <p>Requires the switch to be primary; secondary switches surface
     * {@link P4ConnectionException} per the standard write-side gate. The internal
     * pipeline cache is updated <b>only</b> after the RPC succeeds — a failed call
     * leaves any previously bound pipeline intact.
     *
     * <p><b>Threading and re-entrancy.</b> This synchronous call blocks the calling
     * thread until the RPC completes (or fails). It is safe to call from a listener
     * callback registered via {@link #onMastershipChange} — RPC completion is
     * delivered by gRPC's executor and does not depend on the listener callback
     * executor, so the call will not deadlock against itself. The asynchronous
     * variant (when added in a later release) returns {@link CompletableFuture} and
     * runs the RPC on an internal executor without blocking the caller.
     *
     * @throws P4ConnectionException if the switch is closed, broken, or not primary
     * @throws P4PipelineException   if the device rejects the pipeline. <b>Note:</b>
     *           some targets (notably BMv2) accept syntactically valid pipelines
     *           without verifying that the supplied {@code p4info} and
     *           {@code deviceConfig} describe the same program; the library does not
     *           perform a consistency check itself, it relays the device's response.
     */
    public P4Switch bindPipeline(P4Info p4info, DeviceConfig deviceConfig) {
        Objects.requireNonNull(p4info, "p4info");
        Objects.requireNonNull(deviceConfig, "deviceConfig");
        requireWritable();
        StreamSession sess = session.get();
        if (sess == null) throw new P4ConnectionException("no active session");

        var config = p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig.newBuilder()
                .setP4Info(p4info.proto())
                .setP4DeviceConfig(com.google.protobuf.ByteString.copyFrom(deviceConfig.bytes()))
                .build();

        var req = p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest.newBuilder()
                .setDeviceId(deviceId)
                .setElectionId(buildElectionUint128())
                .setAction(p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
                .setConfig(config)
                .build();

        try {
            p4.v1.P4RuntimeGrpc.newBlockingStub(sess.channel)
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .setForwardingPipelineConfig(req);
        } catch (io.grpc.StatusRuntimeException sre) {
            throw new io.github.zhh2001.jp4.error.P4PipelineException(
                    "SetForwardingPipelineConfig failed against device " + deviceId
                            + " at " + address + ": " + sre.getStatus(), sre);
        }

        pipeline.set(new Pipeline(p4info, deviceConfig));
        return this;
    }

    /**
     * Fetches the device's currently-bound pipeline via
     * {@code GetForwardingPipelineConfig(ALL)} and caches it on the switch.
     *
     * <p><b>Empty-pipeline contract:</b> if the device responds with
     * {@code OK} but the returned {@code ForwardingPipelineConfig} contains no
     * tables and an empty {@code p4_device_config}, this method throws
     * {@link P4PipelineException} with message {@code "device has no bound pipeline"}.
     * Callers that want to detect "no pipeline" specifically should catch the
     * exception and inspect the message; they should not call {@code loadPipeline}
     * speculatively as a probe.
     *
     * <p><b>Threading and re-entrancy.</b> This synchronous call blocks the calling
     * thread until the RPC completes (or fails). It is safe to call from a listener
     * callback registered via {@link #onMastershipChange} — RPC completion is
     * delivered by gRPC's executor and does not depend on the listener callback
     * executor, so the call will not deadlock against itself. The asynchronous
     * variant (when added in a later release) returns {@link CompletableFuture} and
     * runs the RPC on an internal executor without blocking the caller.
     *
     * @throws P4ConnectionException if the switch is closed or broken
     * @throws P4PipelineException   on RPC failure or empty pipeline
     */
    public P4Switch loadPipeline() {
        if (closing.get()) throw new P4ConnectionException("switch is closed");
        if (broken.get()) throw new P4ConnectionException("stream is broken");
        StreamSession sess = session.get();
        if (sess == null) throw new P4ConnectionException("no active session");

        var req = p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest.newBuilder()
                .setDeviceId(deviceId)
                .setResponseType(
                        p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest.ResponseType.ALL)
                .build();

        p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse resp;
        try {
            resp = p4.v1.P4RuntimeGrpc.newBlockingStub(sess.channel)
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .getForwardingPipelineConfig(req);
        } catch (io.grpc.StatusRuntimeException sre) {
            throw new io.github.zhh2001.jp4.error.P4PipelineException(
                    "GetForwardingPipelineConfig failed against device " + deviceId
                            + " at " + address + ": " + sre.getStatus(), sre);
        }

        var fpc = resp.getConfig();
        if (fpc.getP4Info().getTablesCount() == 0 && fpc.getP4DeviceConfig().isEmpty()) {
            throw new io.github.zhh2001.jp4.error.P4PipelineException(
                    "device has no bound pipeline (device " + deviceId + " at " + address + ")");
        }

        P4Info loadedInfo = P4Info.fromBytes(fpc.getP4Info().toByteArray());
        DeviceConfig loadedConfig = new DeviceConfig.Raw(fpc.getP4DeviceConfig().toByteArray());
        pipeline.set(new Pipeline(loadedInfo, loadedConfig));
        return this;
    }

    /**
     * Snapshot of the {@link Pipeline} currently bound to this switch, or
     * {@code null} if no pipeline has been pushed via {@link #bindPipeline} or
     * fetched via {@link #loadPipeline}.
     */
    public Pipeline pipeline() {
        return pipeline.get();
    }

    /**
     * Inserts a single entry into its table. Synchronous: blocks the calling thread
     * until the device's write RPC completes (default 30 s deadline) or fails.
     *
     * @throws P4ConnectionException if the switch is closed, broken, not primary, or
     *           the RPC times out
     * @throws io.github.zhh2001.jp4.error.P4PipelineException if the entry fails
     *           validation against the bound P4Info (field / action / kind / width)
     * @throws io.github.zhh2001.jp4.error.P4OperationException if the device rejects
     *           the update
     */
    public void insert(TableEntry e) {
        awaitWrite(insertAsync(e));
    }

    /** See {@link #insert} — same semantics with {@code MODIFY} update type. */
    public void modify(TableEntry e) {
        awaitWrite(modifyAsync(e));
    }

    /**
     * Deletes an entry by its match key; the entry's action half (if any) is ignored.
     * See {@link #insert} for thread / exception semantics.
     */
    public void delete(TableEntry e) {
        awaitWrite(deleteAsync(e));
    }

    public CompletableFuture<Void> insertAsync(TableEntry e) {
        return submitWrite(e, p4.v1.P4RuntimeOuterClass.Update.Type.INSERT, OperationType.INSERT);
    }

    public CompletableFuture<Void> modifyAsync(TableEntry e) {
        return submitWrite(e, p4.v1.P4RuntimeOuterClass.Update.Type.MODIFY, OperationType.MODIFY);
    }

    public CompletableFuture<Void> deleteAsync(TableEntry e) {
        return submitWrite(e, p4.v1.P4RuntimeOuterClass.Update.Type.DELETE, OperationType.DELETE);
    }

    public BatchBuilder batch() {
        requireWritable();
        Pipeline pipe = pipeline.get();
        if (pipe == null) {
            throw new io.github.zhh2001.jp4.error.P4PipelineException(
                    "no pipeline bound; call bindPipeline() or loadPipeline() first");
        }
        return new BatchBuilderImpl(pipe);
    }

    /**
     * Starts a read query against the given table. Per P4Runtime spec §6.4 a Read RPC
     * is permitted on secondary clients, so this gate is looser than {@link
     * #requireWritable()}: the switch must not be closed or broken, but mastership is
     * not required.
     *
     * <p>The table name is validated eagerly against the bound P4Info — calling
     * {@code read("typo")} fails immediately with a known-list message rather than
     * deferring the failure to a terminal call. Match-field name validation also
     * happens at terminal time (see {@link ReadQueryImpl#buildReadRequest}).
     *
     * @throws P4ConnectionException if the switch is closed or the stream is broken
     * @throws P4PipelineException if no pipeline is bound or the table name is unknown
     */
    public ReadQuery read(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        P4ConnectionException gate = readabilityException();
        if (gate != null) throw gate;
        Pipeline pipe = pipeline.get();
        if (pipe == null) {
            throw new P4PipelineException(
                    "no pipeline bound; call bindPipeline() or loadPipeline() first");
        }
        // Eager table-existence check; throws P4PipelineException with known-list.
        pipe.p4info().table(tableName);
        return new ReadQueryImpl(tableName, pipe);
    }

    /**
     * Registers a single packet-in handler. Last-write-wins: calling this method
     * again replaces the prior handler. The callback runs on the same single-threaded
     * callback executor as {@code onMastershipChange}, so a slow handler holds up
     * subsequent packet dispatches but does not affect the gRPC inbound thread.
     *
     * <p>Mixes cleanly with {@link #packetInStream()} and {@link #pollPacketIn(Duration)}:
     * each PacketIn is fanned out to the active handler, every subscriber, and the
     * poll deque (see v3 §D6 / §5 Scenario E).
     */
    public void onPacketIn(Consumer<PacketIn> handler) {
        Objects.requireNonNull(handler, "handler");
        packetHandler = handler;
    }

    /**
     * Returns the multi-subscriber {@link Flow.Publisher} fed by inbound PacketIn
     * messages. Each subscriber sees every PacketIn (Reactive Streams fan-out).
     * Subscribing or cancelling does not affect other subscribers, the registered
     * handler, or the poll deque.
     */
    public Flow.Publisher<PacketIn> packetInStream() {
        return packetPublisher;
    }

    /**
     * Pulls the next PacketIn from the inbound deque, waiting up to {@code timeout}.
     * Returns {@link Optional#empty()} on timeout. Independent of the handler / Flow
     * subscribers — every PacketIn is also offered to this deque (capacity
     * {@value #PACKET_QUEUE_CAPACITY}); excess is dropped with a log warning.
     */
    public Optional<PacketIn> pollPacketIn(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long ms = Math.max(0L, timeout.toMillis());
        PacketIn p = packetDeque.pollFirst(ms, TimeUnit.MILLISECONDS);
        return Optional.ofNullable(p);
    }

    /**
     * Sends a single PacketOut to the device via the StreamChannel. Synchronous: blocks
     * the caller until the request has been handed to gRPC's outbound writer (or until
     * it fails). Equivalent to {@code awaitWrite(sendAsync(packet))} — see
     * {@link #sendAsync(PacketOut)} for the future-based variant.
     *
     * @throws P4ConnectionException if the switch is closed, broken, or not primary
     * @throws P4PipelineException   on PacketOut serialisation errors (unknown
     *                               metadata field, value too wide for declared bits)
     */
    public void send(PacketOut packet) {
        awaitWrite(sendAsync(packet));
    }

    /**
     * Async variant of {@link #send(PacketOut)}. Validation / serialisation failures
     * land in the returned future, consistent with {@code insertAsync} / {@code
     * modifyAsync} / {@code deleteAsync} — methods that return a future report
     * failures through the future, never by throwing on the calling thread.
     */
    public CompletableFuture<Void> sendAsync(PacketOut packet) {
        P4ConnectionException gate = writabilityException();
        if (gate != null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(gate);
            return f;
        }
        Objects.requireNonNull(packet, "packet");
        Pipeline pipe = pipeline.get();
        if (pipe == null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new P4PipelineException(
                    "no pipeline bound; call bindPipeline() or loadPipeline() first"));
            return f;
        }

        p4.v1.P4RuntimeOuterClass.PacketOut wirePacket;
        try {
            wirePacket = PacketProto.serialize(packet, pipe.p4info());
        } catch (RuntimeException ve) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ve);
            return f;
        }

        StreamSession sess = session.get();
        if (sess == null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new P4ConnectionException("no active session"));
            return f;
        }

        var req = StreamMessageRequest.newBuilder()
                .setPacket(wirePacket)
                .build();

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            outboundExecutor.execute(() -> {
                try {
                    sess.reqObserver.onNext(req);
                    result.complete(null);
                } catch (RuntimeException re) {
                    result.completeExceptionally(re);
                }
            });
        } catch (RejectedExecutionException ree) {
            result.completeExceptionally(new P4ConnectionException("switch is closed", ree));
        }
        return result;
    }

    /**
     * Package-private, called by {@link InboundStreamHandler#onNext} on the gRPC
     * inbound thread. Parses the wire PacketIn, then fans out to the three sinks:
     * the registered handler (if any) on the callback executor, the
     * {@link SubmissionPublisher} (multi-subscriber, non-blocking offer), and the
     * poll deque (drop on full). All three sinks are independent — failures or
     * back-pressure on one do not affect the others.
     *
     * <p>If parsing throws (unknown metadata id), the packet is dropped with a log
     * warn. Read-side reverse parse is fail-fast on unknown ids; here it is
     * fail-open per-packet, so one malformed packet does not poison the stream.
     */
    void dispatchPacketIn(p4.v1.P4RuntimeOuterClass.PacketIn proto) {
        if (closing.get()) return;
        Pipeline pipe = pipeline.get();
        if (pipe == null) {
            LOG.warn("dropping PacketIn on device {}: no pipeline bound", deviceId);
            return;
        }
        final PacketIn parsed;
        try {
            parsed = PacketProto.parseIn(proto, pipe.p4info());
        } catch (RuntimeException re) {
            LOG.warn("dropping PacketIn on device {} that failed to parse: {}",
                    deviceId, re.getMessage());
            return;
        }
        // 1. Single replaceable handler.
        Consumer<PacketIn> h = packetHandler;
        if (h != null) {
            try {
                callbackExecutor.execute(() -> {
                    try {
                        h.accept(parsed);
                    } catch (RuntimeException re) {
                        LOG.warn("PacketIn handler threw on device {}: {}",
                                deviceId, re.toString());
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // Switch is closing; handler dispatch is best-effort.
            }
        }
        // 2. Flow.Publisher subscribers — non-blocking offer; drop on full.
        if (packetPublisher.hasSubscribers()) {
            packetPublisher.offer(parsed, 0L, TimeUnit.MILLISECONDS, (sub, item) -> {
                LOG.warn("dropping PacketIn for slow subscriber on device {}", deviceId);
                return false;       // drop, do not retry
            });
        }
        // 3. Poll deque — non-blocking offer; drop oldest? No: drop newest with warn.
        //    (Dropping oldest would silently lose packets users had already queued for poll.)
        if (!packetDeque.offerLast(parsed)) {
            LOG.warn("packet deque full (capacity {}) on device {}; dropping PacketIn",
                    PACKET_QUEUE_CAPACITY, deviceId);
        }
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

        // 2b. Tear down PacketIn fan-out: signal subscribers (onComplete), drop the
        //     poll deque, and clear the handler. In-flight callback executor tasks
        //     drain naturally; new dispatches are blocked by the closing flag.
        packetPublisher.close();
        packetDeque.clear();
        packetHandler = null;

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
        MasterArbitrationUpdate mau = MasterArbitrationUpdate.newBuilder()
                .setDeviceId(deviceId)
                .setElectionId(buildElectionUint128())
                .build();
        return StreamMessageRequest.newBuilder().setArbitration(mau).build();
    }

    private Uint128 buildElectionUint128() {
        return Uint128.newBuilder().setHigh(electionId.high()).setLow(electionId.low()).build();
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

    /**
     * Looser counterpart to {@link #writabilityException()}: read RPCs are permitted on
     * secondary clients per P4Runtime spec §6.4, so this gate only fails on closed or
     * broken switches — mastership is irrelevant.
     */
    private P4ConnectionException readabilityException() {
        if (closing.get()) return new P4ConnectionException("switch is closed");
        if (broken.get()) return new P4ConnectionException("stream is broken");
        return null;
    }

    /**
     * Translates a gRPC {@link StatusRuntimeException} from the {@code Read} RPC into a
     * {@link P4OperationException}. Reads do not have per-update failures (each Read
     * either streams or fails wholesale), so {@code failures} is always empty —
     * downstream code consults the top-level {@link ErrorCode}.
     */
    private P4OperationException mapReadFailure(StatusRuntimeException sre) {
        return new P4OperationException(
                "read against device " + deviceId + " failed: " + sre.getStatus(),
                OperationType.READ,
                ErrorCode.fromGrpcCode(sre.getStatus().getCode().value()),
                List.of());
    }

    /**
     * Synchronous wait for a read future; mirrors {@link #awaitWrite} but tags the
     * timeout / interrupt messages with "read" so users can tell the two RPCs apart in
     * stack traces.
     */
    private static <T> T awaitRead(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new P4ConnectionException("read RPC timed out after 30s", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new P4ConnectionException("read RPC failed", cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new P4ConnectionException("interrupted during read", ie);
        }
    }

    /**
     * Validates the entry, builds a single-update {@code WriteRequest}, dispatches
     * it through the outbound serial executor, and returns a future. All write paths
     * (sync and async) go through this — the contract that there is exactly one
     * outbound thread per switch holds.
     */
    private CompletableFuture<Void> submitWrite(TableEntry entry,
                                                p4.v1.P4RuntimeOuterClass.Update.Type updateType,
                                                OperationType opType) {
        // Gate first: closed/broken/secondary takes priority over null-entry check —
        // a switch that's been closed should report the closure, not an NPE that hides it.
        P4ConnectionException gate = writabilityException();
        if (gate != null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(gate);
            return f;
        }
        Objects.requireNonNull(entry, "entry");
        Pipeline pipe = pipeline.get();
        if (pipe == null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new io.github.zhh2001.jp4.error.P4PipelineException(
                    "no pipeline bound; call bindPipeline() or loadPipeline() first"));
            return f;
        }

        // Validation failures are surfaced through the returned future (consistent with the
        // gate / pipeline-null branches above) so callers of *Async never have to catch on
        // both the call-site and the future. Sync wrappers unwrap via awaitWrite().
        try {
            EntryValidator.validate(entry, pipe.p4info(), opType);
        } catch (RuntimeException ve) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ve);
            return f;
        }

        StreamSession sess = session.get();
        if (sess == null) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new P4ConnectionException("no active session"));
            return f;
        }

        var update = p4.v1.P4RuntimeOuterClass.Update.newBuilder()
                .setType(updateType)
                .setEntity(p4.v1.P4RuntimeOuterClass.Entity.newBuilder()
                        .setTableEntry(EntryProto.toProto(entry, pipe.p4info()))
                        .build())
                .build();
        var req = p4.v1.P4RuntimeOuterClass.WriteRequest.newBuilder()
                .setDeviceId(deviceId)
                .setElectionId(buildElectionUint128())
                .addUpdates(update)
                .build();

        return dispatchWrite(req, opType, sess);
    }

    /**
     * Dispatches a fully-built {@code WriteRequest} through the outbound serial
     * executor. Used by single-update writes and by {@link BatchBuilderImpl} alike.
     */
    private CompletableFuture<Void> dispatchWrite(p4.v1.P4RuntimeOuterClass.WriteRequest req,
                                                  OperationType opType,
                                                  StreamSession sess) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            outboundExecutor.execute(() -> {
                try {
                    P4RuntimeGrpc.newBlockingStub(sess.channel)
                            .withDeadlineAfter(30, TimeUnit.SECONDS)
                            .write(req);
                    result.complete(null);
                } catch (StatusRuntimeException sre) {
                    result.completeExceptionally(mapWriteFailure(sre, opType));
                } catch (RuntimeException rex) {
                    result.completeExceptionally(rex);
                }
            });
        } catch (RejectedExecutionException ree) {
            result.completeExceptionally(new P4ConnectionException("switch is closed", ree));
        }
        return result;
    }

    /**
     * Synchronous wait for a write future. Maps {@link TimeoutException} →
     * {@link P4ConnectionException} (the spec answer for "device did not respond"),
     * unwraps {@link ExecutionException} so user code sees the underlying
     * {@code P4PipelineException} / {@code P4OperationException} directly, and
     * restores the interrupt flag on {@link InterruptedException}.
     */
    private static void awaitWrite(CompletableFuture<Void> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new P4ConnectionException("write RPC timed out after 30s", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new P4ConnectionException("write RPC failed", cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new P4ConnectionException("interrupted during write", ie);
        }
    }

    /**
     * Translates a gRPC {@link StatusRuntimeException} from the {@code Write} RPC
     * into a {@link P4OperationException}. When the trailers carry a
     * {@code google.rpc.Status} with details, each detail is unpacked as a
     * P4Runtime {@code Error} and turned into one {@link UpdateFailure} (its
     * {@code index} matches the Update's position in the request).
     *
     * <p>BMv2 sometimes returns just the gRPC status code without per-update details;
     * in that case the resulting {@code P4OperationException} carries an empty
     * failures list — the whole batch is treated as failed.
     */
    private P4OperationException mapWriteFailure(StatusRuntimeException sre, OperationType op) {
        ErrorCode topLevel = ErrorCode.fromGrpcCode(sre.getStatus().getCode().value());
        List<UpdateFailure> failures = new ArrayList<>();
        com.google.rpc.Status statusProto = StatusProto.fromThrowable(sre);
        if (statusProto != null) {
            for (int i = 0; i < statusProto.getDetailsCount(); i++) {
                com.google.protobuf.Any any = statusProto.getDetails(i);
                if (any.is(p4.v1.P4RuntimeOuterClass.Error.class)) {
                    try {
                        var error = any.unpack(p4.v1.P4RuntimeOuterClass.Error.class);
                        ErrorCode code = ErrorCode.fromGrpcCode(error.getCanonicalCode());
                        // BMv2 emits one detail per update, including OK ones for the
                        // updates the device accepted. Only the non-OK details are real
                        // failures; OK details would otherwise pollute WriteResult.failures
                        // and break per-update attribution.
                        if (code == ErrorCode.OK) continue;
                        failures.add(new UpdateFailure(i, code, error.getMessage()));
                    } catch (com.google.protobuf.InvalidProtocolBufferException ignored) {
                        // Detail couldn't be unpacked; skip.
                    }
                }
            }
        }
        return new P4OperationException(
                op + " failed against device " + deviceId + ": " + sre.getStatus(),
                op,
                topLevel,
                failures);
    }

    /**
     * Internal {@link BatchBuilder} implementation. Validates each entry as it is
     * added (so the call site fails fast on a bad entry) and accumulates the wire
     * {@code Update} list. {@link #execute()} sends one {@code WriteRequest} for the
     * whole batch through the same outbound path used by single-update writes.
     */
    private final class BatchBuilderImpl implements BatchBuilder {

        private final Pipeline pipe;
        private final List<p4.v1.P4RuntimeOuterClass.Update> updates = new ArrayList<>();
        private int submitted = 0;

        BatchBuilderImpl(Pipeline pipe) {
            this.pipe = pipe;
        }

        @Override
        public BatchBuilder insert(TableEntry e) {
            return add(e, p4.v1.P4RuntimeOuterClass.Update.Type.INSERT, OperationType.INSERT);
        }

        @Override
        public BatchBuilder modify(TableEntry e) {
            return add(e, p4.v1.P4RuntimeOuterClass.Update.Type.MODIFY, OperationType.MODIFY);
        }

        @Override
        public BatchBuilder delete(TableEntry e) {
            return add(e, p4.v1.P4RuntimeOuterClass.Update.Type.DELETE, OperationType.DELETE);
        }

        @Override
        public WriteResult execute() {
            requireWritable();
            StreamSession sess = session.get();
            if (sess == null) throw new P4ConnectionException("no active session");
            var req = p4.v1.P4RuntimeOuterClass.WriteRequest.newBuilder()
                    .setDeviceId(deviceId)
                    .setElectionId(buildElectionUint128())
                    .addAllUpdates(updates)
                    .build();
            try {
                dispatchWrite(req, OperationType.INSERT, sess).get(30, TimeUnit.SECONDS);
                return new WriteResult(submitted, List.of());
            } catch (TimeoutException te) {
                throw new P4ConnectionException("batch write RPC timed out after 30s", te);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof P4OperationException p4op) {
                    return new WriteResult(submitted, p4op.failures());
                }
                if (cause instanceof RuntimeException re) throw re;
                throw new P4ConnectionException("batch write RPC failed", cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new P4ConnectionException("interrupted during batch write", ie);
            }
        }

        private BatchBuilder add(TableEntry e,
                                 p4.v1.P4RuntimeOuterClass.Update.Type updateType,
                                 OperationType opType) {
            Objects.requireNonNull(e, "entry");
            EntryValidator.validate(e, pipe.p4info(), opType);
            updates.add(p4.v1.P4RuntimeOuterClass.Update.newBuilder()
                    .setType(updateType)
                    .setEntity(p4.v1.P4RuntimeOuterClass.Entity.newBuilder()
                            .setTableEntry(EntryProto.toProto(e, pipe.p4info()))
                            .build())
                    .build());
            submitted++;
            return this;
        }
    }

    /**
     * Internal {@link ReadQuery} implementation. Match-field setters accumulate filters
     * by name; the terminal call ({@link #all()} / {@link #one()} / {@link #stream()})
     * resolves names via the table's {@link MatchFieldInfo} index, builds a
     * {@code ReadRequest}, dispatches it through the outbound serial executor, and
     * decodes each {@code ReadResponse}'s table entries via {@link EntryProto#fromProto}.
     *
     * <p>{@link #all()} / {@link #one()} drain the entire response stream on the
     * outbound thread; {@link #stream()} initiates the call on the outbound thread but
     * leaves consumption on the user thread, with {@link io.grpc.Context} cancellation
     * wiring an early {@code close()} back to the underlying {@code ClientCall}.
     */
    private final class ReadQueryImpl implements ReadQuery {

        private final String tableName;
        private final Pipeline pipe;
        private final Map<String, Match> matches = new LinkedHashMap<>();

        ReadQueryImpl(String tableName, Pipeline pipe) {
            this.tableName = tableName;
            this.pipe = pipe;
        }

        @Override
        public ReadQuery match(String fieldName, Match match) {
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(match, "match");
            matches.put(fieldName, match);
            return this;
        }

        @Override
        public ReadQuery match(String fieldName, Bytes value) {
            Objects.requireNonNull(value, "value");
            return match(fieldName, new Match.Exact(value));
        }

        @Override
        public ReadQuery match(String fieldName, Mac value) {
            Objects.requireNonNull(value, "value");
            return match(fieldName, new Match.Exact(value.toBytes()));
        }

        @Override
        public ReadQuery match(String fieldName, Ip4 value) {
            Objects.requireNonNull(value, "value");
            return match(fieldName, new Match.Exact(value.toBytes()));
        }

        @Override
        public ReadQuery match(String fieldName, Ip6 value) {
            Objects.requireNonNull(value, "value");
            return match(fieldName, new Match.Exact(value.toBytes()));
        }

        @Override
        public ReadQuery match(String fieldName, int value) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "match int value must be non-negative; pass byte[] or Bytes for an "
                                + "explicit bit pattern. Got " + value);
            }
            return match(fieldName, new Match.Exact(Bytes.ofInt(value)));
        }

        @Override
        public ReadQuery match(String fieldName, long value) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        "match long value must be non-negative; pass byte[] or Bytes for an "
                                + "explicit bit pattern. Got " + value);
            }
            return match(fieldName, new Match.Exact(Bytes.ofLong(value)));
        }

        @Override
        public ReadQuery match(String fieldName, byte[] value) {
            Objects.requireNonNull(value, "value");
            return match(fieldName, new Match.Exact(Bytes.of(value)));
        }

        @Override
        public List<TableEntry> all() {
            return awaitRead(allAsync());
        }

        @Override
        public Optional<TableEntry> one() {
            return collapseToOne(all());
        }

        @Override
        public CompletableFuture<List<TableEntry>> allAsync() {
            P4ConnectionException gate = readabilityException();
            if (gate != null) {
                CompletableFuture<List<TableEntry>> f = new CompletableFuture<>();
                f.completeExceptionally(gate);
                return f;
            }
            StreamSession sess = session.get();
            if (sess == null) {
                CompletableFuture<List<TableEntry>> f = new CompletableFuture<>();
                f.completeExceptionally(new P4ConnectionException("no active session"));
                return f;
            }

            p4.v1.P4RuntimeOuterClass.ReadRequest req;
            try {
                req = buildReadRequest();
            } catch (RuntimeException ve) {
                CompletableFuture<List<TableEntry>> f = new CompletableFuture<>();
                f.completeExceptionally(ve);
                return f;
            }

            CompletableFuture<List<TableEntry>> result = new CompletableFuture<>();
            try {
                outboundExecutor.execute(() -> {
                    try {
                        Iterator<p4.v1.P4RuntimeOuterClass.ReadResponse> it =
                                P4RuntimeGrpc.newBlockingStub(sess.channel)
                                        .withDeadlineAfter(30, TimeUnit.SECONDS)
                                        .read(req);
                        List<TableEntry> entries = new ArrayList<>();
                        while (it.hasNext()) {
                            extractInto(it.next(), entries);
                        }
                        result.complete(entries);
                    } catch (StatusRuntimeException sre) {
                        result.completeExceptionally(mapReadFailure(sre));
                    } catch (RuntimeException re) {
                        result.completeExceptionally(re);
                    }
                });
            } catch (RejectedExecutionException ree) {
                result.completeExceptionally(new P4ConnectionException("switch is closed", ree));
            }
            return result;
        }

        @Override
        public CompletableFuture<Optional<TableEntry>> oneAsync() {
            return allAsync().thenApply(this::collapseToOne);
        }

        @Override
        public Stream<TableEntry> stream() {
            P4ConnectionException gate = readabilityException();
            if (gate != null) throw gate;
            StreamSession sess = session.get();
            if (sess == null) throw new P4ConnectionException("no active session");
            p4.v1.P4RuntimeOuterClass.ReadRequest req = buildReadRequest();

            // Cancellable context: cancel(null) on close propagates through gRPC
            // Context binding to ClientCall.cancel().
            Context.CancellableContext ctx = Context.current().withCancellation();

            CompletableFuture<Iterator<p4.v1.P4RuntimeOuterClass.ReadResponse>> startFuture =
                    new CompletableFuture<>();
            try {
                outboundExecutor.execute(() -> {
                    try {
                        // ctx.call binds the call's Context at start() to ctx so cancel
                        // propagates. Returns immediately; gRPC iteration is lazy.
                        Iterator<p4.v1.P4RuntimeOuterClass.ReadResponse> it = ctx.call(() ->
                                P4RuntimeGrpc.newBlockingStub(sess.channel).read(req));
                        startFuture.complete(it);
                    } catch (Exception e) {
                        startFuture.completeExceptionally(e);
                    }
                });
            } catch (RejectedExecutionException ree) {
                ctx.cancel(null);
                throw new P4ConnectionException("switch is closed", ree);
            }

            Iterator<p4.v1.P4RuntimeOuterClass.ReadResponse> respIter;
            try {
                respIter = startFuture.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                ctx.cancel(null);
                throw new P4ConnectionException("read RPC timed out before stream start", te);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                ctx.cancel(null);
                throw new P4ConnectionException("interrupted while starting read stream", ie);
            } catch (ExecutionException ee) {
                ctx.cancel(null);
                Throwable cause = ee.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new P4ConnectionException("read RPC failed to start", cause);
            }

            Iterator<TableEntry> entryIter = flatten(respIter, pipe.p4info());
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(entryIter, Spliterator.ORDERED),
                    /* parallel */ false
            ).onClose(() -> ctx.cancel(null));
        }

        // ---------- helpers ------------------------------------------------

        private p4.v1.P4RuntimeOuterClass.ReadRequest buildReadRequest() {
            // Re-resolve table; pipeline could in theory have been swapped between
            // .read("table") returning and a terminal call here. Practically
            // bindPipeline replaces the AtomicReference atomically and we captured a
            // Pipeline snapshot at .read() time, so this is just defensive.
            TableInfo table = pipe.p4info().table(tableName);
            var teBuilder = p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
                    .setTableId(table.id());

            for (Map.Entry<String, Match> me : matches.entrySet()) {
                MatchFieldInfo field = lookupMatchField(table, me.getKey());
                teBuilder.addMatch(EntryProto.matchToProto(field, me.getValue()));
            }

            var entity = p4.v1.P4RuntimeOuterClass.Entity.newBuilder()
                    .setTableEntry(teBuilder.build())
                    .build();
            return p4.v1.P4RuntimeOuterClass.ReadRequest.newBuilder()
                    .setDeviceId(deviceId)
                    .addEntities(entity)
                    .build();
        }

        private MatchFieldInfo lookupMatchField(TableInfo table, String fieldName) {
            try {
                return table.matchField(fieldName);
            } catch (P4PipelineException notFound) {
                List<String> known = new ArrayList<>(table.matchFields().size());
                for (MatchFieldInfo mf : table.matchFields()) known.add(mf.name());
                throw new P4PipelineException(
                        "Field '" + fieldName + "' not found in table '" + table.name()
                                + "'. Known fields: " + known);
            }
        }

        private void extractInto(p4.v1.P4RuntimeOuterClass.ReadResponse resp,
                                 List<TableEntry> out) {
            for (p4.v1.P4RuntimeOuterClass.Entity e : resp.getEntitiesList()) {
                if (e.hasTableEntry()) {
                    out.add(EntryProto.fromProto(e.getTableEntry(), pipe.p4info()));
                }
                // Other entity types (counter / meter / action_profile_member /
                // packet_replication / etc.) are v0.2 work — silently skipped here.
            }
        }

        private Iterator<TableEntry> flatten(
                Iterator<p4.v1.P4RuntimeOuterClass.ReadResponse> respIter,
                P4Info p4info) {
            return new Iterator<>() {
                Iterator<TableEntry> currentBatch = Collections.emptyIterator();

                @Override
                public boolean hasNext() {
                    try {
                        while (!currentBatch.hasNext() && respIter.hasNext()) {
                            List<TableEntry> batch = new ArrayList<>();
                            extractInto(respIter.next(), batch);
                            currentBatch = batch.iterator();
                        }
                        return currentBatch.hasNext();
                    } catch (StatusRuntimeException sre) {
                        throw mapReadFailure(sre);
                    }
                }

                @Override
                public TableEntry next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return currentBatch.next();
                }
            };
        }

        private Optional<TableEntry> collapseToOne(List<TableEntry> all) {
            if (all.isEmpty()) return Optional.empty();
            if (all.size() == 1) return Optional.of(all.get(0));
            throw new P4OperationException(
                    "expected at most one entry from table '" + tableName
                            + "', got " + all.size() + " entries",
                    OperationType.READ,
                    ErrorCode.UNKNOWN,
                    List.of());
        }
    }
}
