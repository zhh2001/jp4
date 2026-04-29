package io.github.zhh2001.jp4;

import io.github.zhh2001.jp4.types.ElectionId;

import java.util.Objects;

/**
 * Intermediate stage between {@link P4Switch#connect(String)} and one of the terminals
 * {@link #asPrimary()} / {@link #asSecondary()}. Each setter returns {@code this} so
 * the chain reads as one expression. Defaults: {@code deviceId=0}, {@code electionId=1},
 * {@link ReconnectPolicy#noRetry()}, packet-in queue capacity 1024.
 *
 * <p>Skeleton in 4A: setters are wired but the terminals throw
 * {@link UnsupportedOperationException}. Real arbitration lands in 4B.
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
     * {@code P4ArbitrationLost} if a higher election id already holds primary.
     */
    public P4Switch asPrimary() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
    }

    /**
     * Opens the channel and sends arbitration, returning as soon as the response
     * arrives regardless of whether we won primary. The returned switch's
     * {@code isPrimary()} reflects the actual role; writes throw while not primary.
     */
    public P4Switch asSecondary() {
        throw new UnsupportedOperationException("Not yet implemented in 4A");
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
}
