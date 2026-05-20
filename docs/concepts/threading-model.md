---
title: Threading model
description: jp4's three internal executors — the gRPC inbound thread, the single-threaded callback executor, and the single-threaded outbound executor — what each one runs, FIFO and serialisation contracts, and the deadlock-free guarantee for a callback that calls send.
keywords: [jp4, P4Runtime, threading, callback executor, outbound executor, gRPC, deadlock, concurrency]
---

# Threading model

jp4 has three internal threads-of-execution per `P4Switch`. Two are
single-threaded executors that jp4 owns; the third is the gRPC inbound
thread that grpc-java owns. Understanding which code runs on which
executor is the difference between a controller that scales naturally
and one that mysteriously deadlocks or drops events.

This page explains the three executors, the FIFO contracts each one
guarantees, the deadlock-freedom of the callback-calling-send pattern,
and the rules that govern concurrent caller threads.

## The three executors

**gRPC inbound thread** — owned by grpc-java. Each `P4Switch` has a
single bidirectional StreamChannel; everything the device sends —
`MasterArbitrationUpdate` replies, `PacketIn`, `DigestList`,
`IdleTimeoutNotification` — arrives on this thread. jp4 does not run
user code on the inbound thread. The inbound handler's only job is to
parse the proto, fan it out to the appropriate sink (the callback
executor's queue, the `Flow.Publisher` subscribers, or the poll
deque), and return immediately. A slow callback can never block the
inbound thread.

**Callback executor** — single-threaded, owned by jp4. Runs every
user-supplied listener:

- `sw.onPacketIn(Consumer<PacketIn>)`
- `sw.onMastershipChange(Consumer<MastershipStatus>)`
- `sw.onDigest(Consumer<DigestEvent>)`
- `sw.onIdleTimeout(Consumer<IdleTimeoutEvent>)`
- `sw.onPacketDropped(Consumer<DropEvent>)`
- Every `Flow.Subscriber.onNext` for `sw.packetInStream()` subscribers

These all share the same callback executor. They never run
concurrently. A slow listener holds up subsequent listener dispatches
on the same switch — packet 2 waits for packet 1's handler to return,
a mastership change waits for the current packet to finish processing,
etc. The contract is FIFO across all listener types: events are
dispatched in the order the inbound thread fanned them out.

**Outbound executor** — single-threaded, owned by jp4. Runs every
write-side operation:

- `sw.insert(entry)` / `modify` / `delete` / their `*Async` variants
- `sw.batch().…​.execute()`
- `sw.bindPipeline(...)`
- `sw.enableDigest(name, config)`
- `sw.send(packet)` and `sendAsync`
- `sw.asPrimary()` / `asSecondary()` re-arbitration calls

These serialise onto the StreamChannel in FIFO order. Concurrent
`sw.insert(...)` from N user threads is safe and produces a
deterministic wire order; the executor's internal queue resolves any
race for the channel.

## The deadlock-free callback-calling-send guarantee

A callback that calls `sw.send(...)` (a common pattern for a learning
switch reacting to PacketIn) does **not** deadlock, even though both
the callback and the send touch jp4-owned executors:

<!-- illustrative: concept fragment -->

```java
sw.onPacketIn(packet -> {
    // running on the callback executor
    PacketOut response = buildResponse(packet);
    sw.send(response);   // schedules on the outbound executor — does not deadlock
    // returns; the callback executor moves on to the next event
});
```

`sw.send(...)` enqueues a task onto the **outbound** executor and
blocks the calling thread until the outbound executor processes it.
The calling thread is the **callback** executor; the outbound executor
is a different thread. They never share a queue. The send completes,
the outbound executor returns, the calling thread (the callback
executor) resumes and finishes the handler.

The same pattern applies to `sw.insert(...)` from a PacketIn handler,
`sw.modify(...)` from a mastership-change listener, etc. — any
write-side call from any callback is deadlock-free for the same
reason.

## Async paths and completion threads

The `*Async` paths (`insertAsync`, `sendAsync`, `allAsync`, …) return
a `CompletableFuture<Void>` or `CompletableFuture<List<...>>`. The
future completes on the outbound executor — the thread that issued the
RPC and processed the device's reply. Continuations chained without an
explicit executor (`thenRun`, `thenAccept`, `whenComplete`) run on
**that** executor:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(entry)
        .thenRun(() -> log("inserted"));   // runs on the outbound executor
```

A continuation that does meaningful work (logging an entry to a
database, computing a derived value) should hop off the outbound
executor explicitly to avoid stalling the next outbound RPC:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(entry)
        .thenRunAsync(() -> db.record(entry), userExecutor);
```

The standard `CompletableFuture` rules apply — `thenApplyAsync`,
`thenComposeAsync`, `whenCompleteAsync` all accept an executor and
route the continuation there.

## What is *not* serialised

**Concurrent reads on the same switch.** `sw.read("...").stream()`
initiates on the outbound executor but consumes on the calling thread
of the terminal (`forEach`, the for-loop on `iterator()`, etc.).
Multiple readers iterating multiple streams from the same switch run
concurrently on their own threads; the underlying gRPC iterators are
independent. The outbound executor is only involved at initiation.

**Independent switches.** Two `P4Switch` instances against the same
device or against two different devices have entirely independent
executors. There is no cross-switch ordering guarantee — two switches
writing to the same device race normally.

**Outbound-from-the-inbound-thread.** jp4 never lets the inbound
thread do real work, and the inbound thread does not write to the
device directly (every write goes through the outbound executor). The
pattern is purely fan-out: inbound parses, enqueues on the callback
executor's queue, and returns.

## A worked example: PacketIn → table insert + send

The L2 learning switch is the canonical PacketIn-into-write workload:

<!-- illustrative: concept fragment -->

```java
sw.onPacketIn(packet -> {
    Mac src = Mac.fromBytes(extractSrc(packet));
    int  ingress = packet.metadataInt("ingress_port");

    sw.insert(TableEntry.in("MyIngress.mac_learn")
            .match("hdr.ethernet.srcAddr", new Match.Exact(src.toBytes()))
            .action("MyIngress.mac_seen").param("port", ingress)
            .build());
    // (no send — the dataplane forwards once the learn entry lands)
});
```

What runs where:

1. BMv2 emits a PacketIn on the StreamChannel. grpc-java's inbound
   thread receives it.
2. Inbound thread parses the wire proto, builds a `PacketIn` value,
   and enqueues it on the callback executor's queue.
3. Callback executor picks up the next queued event (FIFO with any
   prior digest / mastership / drop / earlier-packet event), invokes
   `onPacketIn`.
4. The handler calls `sw.insert(...)`. That enqueues a task on the
   outbound executor and blocks the callback thread until the
   outbound executor finishes the RPC.
5. Outbound executor runs the Write RPC, waits for the device's
   response, completes.
6. Callback thread resumes, the handler returns, callback executor
   picks up the next queued event.

A slow database call inside the handler delays step 6 (and therefore
the next PacketIn dispatch), but it never delays the inbound thread
in step 2. The poll deque has a configurable capacity (default 1024)
and overflows by dropping the oldest unread packet with a WARN log —
that's the back-pressure mechanism when the application can't keep
up.

## See also

- [Packet I/O](/guides/packet-io) — the three PacketIn styles
  (callback / Flow.Publisher / poll) feed into the same callback
  executor.
- [Tables](/guides/tables) — concurrent inserts from multiple user
  threads.
- [Error handling](/guides/error-handling) — async paths surface
  failures through the future, never by throwing on the calling
  thread.
