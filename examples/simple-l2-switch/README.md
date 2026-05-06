# SimpleL2Switch

A controller-side L2 learning switch. The data plane forwards by destination MAC
when a hit exists in `l2_forward`; on miss, BMv2 sends the packet up to the
controller, which both *learns* `srcAddr → ingress_port` and *floods* the
packet to all other front-panel ports via `PacketOut`.

## What this demonstrates in jp4

- Connecting as primary and pushing a pipeline (`P4Switch.connectAsPrimary` +
  `bindPipeline`).
- Registering a `PacketIn` callback (`sw.onPacketIn`) and reading the
  `controller_packet_metadata` (`packet.metadataInt("ingress_port")`).
- Writing table entries from the callback (`TableEntry.in("…").match(…).action(…).build()` + `sw.insert`).
- Sending `PacketOut` (`PacketOut.builder().payload(…).metadata("egress_port", …).build()` + `sw.send`).
- Try-with-resources lifecycle (`P4Switch implements AutoCloseable`).

Maps to v3 §5 Scenarios A (connect/pipeline), C (table insert), and E (Packet I/O).

## Prerequisites

- **Java 21+** (the project's main build uses 21; the example follows).
- **Docker** with `p4lang/behavioral-model` reachable.
- See [`../README.md`](../README.md) for the one-line `docker run` that starts
  BMv2 with the digest pin used by the project's CI.

## Run

```bash
# In one terminal: start BMv2 (see ../README.md for the exact docker run line).

# In another terminal:
cd examples
./gradlew :simple-l2-switch:run
```

Optional first-arg override of the device address (default `127.0.0.1:50051`):

```bash
./gradlew :simple-l2-switch:run --args="my-bmv2-host:50051"
```

## Expected output

A successful run prints something like:

```
[L2] connected as primary on 127.0.0.1:50051, pipeline pushed
[L2] inject    src=AA:00:00:00:00:01 dst=BB:00:00:00:00:02 via port 1
[L2] PacketIn  src=AA:00:00:00:00:01 dst=BB:00:00:00:00:02 ingress=1
[L2] LEARN     AA:00:00:00:00:01 → port 1 (entry installed)
[L2] inject    src=BB:00:00:00:00:02 dst=AA:00:00:00:00:01 via port 2
[L2] PacketIn  src=BB:00:00:00:00:02 dst=AA:00:00:00:00:01 ingress=2
[L2] LEARN     BB:00:00:00:00:02 → port 2 (entry installed)
[L2] learned table: {AA:00:00:00:00:01=1, BB:00:00:00:00:02=2}
```

The two `LEARN` lines confirm jp4 wrote forwarding entries; subsequent traffic
to either MAC would short-circuit through the data plane (no controller hop).

## Try this next

- Replace `sw.insert(e)` with `sw.modify(e)` and observe the device's response
  when the entry already exists vs not.
- Subscribe to `sw.packetInStream()` instead of `sw.onPacketIn(...)` and
  consume packets via a `Flow.Subscriber` — both styles fan out from the same
  underlying stream (see jp4 v3 design §D6).
- Remove a learned entry with `sw.delete(e)` mid-run and watch the next packet
  to that MAC trigger another learn cycle.

## Self-traffic note

The example uses `sw.send(...)` to inject demo frames so that you can see the
learn-and-forward cycle on a single machine without `mininet` / `tcpreplay` /
real interfaces. In production, traffic comes from the device's physical
ports; the controller code itself stays the same.
