---
title: simple-l2-switch — controller-side L2 learning
description: Walkthrough of the simple-l2-switch example — a controller-side L2 learning switch that injects synthetic frames, observes PacketIn, writes forwarding entries from the callback, and prints a learned MAC table.
keywords: [jp4, example, simple-l2-switch, L2 learning, PacketIn, controller, BMv2]
---

# `simple-l2-switch`

A controller-side L2 learning switch. The data plane forwards by destination MAC when a hit exists in `l2_forward`; on miss, BMv2 sends the packet up to the controller, which *learns* `srcAddr → ingress_port` and *floods* the packet to all other front-panel ports via `PacketOut`.

**Source on GitHub**: [`examples/simple-l2-switch/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/) (Java + P4 + Gradle build)

## What this example demonstrates

- Connecting as primary and pushing a pipeline (`P4Switch.connectAsPrimary` + `bindPipeline`).
- Registering a `PacketIn` callback (`sw.onPacketIn`) and reading the `controller_packet_metadata` (`packet.metadataInt("ingress_port")`).
- Writing table entries from the callback (`TableEntry.in("…").match(…).action(…).build()` + `sw.insert`).
- Sending `PacketOut` (`PacketOut.builder().payload(…).metadata("egress_port", …).build()` + `sw.send`).
- Try-with-resources lifecycle (`P4Switch implements AutoCloseable`).

## Running locally

The full prerequisites and `docker run` line are in the [example's README](https://github.com/zhh2001/jp4/blob/main/examples/simple-l2-switch/README.md); the [Quickstart](/quickstart) carries the same commands end-to-end. Briefly:

```bash
# After starting BMv2 in another terminal (see the README):
cd examples
./gradlew :simple-l2-switch:run
```

Optional first-arg override: `--args="my-bmv2-host:50051"`.

## Expected output

```
[L2] connected as primary on 127.0.0.1:50051, pipeline pushed
[L2] inject    src=aa:00:00:00:00:01 dst=ff:ff:ff:ff:ff:ff via simulated ingress 1
[L2] PacketIn  src=AA:00:00:00:00:01 dst=FF:FF:FF:FF:FF:FF ingress=1
[L2] LEARN     AA:00:00:00:00:01 → port 1 (entry installed)
[L2] inject    src=bb:00:00:00:00:02 dst=ff:ff:ff:ff:ff:ff via simulated ingress 2
[L2] PacketIn  src=BB:00:00:00:00:02 dst=FF:FF:FF:FF:FF:FF ingress=2
[L2] LEARN     BB:00:00:00:00:02 → port 2 (entry installed)
[L2] learned table: {AA:00:00:00:00:01=1, BB:00:00:00:00:02=2}
```

The two `LEARN` lines confirm jp4 wrote forwarding entries; subsequent traffic to either MAC would short-circuit through the data plane (no controller hop). Both demo frames target the broadcast MAC `FF:FF:FF:FF:FF:FF` so each one misses `l2_forward` and triggers the learning path.

## Things to try

- Replace `sw.insert(e)` with `sw.modify(e)` and observe the device's response when the entry already exists vs not.
- Subscribe to `sw.packetInStream()` instead of `sw.onPacketIn(...)` and consume packets via a `Flow.Subscriber` — both styles fan out from the same underlying stream. The [network-monitor example](/examples/network-monitor) shows the `Flow.Subscriber` shape end-to-end.
- Remove a learned entry with `sw.delete(e)` mid-run and watch the next packet to that MAC trigger another learn cycle.

## Self-traffic note

The example uses `sw.send(...)` to inject demo frames so that you can see the learn cycle on a single machine without `mininet` / `tcpreplay` / real interfaces. The `simple_l2.p4` program treats the controller-supplied `packet_out.egress_port` as the **simulated ingress port** for this loopback demo; a production controller's PacketOut would use that field with its conventional egress meaning, and real ingress traffic would come from the network. The Java controller code stays the same.

## See also

- [L2 learning entry installation](/cookbook/l2-learning) — the recipe extracted from this example.
- [Packet I/O](/guides/packet-io) — the three PacketIn consumption styles.
- [Tables](/guides/tables) — the `TableEntry` builder surface used by the learn-then-insert callback.
- [Threading model](/concepts/threading-model) — why a PacketIn handler calling `sw.insert` doesn't deadlock.
