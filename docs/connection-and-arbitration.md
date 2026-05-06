# Connection and arbitration

Every jp4 program starts the same way: open a gRPC channel to a P4Runtime
device and arbitrate for mastership. This guide covers what those steps
actually do and the controller patterns they support.

## P4Runtime mastership in one minute

P4Runtime allows multiple controllers to connect to the same device
simultaneously, but only one is the **primary** at any time — the others
are **secondaries**. The primary is whoever holds the highest
`election_id` among connected clients; that's the only client allowed to
write (push pipelines, insert / modify / delete table entries, send
PacketOut). Secondaries can still **read** (read tables, observe
PacketIn). See P4Runtime spec §6.4 for the full mastership semantics.

In jp4, `P4Switch` represents one client's connection to one device.
Two clients to the same device are two `P4Switch` instances; the JVM /
process boundary does not matter.

## The basic happy path

The 99% case is one primary controller per device:

<!-- snippet: examples/simple-l2-switch/.../SimpleL2Switch.java#main -->

```java
try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    // ... operate ...
}
```

`connectAsPrimary(address)` is shorthand for
`connect(address).asPrimary()` with default deviceId (0) and a generated
election id. If that's not what you want, the longer form is explicit:

<!-- illustrative -->

```java
try (P4Switch sw = P4Switch.connect("127.0.0.1:50051")
        .deviceId(7)
        .electionId(ElectionId.of(0xCAFE))
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(10), /*maxRetries*/ 5))
        .asPrimary()
        .bindPipeline(p4info, deviceConfig)) {

    // ...
}
```

*Real usage: [`network-monitor`](../examples/network-monitor/).*

`asPrimary()` blocks until the device confirms the arbitration outcome.
If the device denies primary (because another client already holds a
higher election id), it throws `P4ArbitrationLost` — a subclass of
`P4ConnectionException` carrying both your election id and the current
primary's.

## Secondary controllers

Read-only and observability controllers connect with a lower election
id and `asSecondary()`:

<!-- snippet: examples/network-monitor/.../NetworkMonitor.java#main (trimmed) -->

```java
try (P4Switch monitor = P4Switch.connect("127.0.0.1:50051")
        .electionId(ElectionId.of(1))
        .asSecondary()) {

    // Secondaries cannot bindPipeline (it's a write); they can loadPipeline,
    // which is a read-only RPC fetching the device's currently-installed P4Info.
    monitor.loadPipeline();

    monitor.packetInStream().subscribe(observer);
    // ... read tables, observe PacketIn ...
}
```

Two practical notes:

- **Secondaries that observe PacketIn must call `loadPipeline()`.** The
  inbound parser needs the metadata schema to decode `controller_packet_metadata`
  fields; without a bound pipeline, `onPacketIn` / `packetInStream` /
  `pollPacketIn` throw `P4PipelineException("no pipeline bound; …")`. This
  is intentional fail-fast — silently dropping every PacketIn would be the
  worst kind of failure mode.
- **PacketIn delivery to secondaries is target-dependent.** P4Runtime spec
  §16.1 says PacketIn MUST be delivered to the primary client and SHOULD
  also be delivered to backups. BMv2 implements only the MUST — secondaries
  do not receive PacketIn from BMv2 even when they have subscribed. Other
  spec-compliant targets may broadcast. See `NOTES.md` "BMv2 PacketIn
  delivery is primary-only" for the empirical detail; the network-monitor
  example explains the design implication.

## Mastership change notifications

Register a listener to react to mastership transitions (e.g. another
controller preempts you, or you previously lost primary and re-acquired
it):

<!-- illustrative: concept fragment -->

```java
sw.onMastershipChange(status -> {
    switch (status) {
        case MastershipStatus.Acquired a -> log("now primary, election_id=" + a.ourElectionId());
        case MastershipStatus.Lost l ->     log("lost primary; current is " + l.currentPrimary());
    }
});
```

`onMastershipChange` is single-replaceable (calling it again replaces the
prior listener), and the callback runs on jp4's single-threaded callback
executor — same FIFO contract as `onPacketIn`. A slow listener holds up
subsequent callbacks but never blocks the gRPC inbound thread.

## Re-claiming primary after loss (HA pattern)

`asPrimary()` is idempotent. If a switch was demoted by a higher election
id and then the holder went away, calling `sw.asPrimary()` re-issues
`MasterArbitrationUpdate` with the original election id and waits for
the device's response:

<!-- illustrative: concept fragment -->

```java
sw.onMastershipChange(status -> {
    if (status.isLost()) {
        reportToOps();
        sw.asPrimary();   // try to re-claim; throws P4ArbitrationLost if denied
    }
});
```

This handles the common HA pattern: a hot-standby controller observes
the active controller drop, and tries to take over.

## Auto-reconnect

`ReconnectPolicy.exponentialBackoff(...)` rebuilds the entire `ManagedChannel`
+ StreamChannel + arbitration on a transport error. Default is
`ReconnectPolicy.noRetry()` — silent failures are still loud (`P4ConnectionException`
on the next op), so explicit retry is opt-in.

<!-- illustrative: concept fragment -->

```java
P4Switch.connect(address)
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100),  // initial delay
                Duration.ofSeconds(10),  // max delay
                5))                      // max retries before giving up
        .asPrimary();
```

After auto-reconnect, the new session re-issues arbitration with the
same election id, so primaries reclaim primary automatically. If the
device denies (someone else became primary while you were disconnected),
the next operation surfaces `P4ConnectionException`; observers via
`onMastershipChange` see the `Lost` status.

## Closing

`P4Switch implements AutoCloseable`. `close()` is idempotent and safe to
call from any context, including from inside a callback. It cancels any
pending reconnect, sends `onCompleted` to the device, shuts down the
internal executors, and closes the gRPC channel.

<!-- illustrative: concept fragment -->

```java
P4Switch sw = P4Switch.connectAsPrimary(address);   // open
// ...
sw.close();   // teardown — or just use try-with-resources
```

After `close()`, every operation throws `P4ConnectionException("switch
is closed")`.

## See also

- [Pipelines](pipeline.md) — what `bindPipeline` and `loadPipeline`
  actually push and fetch.
- [Error handling](error-handling.md) — what to do when a connection
  fails, an arbitration is denied, or the device rejects a write.
- [`examples/network-monitor/`](../examples/network-monitor/) for the
  full primary + secondary pattern in one JVM.
