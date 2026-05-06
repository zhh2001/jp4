# Packet I/O

P4Runtime devices send packets to the controller (PacketIn) and accept
packets from the controller (PacketOut) over the bidirectional
StreamChannel. jp4 exposes three styles for consuming PacketIn — pick
whichever matches your program's concurrency model — and a single
synchronous-or-async send for PacketOut.

## The three PacketIn styles

<!-- illustrative: concept fragment -->

```java
sw.onPacketIn(packet -> ...);                            // 1. callback (common case)
sw.packetInStream().subscribe(subscriber);               // 2. Flow.Publisher (multi-subscriber, backpressure)
sw.pollPacketIn(Duration.ofSeconds(1)).ifPresent(...);   // 3. blocking pull
```

All three feed off the same internal packet stream. Each PacketIn is
delivered to **the active `onPacketIn` handler, every `packetInStream`
subscriber, and the `pollPacketIn` deque**. Mixing styles is supported
and well-defined; the active `onPacketIn` handler is single (calling
`onPacketIn` again replaces the prior handler), the subscriber list is
unbounded.

A few orientation notes:

- All three throw `P4PipelineException("no pipeline bound; …")` if
  called before `bindPipeline` or `loadPipeline`. This is fail-fast on
  purpose: PacketIn metadata cannot be parsed without P4Info, and
  silently dropping every packet would be the worst kind of failure.
- Callbacks run on jp4's single-threaded callback executor — same FIFO
  contract as `onMastershipChange`. A slow handler holds up subsequent
  packet dispatches but never blocks the gRPC inbound thread.
- The poll deque has capacity 1024; on overflow, the oldest unread
  packet is dropped with a SLF4J `WARN` log line.

## Style 1 — `onPacketIn(Consumer<PacketIn>)`

The 90% case. Register one handler, jp4 calls it per packet:

<!-- snippet: examples/simple-l2-switch/.../SimpleL2Switch.java#handlePacketIn -->

```java
sw.onPacketIn(packet -> {
    int ingressPort = packet.metadataInt("ingress_port");
    byte[] frame = packet.payload().toByteArray();
    log.info("PacketIn  ingress=" + ingressPort + " bytes=" + frame.length);
    // ...
});
```

`packet.metadata(name)` returns the raw `Bytes` for any
`controller_packet_metadata` field declared on the `packet_in` header.
`metadataInt(name)` is a convenience for fields that fit in a Java
`int` (≤ 31 bits); it throws `IllegalStateException` if the field is
absent or wider.

Calling `onPacketIn` again replaces the previous handler. There is no
`removePacketInHandler()` — pass `p -> {}` if you need to disable
dispatch without closing the switch.

## Style 2 — `packetInStream()`

Returns a `Flow.Publisher<PacketIn>` (JDK 9+, no extra dependency). Each
subscriber sees every packet:

<!-- snippet: examples/network-monitor/.../NetworkMonitor.java (illustrative — the example uses an inline class rather than an anonymous one) -->

```java
sw.packetInStream().subscribe(new Flow.Subscriber<>() {
    @Override public void onSubscribe(Flow.Subscription s) {
        s.request(Long.MAX_VALUE);
    }
    @Override public void onNext(PacketIn p) {
        // process p
    }
    @Override public void onError(Throwable t) { /* ... */ }
    @Override public void onComplete()         { /* device closed stream */ }
});
```

The full `network-monitor` run (verbatim from a real run) shows a
primary-injects + Flow-subscriber-tallies cycle, plus a brief secondary
that demonstrates `loadPipeline()` works without primary privileges:

```
[MON] primary connected (election_id=10), pipeline pushed
[MON] secondary connected (election_id=1)
[MON] secondary mastership: Lost[previousElectionId=null, currentPrimaryElectionId=ElectionId(10)]
[MON] secondary loadPipeline() OK — spec §6.4 read-without-primary verified
[MON] injecting 30 synthetic frames at 30ms intervals…
[MON] observed 30 / 30 expected packets
[MON]   port 1: 8 packets, 304 bytes total, 38 avg
[MON]   port 2: 8 packets, 336 bytes total, 42 avg
[MON]   port 3: 7 packets, 314 bytes total, 44 avg
[MON]   port 4: 7 packets, 342 bytes total, 48 avg
[MON] stream completed
```

The `Flow.Subscriber` runs on the primary's `packetInStream` because
BMv2 only delivers PacketIn to the primary client (spec §16.1 says MUST
primary, SHOULD backups; BMv2 only does the MUST). On a target that
broadcasts to backups, the same subscriber attaches unchanged to a
secondary. See `NOTES.md` "BMv2 PacketIn delivery is primary-only".

