---
title: PacketIn handler never fires
description: Why an onPacketIn callback or Flow.Subscriber never receives packets — the most common causes are no primary mastership against BMv2, no bound pipeline, or device-side P4 program not punting traffic to the controller.
keywords: [jp4, troubleshooting, PacketIn, callback, Flow.Subscriber, BMv2, secondary, mastership, punt]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# PacketIn handler never fires

## Symptom

You registered `sw.onPacketIn(...)`, subscribed to `sw.packetInStream()`, or called `sw.pollPacketIn(...)`, but no packets arrive. The connection seems healthy — no exception, no SLF4J warning, no `onError`/`onComplete` callback. The handler just never runs.

## Most likely causes (ordered by frequency)

### 1. You're a secondary against BMv2

P4Runtime spec §16.1 says PacketIn **MUST** be sent to the primary client and **SHOULD** be sent to backups. BMv2 implements only the MUST — it delivers PacketIn to the primary client and nothing to secondaries. A secondary connection against BMv2 will register the subscription successfully but will never see a packet.

**Fix:** Either become primary (and the existing primary must yield), or observe PacketIn through a multiplexer on the primary side. Some Tofino / Stratum builds do broadcast to secondaries — the same subscriber code attaches unchanged on those targets.

### 2. You haven't called `bindPipeline` / `loadPipeline`

PacketIn parsing requires the `P4Info` schema to decode `controller_packet_metadata` fields. Without one, the registration call throws `P4PipelineException("no pipeline bound; ...")` — but if you registered the handler **before** the exception fires (or in a code path where the exception is swallowed), the registered handler will never fire because the inbound parser never accepts packets.

**Fix:** Always `bindPipeline(...)` (primary) or `loadPipeline()` (secondary) before any `onPacketIn` / `packetInStream` / `pollPacketIn` call. See [Troubleshooting: no pipeline bound](/troubleshooting/no-pipeline-bound).

### 3. The device-side P4 program doesn't punt

PacketIn is the device punting a frame to the controller. The P4 program decides which frames to punt — typically via a `clone_preserving_field_list` / `recirculate` / `digest` / explicit fan-out to `CPU_PORT`. A pipeline that has no punt path produces no PacketIn no matter how the controller is wired up.

**Fix:** Check the P4 source for the punt action. For BMv2, `simple_switch_grpc --cpu-port 255` requires the P4 program to forward to port 255 to punt — verify the program does so. For `simple-l2-switch` and `network-monitor` in the jp4 examples, the punt path is the data-plane miss action on `l2_forward` (table miss → controller).

### 4. The poll deque is overflowing (silently dropping)

If you use `sw.pollPacketIn(...)` and the poll loop falls behind, the deque (default capacity 1024) drops the oldest unread packet on overflow. A SLF4J `WARN` log fires per drop. If the deque is full *and* nobody is polling, the SLF4J warns will pile up but no exception surfaces.

**Fix:** Increase poll cadence, switch to `onPacketIn` (callback) or `packetInStream` (Flow.Publisher) which don't queue, or accept the drops if they're tolerable for the workload.

### 5. The device closed the stream

If the gRPC StreamChannel terminated and reconnect isn't configured (`ReconnectPolicy.noRetry()` default), the subscriber's `onComplete()` fires once and then nothing more. Without an `onError` / `onComplete` log, this is easy to miss.

**Fix:** Log `onComplete()` and `onError(Throwable)` callbacks. Configure `ReconnectPolicy.exponentialBackoff(...)` if auto-reconnect is desired.

## Diagnostic checklist

```java
// 1. Confirm you're primary on BMv2.
sw.onMastershipChange(s -> log.info("mastership: {}", s));

// 2. Confirm pipeline is bound.
log.info("pipeline bound: {}", sw.boundP4Info() != null);

// 3. Log every state transition on the subscriber.
sw.packetInStream().subscribe(new Flow.Subscriber<>() {
    @Override public void onSubscribe(Flow.Subscription s) {
        log.info("subscribed");
        s.request(Long.MAX_VALUE);
    }
    @Override public void onNext(PacketIn p)         { log.info("packet"); }
    @Override public void onError(Throwable t)       { log.error("stream error", t); }
    @Override public void onComplete()               { log.info("stream completed"); }
});

// 4. Confirm device-side traffic. tcpdump on the BMv2 host's interfaces.
```

## See also

- [Packet I/O](/guides/packet-io) — three consumption styles, the threading model, the BMv2 §16.1 caveat.
- [PacketIn observation from a secondary controller](/cookbook/packet-in-secondary) — recipe explaining the BMv2 §16.1 gap in context.
- [Connection and arbitration](/guides/connection-and-arbitration) — primary vs secondary, mastership change notifications.
