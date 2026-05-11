package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.error.P4ArbitrationLost;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.types.ElectionId;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate;
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest;
import p4.v1.P4RuntimeOuterClass.Uint128;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Intermediate stage between {@link P4Switch#connect(String)} and one of the terminals
 * {@link #asPrimary()} / {@link #asSecondary()}. Each setter returns {@code this} so
 * the chain reads as one expression. Defaults: {@code deviceId=0}, {@code electionId=1},
 * {@link ReconnectPolicy#noRetry()}, packet-in queue capacity 1024.
 *
 * <p>Not safe for concurrent use; one instance should be confined to a single
 * thread. The fluent chain mutates internal state, so external synchronization
 * is required to share a builder across threads. Once {@link #asPrimary()} or
 * {@link #asSecondary()} returns, the produced {@link P4Switch} is thread-safe
 * per its own threading-model documentation.
 *
 * @since 0.1.0
 */
public final class Connector {

    private static final AtomicLong SWITCH_SEQ = new AtomicLong();

    private final String address;
    private long deviceId = 0L;
    private ElectionId electionId = ElectionId.of(1L);
    private ReconnectPolicy reconnectPolicy = ReconnectPolicy.noRetry();
    private int packetInQueueSize = 1024;
    private boolean preserveRoleOnReconnect = false;
    private Predicate<? super PacketIn> packetInFilter = null;

    Connector(String address) {
        this.address = Objects.requireNonNull(address, "address");
    }

    public Connector deviceId(long id) {
        this.deviceId = id;
        return this;
    }

    public Connector electionId(long low) {
        this.electionId = ElectionId.of(low);
        return this;
    }

    public Connector electionId(ElectionId id) {
        this.electionId = Objects.requireNonNull(id, "id");
        return this;
    }

    public Connector reconnectPolicy(ReconnectPolicy policy) {
        this.reconnectPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    public Connector packetInQueueSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("packetInQueueSize must be > 0, got " + size);
        }
        this.packetInQueueSize = size;
        return this;
    }

    /**
     * Configures the switch to fail fast if its mastership role on auto-reconnect
     * differs from the role originally requested via {@link #asPrimary()}. When
     * enabled and the device assigns a non-Primary role on reconnect (because
     * another client arbitrated with a higher election id during the disconnect
     * window), the switch closes itself and stores the resulting
     * {@link io.github.zhh2001.jp4.error.P4ArbitrationLost} as the close cause;
     * subsequent calls on the switch (write / read / send / etc.) throw that
     * stored exception via the existing writability and readability gates,
     * carrying both the original election id and the device's new primary
     * election id so the application can retry with a higher id.
     *
     * <p>Default: {@code false}. The unchanged behaviour is to keep the switch
     * open as Secondary and emit a {@code MastershipStatus.Lost} callback only;
     * subsequent writes fail one by one against the writability gate. The flag
     * is a no-op for connectors that started as {@link #asSecondary()}, since a
     * post-reconnect Primary is the user yielding intentionally rather than a
     * downgrade.
     *
     * <p>Recovery pattern in primary-mandatory applications:
     *
     * <pre>{@code
     * try (P4Switch sw = P4Switch.connect(addr)
     *         .electionId(myEid)
     *         .reconnectPolicy(...)
     *         .preserveRoleOnReconnect(true)
     *         .asPrimary()) {
     *     ...
     * } catch (P4ArbitrationLost ex) {
     *     ElectionId higher = ElectionId.of(
     *             ex.currentPrimaryElectionId().low() + 1);
     *     // reconnect with the higher id, retry application work
     * }
     * }</pre>
     *
     * @param enable {@code true} to opt into fail-fast on reconnect role
     *               downgrade; {@code false} for the v1.0 silent-Secondary
     *               behaviour
     * @return this builder, for chaining
     * @since 1.1.0
     */
    public Connector preserveRoleOnReconnect(boolean enable) {
        this.preserveRoleOnReconnect = enable;
        return this;
    }

    /**
     * Configures a predicate that runs against every parsed inbound
     * {@link PacketIn} after parsing and before the fan-out to the registered
     * handler, {@code Flow.Publisher} subscribers, and the poll deque. A
     * packet for which the predicate returns {@code false} is dropped — no
     * sink sees it — and a {@code DropEvent} with reason {@code FILTERED} is
     * dispatched through the listener registered via
     * {@link P4Switch#onPacketDropped(java.util.function.Consumer)}. Unlike
     * the {@code SUBSCRIBER_LAG} and {@code QUEUE_FULL} dispatch sites, no
     * WARN log is emitted on the normal filter-reject path: the filter is a
     * user-supplied opt-in choice, not a runtime anomaly worth surfacing to
     * ops grep.
     *
     * <p>A filter that throws a {@link RuntimeException} is treated as a
     * drop: the dispatch path catches the exception, logs at WARN (because a
     * filter that throws is an application bug worth surfacing), and fires a
     * {@code FILTERED} {@code DropEvent} whose {@code message} names the
     * thrown exception's simple class name. The application-side listener
     * can distinguish normal-reject ({@code "rejected by packetInFilter"})
     * from filter-threw ({@code "packetInFilter threw <ClassName>"}) on the
     * message component.
     *
     * <p>Default is {@code null} — every packet passes through unchanged.
     * Setting a filter is a pure additive change for v1.0 / v1.1 callers; no
     * SemVer impact.
     *
     * <p>The signature accepts {@code Predicate<? super PacketIn>} so callers
     * can supply a {@code Predicate<Object>} or {@code Predicate<PacketIn>}
     * alike, matching the PECS shape of
     * {@code ReadQuery.where(Predicate<? super TableEntry>)}.
     *
     * @param filter the predicate to apply to each parsed PacketIn; never
     *               {@code null}
     * @return this builder, for chaining
     * @throws NullPointerException if {@code filter} is null
     * @since 1.2.0
     */
    public Connector packetInFilter(Predicate<? super PacketIn> filter) {
        this.packetInFilter = Objects.requireNonNull(filter, "filter");
        return this;
    }

    public P4Switch asPrimary()   { return open(/*requirePrimary=*/ true); }
    public P4Switch asSecondary() { return open(/*requirePrimary=*/ false); }

    String address()                              { return address; }
    long deviceId()                               { return deviceId; }
    ElectionId electionId()                       { return electionId; }
    ReconnectPolicy reconnectPolicy()             { return reconnectPolicy; }
    int packetInQueueSize()                       { return packetInQueueSize; }
    boolean preserveRoleOnReconnect()             { return preserveRoleOnReconnect; }
    Predicate<? super PacketIn> packetInFilter()  { return packetInFilter; }

    // ---------- internal -----------------------------------------------------

    private P4Switch open(boolean requirePrimary) {
        long switchId = SWITCH_SEQ.incrementAndGet();

        // Build executors with name + thread-reference capture for self-detection in close().
        Thread[] outboundThreadRef = new Thread[1];
        Thread[] callbackThreadRef = new Thread[1];
        ExecutorService outboundExec = Executors.newSingleThreadExecutor(capturingFactory(
                "jp4-sw" + switchId + "-outbound", outboundThreadRef));
        ExecutorService callbackExec = Executors.newSingleThreadExecutor(capturingFactory(
                "jp4-sw" + switchId + "-callback", callbackThreadRef));
        ScheduledExecutorService reconnectExec = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("jp4-sw" + switchId + "-reconnect"));

        // Force the executor threads to start so we capture the Thread reference NOW
        // (before the P4Switch constructor needs them).
        primeExecutor(outboundExec);
        primeExecutor(callbackExec);

        ManagedChannel channel = buildChannel(address);
        InboundStreamHandler handler = new InboundStreamHandler(electionId, /*generation=*/ 0L);

        StreamObserver<StreamMessageRequest> reqObserver;
        try {
            reqObserver = P4RuntimeGrpc.newStub(channel).streamChannel(handler);
        } catch (RuntimeException e) {
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ConnectionException("failed to open StreamChannel to " + address, e);
        }
        StreamSession initialSession = new StreamSession(0L, channel, handler, reqObserver);

        // Send arbitration through the serial outbound executor (mirrors the contract
        // every other outbound RPC will follow).
        Uint128 eid = Uint128.newBuilder().setHigh(electionId.high()).setLow(electionId.low()).build();
        MasterArbitrationUpdate mau = MasterArbitrationUpdate.newBuilder()
                .setDeviceId(deviceId)
                .setElectionId(eid)
                .build();
        StreamMessageRequest req = StreamMessageRequest.newBuilder().setArbitration(mau).build();
        try {
            outboundExec.submit(() -> reqObserver.onNext(req)).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ConnectionException("interrupted sending arbitration to " + address, ie);
        } catch (ExecutionException ee) {
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof StatusRuntimeException sre) {
                throw new P4ConnectionException("transport error sending arbitration to " + address, sre);
            }
            throw new P4ConnectionException("failed sending arbitration to " + address, cause);
        } catch (TimeoutException te) {
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ConnectionException("timeout sending arbitration to " + address, te);
        }

        // Wait for first arbitration response.
        MastershipStatus initial;
        try {
            initial = handler.firstResponse().get(
                    P4Switch.arbitrationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ConnectionException("interrupted waiting for arbitration from " + address, ie);
        } catch (ExecutionException ee) {
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            Throwable cause = ee.getCause();
            if (cause instanceof P4ConnectionException pce) throw pce;
            throw new P4ConnectionException("arbitration failed against " + address, cause);
        } catch (TimeoutException te) {
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ConnectionException(
                    "no arbitration response from " + address + " within "
                            + P4Switch.arbitrationTimeout(), te);
        }

        if (requirePrimary && initial.isLost()) {
            ElectionId currentPrimary = ((MastershipStatus.Lost) initial).currentPrimaryElectionId();
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ArbitrationLost(
                    "device " + deviceId + " at " + address + " denied primary; current primary="
                            + currentPrimary, electionId, currentPrimary);
        }

        P4Switch sw = new P4Switch(
                address, deviceId, electionId, reconnectPolicy,
                outboundExec, callbackExec, reconnectExec,
                outboundThreadRef[0], callbackThreadRef[0],
                initialSession, initial,
                requirePrimary, preserveRoleOnReconnect,
                packetInFilter);
        handler.attach(sw);
        return sw;
    }

    private static void primeExecutor(ExecutorService exec) {
        try {
            exec.submit(() -> { /* warm-up so the thread is created */ }).get(1, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            // Should not happen for a freshly created single-thread executor.
            throw new IllegalStateException("could not prime executor", e);
        }
    }

    private static ManagedChannel buildChannel(String address) {
        return NettyChannelBuilder
                .forTarget(address)
                .usePlaintext()
                .build();
    }

    private static ThreadFactory capturingFactory(String name, Thread[] holder) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            holder[0] = t;
            return t;
        };
    }

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static void shutdownAll(ManagedChannel channel,
                                    ExecutorService a, ExecutorService b, ExecutorService c) {
        a.shutdownNow();
        b.shutdownNow();
        c.shutdownNow();
        channel.shutdownNow();
    }
}
