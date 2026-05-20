---
title: L2 learning entry installation
description: Recipe for a controller-side L2 learning switch — read source MAC from PacketIn, install a forwarding entry through TableEntry.in().match().action().build() + sw.insert, and let the data plane handle subsequent frames without the controller hop.
keywords: [jp4, cookbook, L2 learning, PacketIn, TableEntry, callback, controller-side learning]
---

# L2 learning entry installation

**I want to:** turn a flood-on-miss data plane into a learning switch by writing forwarding entries from the controller as PacketIns arrive.

## The pattern

<!-- illustrative: trimmed adaptation of examples/simple-l2-switch/src/main/java/io/github/zhh2001/jp4/examples/l2switch/SimpleL2Switch.java#learnHandler, edited for didactic clarity -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Mac;

import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    Map<Mac, Integer> learned = new ConcurrentHashMap<>();

    sw.onPacketIn(packet -> {
        int ingressPort = packet.metadataInt("ingress_port");
        byte[] frame = packet.payload().toByteArray();
        if (frame.length < 12) return;            // too short to carry src/dst MAC

        byte[] srcBytes = java.util.Arrays.copyOfRange(frame, 6, 12);
        Mac src = Mac.fromBytes(srcBytes);

        if (learned.putIfAbsent(src, ingressPort) != null) return;   // already known

        sw.insert(TableEntry.in("MyIngress.l2_forward")
                .match("hdr.ethernet.dstAddr", new Match.Exact(srcBytes))
                .action("MyIngress.forward").param("port", ingressPort)
                .build());
    });

    // ... drive traffic and observe learning ...
}
```

*Real usage: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

## Walkthrough

1. **Connect as primary + bind pipeline.** The handler can't fire without a bound pipeline because PacketIn metadata can't be parsed without `P4Info`. The controller in this recipe is the only client; `connectAsPrimary` is the shorthand.
2. **Register the PacketIn handler with `sw.onPacketIn`.** The handler runs on jp4's single-threaded callback executor — slow handlers delay subsequent dispatch but never block the gRPC inbound thread.
3. **Read the source MAC out of the frame.** The L2 src is bytes 6-11 of the Ethernet payload. Use `Mac.fromBytes` so the comparison key has a typed equals/hashCode.
4. **Idempotent guard via `learned.putIfAbsent`.** Multiple PacketIns for the same src MAC can race; `putIfAbsent` ensures one insert per src.
5. **Install the forwarding entry.** `TableEntry.in(name).match(field, MatchKind).action(name).param(name, value).build()` is the fluent chain; `sw.insert(entry)` is blocking and throws `P4OperationException` with `ALREADY_EXISTS` if the key already exists — the guard avoids that, but if the data plane state diverges from the controller's `learned` map, catch and either fall through or `sw.modify` the entry.

## Why this works

The data plane (`MyIngress.l2_forward`) does an exact-match lookup keyed on `hdr.ethernet.dstAddr`; a hit forwards, a miss punts the frame to the controller. After the controller installs the entry for `srcAddr`, subsequent frames destined to that MAC short-circuit through the data plane without a controller hop.

## See also

- [Packet I/O](/guides/packet-io) — the three PacketIn consumption styles (callback / Flow.Publisher / poll) and how PacketOut interacts.
- [Tables](/guides/tables) — the full `TableEntry` builder surface and the five match kinds.
- [Threading model](/concepts/threading-model) — why a PacketIn handler calling `sw.insert` doesn't deadlock.
- [`simple-l2-switch` example](/examples/simple-l2-switch) — the full runnable program this recipe was extracted from.