Use this style when you want backpressure (request fewer items than
`Long.MAX_VALUE`), or when your application is already reactive and
adapting from `Flow.Publisher` to your reactive stack is one line:

<!-- illustrative: concept fragment -->

```java
// Reactor:
Flux<PacketIn> flux = reactor.adapter.JdkFlowAdapter
        .flowPublisherToFlux(sw.packetInStream());

// RxJava 3 (via Reactive Streams adapter):
Flowable<PacketIn> flow = Flowable.fromPublisher(
        org.reactivestreams.FlowAdapters.toPublisher(sw.packetInStream()));
```

`subscribe()` and `cancel()` are independent across subscribers —
cancelling one subscription has no effect on the others, the registered
`onPacketIn` handler, or the poll deque. On `sw.close()`, every
subscriber's `onComplete()` fires.

## Style 3 — `pollPacketIn(Duration)`

Blocks the calling thread until a packet arrives or the timeout elapses:

<!-- illustrative: concept fragment -->

```java
Optional<PacketIn> p = sw.pollPacketIn(Duration.ofSeconds(1));
p.ifPresent(this::process);
```

Returns `Optional.empty()` on timeout. Use this style for scripted
single-controller programs that want a procedural read loop without
introducing an executor or a reactive stack.

## Sending PacketOut

Synchronous send:

<!-- illustrative -->

```java
sw.send(PacketOut.builder()
        .payload(rawBytes)
        .metadata("egress_port", 1)
        .build());
```

*Real usage: [`simple-l2-switch`](../examples/simple-l2-switch/).*

Async variant returns a `CompletableFuture<Void>`:

<!-- illustrative: concept fragment -->

```java
sw.sendAsync(PacketOut.builder()
        .payload(rawBytes)
        .metadata("egress_port", 1)
        .build());
```

`PacketOut` is built by `PacketOut.builder()`. `payload(byte[])` /
`payload(Bytes)` set the raw bytes the device emits.
`metadata(name, value)` accepts `int` / `long` / `Bytes` / `byte[]` /
`Mac`; the value is canonicalised to the bit width declared in P4Info
when serialised on the wire. Negative `int`/`long` is rejected with
`IllegalArgumentException`.

`PacketOut` is immutable and reusable — the same instance is safe to
send multiple times.

`send` requires primary mastership (P4Runtime spec §6.1: PacketOut is a
write-side operation). Secondaries calling `send` get
`P4ConnectionException("not primary")`. PacketIn, in contrast, is open
to secondaries.

## controller_packet_metadata declarations

The `packet_in` and `packet_out` headers are declared in your P4 program
and surfaced by p4c into the P4Info as `controller_packet_metadata`
entries:

```p4
@controller_header("packet_in")
header packet_in_h {
    bit<9> ingress_port;
    bit<7> _pad;
}

@controller_header("packet_out")
header packet_out_h {
    bit<9> egress_port;
    bit<7> _pad;
}
```

Each field becomes a name-addressable metadata slot in jp4: read via
`PacketIn.metadata("ingress_port")`, write via
`PacketOut.builder().metadata("egress_port", 1)`. The `_pad` field
exists to align the packed bitstream to a byte boundary (9 + 7 = 16
bits = 2 bytes); jp4 sets `_pad` to zero on PacketOut and ignores it on
PacketIn unless you read it explicitly.

`P4Info` exposes the declarations programmatically:

<!-- illustrative: concept fragment -->

```java
List<PacketMetadataInfo> in  = p4info.packetInMetadata();
List<PacketMetadataInfo> out = p4info.packetOutMetadata();
```

## Concurrency rules

- `onPacketIn` callback, `Flow.Subscriber.onNext`, and the
  `onMastershipChange` listener share **one** callback executor. They
  never run concurrently. Slow code in any of them slows the others.
- `sw.send(...)` from multiple threads is safe; the outbound executor
  serialises packets onto the StreamChannel.
- A `PacketIn` handler calling `sw.send(...)` does **not** deadlock —
  the handler runs on the callback executor, send waits on the outbound
  executor, different threads.

## See also

- [Connection and arbitration](connection-and-arbitration.md) — primary
  vs secondary, and the `loadPipeline()` pre-requisite for secondaries
  observing PacketIn.
- [`examples/simple-l2-switch/`](../examples/simple-l2-switch/) for
  the `onPacketIn` callback style end-to-end.
- [`examples/network-monitor/`](../examples/network-monitor/) for the
  `packetInStream()` Flow.Publisher style.
