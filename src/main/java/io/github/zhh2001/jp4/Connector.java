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

/**
 * Intermediate stage between {@link P4Switch#connect(String)} and one of the terminals
 * {@link #asPrimary()} / {@link #asSecondary()}. Each setter returns {@code this} so
 * the chain reads as one expression. Defaults: {@code deviceId=0}, {@code electionId=1},
 * {@link ReconnectPolicy#noRetry()}, packet-in queue capacity 1024.
 */
public final class Connector {

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

    /** Convenience: sets election id with high=0, low={@code low}. */
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

    /** Maximum number of unconsumed PacketIn messages buffered before drops are reported. */
    public Connector packetInQueueSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("packetInQueueSize must be > 0, got " + size);
        }
        this.packetInQueueSize = size;
        return this;
    }

    /**
     * Opens the channel, sends {@code MasterArbitrationUpdate} with the configured
     * election id, and blocks until the response confirms primary. Throws
     * {@link P4ArbitrationLost} if a higher election id already holds primary, or
     * {@link P4ConnectionException} for any transport-level failure or timeout.
     */
    public P4Switch asPrimary() {
        return open(/*requirePrimary=*/ true);
    }

    /**
     * Opens the channel and sends arbitration, returning as soon as the response
     * arrives regardless of whether we won primary. The returned switch's
     * {@code isPrimary()} reflects the actual role; writes throw while not primary.
     */
    public P4Switch asSecondary() {
        return open(/*requirePrimary=*/ false);
    }

    String address() {
        return address;
    }

    long deviceId() {
        return deviceId;
    }

    ElectionId electionId() {
        return electionId;
    }

    ReconnectPolicy reconnectPolicy() {
        return reconnectPolicy;
    }

    int packetInQueueSize() {
        return packetInQueueSize;
    }

    // ---------- internal -----------------------------------------------------

    private P4Switch open(boolean requirePrimary) {
        ManagedChannel channel = buildChannel(address);
        ExecutorService outboundExec = newSingleDaemon("jp4-outbound");
        ExecutorService callbackExec = newSingleDaemon("jp4-callback");
        ScheduledExecutorService reconnectExec = newSingleDaemonScheduled("jp4-reconnect");
        InboundStreamHandler handler = new InboundStreamHandler(electionId);

        StreamObserver<StreamMessageRequest> reqObserver;
        try {
            reqObserver = P4RuntimeGrpc.newStub(channel).streamChannel(handler);
        } catch (RuntimeException e) {
            shutdownAll(channel, outboundExec, callbackExec, reconnectExec);
            throw new P4ConnectionException("failed to open StreamChannel to " + address, e);
        }

        // Send arbitration through the serial outbound executor, mirroring the contract
        // every other outbound RPC will follow.
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

        // Wait for the device's first arbitration response.
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
                address, deviceId, electionId,
                channel, reqObserver,
                outboundExec, callbackExec, reconnectExec,
                initial);
        handler.attach(sw);
        return sw;
    }

    private static ManagedChannel buildChannel(String address) {
        // address is "host:port"; let NettyChannelBuilder parse it.
        return NettyChannelBuilder
                .forTarget(address)
                .usePlaintext()
                .build();
    }

    private static final AtomicLong THREAD_SEQ = new AtomicLong();

    private static ExecutorService newSingleDaemon(String prefix) {
        return Executors.newSingleThreadExecutor(daemonFactory(prefix));
    }

    private static ScheduledExecutorService newSingleDaemonScheduled(String prefix) {
        return Executors.newSingleThreadScheduledExecutor(daemonFactory(prefix));
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + "-" + THREAD_SEQ.incrementAndGet());
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
