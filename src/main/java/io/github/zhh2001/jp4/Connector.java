package io.github.zhh2001.jp4;

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

public final class Connector {

    private static final AtomicLong SWITCH_SEQ = new AtomicLong();

    private final String address;
    private long deviceId = 0L;
    private ElectionId electionId = ElectionId.of(1L);
    private ReconnectPolicy reconnectPolicy = ReconnectPolicy.noRetry();
    private int packetInQueueSize = 1024;

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

    public P4Switch asPrimary()   { return open(/*requirePrimary=*/ true); }
    public P4Switch asSecondary() { return open(/*requirePrimary=*/ false); }

    String address()                  { return address; }
    long deviceId()                   { return deviceId; }
    ElectionId electionId()           { return electionId; }
    ReconnectPolicy reconnectPolicy() { return reconnectPolicy; }
    int packetInQueueSize()           { return packetInQueueSize; }

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
                initialSession, initial);
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
