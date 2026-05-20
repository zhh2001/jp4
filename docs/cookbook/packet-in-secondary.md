---
title: PacketIn observation from a secondary controller
description: Recipe for a passive monitor controller — connect as secondary (low election id), call loadPipeline to populate the local schema, then subscribe to packetInStream as a Flow.Publisher. Includes the BMv2 PacketIn-delivery caveat.
keywords: [jp4, cookbook, secondary, monitor, Flow.Publisher, loadPipeline, mastership, observability]
---

# PacketIn observation from a secondary controller

**I want to:** run a read-only monitor that subscribes to PacketIn without holding primary, while the existing primary controller manages the device's forwarding state.

## The pattern

<!-- illustrative: trimmed adaptation of examples/network-monitor/src/main/java/io/github/zhh2001/jp4/examples/monitor/NetworkMonitor.java#secondaryObserver, edited for didactic clarity -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.ElectionId;
import io.github.zhh2001.jp4.entity.PacketIn;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

try (P4Switch monitor = P4Switch.connect("127.0.0.1:50051")
        .electionId(ElectionId.of(1))   // low id — primary holds something higher
        .asSecondary()) {

    // Secondaries can't bindPipeline (it's a write), but loadPipeline is a
    // read-only RPC that fetches the device's currently-installed P4Info
    // into the local switch. PacketIn parsing requires this schema.
    monitor.loadPipeline();

    AtomicInteger counter = new AtomicInteger();

    monitor.packetInStream().subscribe(new Flow.Subscriber<PacketIn>() {
        @Override public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }
        @Override public void onNext(PacketIn p) {
            int port = p.metadataInt("ingress_port");
            int bytes = p.payload().toByteArray().length;
            System.out.printf("[#%d] port=%d bytes=%d%n",
                    counter.incrementAndGet(), port, bytes);
        }
        @Override public void onError(Throwable t) { /* log + reconnect logic */ }
        @Override public void onComplete()         { /* device closed stream */ }
    });

    // ... block until shutdown signal ...
}
```

*Real usage: [`network-monitor`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/).*

## Walkthrough

1. **Connect with `asSecondary()` and a low election id.** The id has to be lower than the existing primary's. If the device replies with `Acquired` (you became primary), check operations team coordination on election ids — `asSecondary()` is a request, not a guarantee.
2. **`loadPipeline()` is the read-side complement of `bindPipeline`.** It issues `GetForwardingPipelineConfig`, populates the local `P4Info`, and returns. Without it, `packetInStream()` throws `P4PipelineException("no pipeline bound; ...")` because the inbound parser has no metadata schema to decode against.
3. **`packetInStream()` returns a `Flow.Publisher<PacketIn>`** (JDK 9+, no extra dependency). Each subscriber sees every packet. For backpressure, request fewer items than `Long.MAX_VALUE`; for fanout to a reactive stack, adapt with `JdkFlowAdapter.flowPublisherToFlux(...)` (Reactor) or `FlowAdapters.toPublisher(...)` (Reactive Streams).
4. **The `onNext` callback runs on jp4's callback executor.** Same FIFO contract as `onPacketIn` — slow consumers delay subsequent dispatches but never block the gRPC inbound thread.

## Important: BMv2 PacketIn delivery caveat

P4Runtime spec §16.1 says PacketIn **MUST** be delivered to the primary client and **SHOULD** also be delivered to secondaries. BMv2 implements only the MUST. A secondary connection against BMv2 will register and load the pipeline successfully, but **no PacketIn will arrive** through `packetInStream()` because the device only fans them out to the primary.

On a target that **does** broadcast PacketIn to secondaries (some Tofino / Stratum builds), the same subscriber code attaches unchanged. The Java code is target-agnostic.

If you're testing this recipe against BMv2 and seeing no packets, that's the spec MUST/SHOULD gap, not a jp4 bug — see [Troubleshooting: PacketIn handler never fires](/troubleshooting/packet-in-not-firing).

## See also

- [Connection and arbitration](/guides/connection-and-arbitration) — primary vs secondary, election ids, mastership semantics.
- [Packet I/O](/guides/packet-io) — `packetInStream()` mechanics and the three consumption styles.
- [Threading model](/concepts/threading-model) — which executor runs `Flow.Subscriber.onNext`.
- [`network-monitor` example](/examples/network-monitor) — the full runnable program this recipe was extracted from.
