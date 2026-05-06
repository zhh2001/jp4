# SimpleLoadbalancer

LPM-based load balancer with three /24 prefixes mapping to three backend ports.
The example installs an initial route table as a single batched Write, reads it
back to verify the device's view, modifies one route at runtime, and reads
again to confirm.

## What this demonstrates in jp4

- Pushing a pipeline + batch-inserting many entries in one RPC
  (`sw.batch().insert(...).insert(...).execute()`).
- LPM match construction with `new Match.Lpm(Bytes, prefixLen)`.
- Reading entries back with `sw.read("…").all()` and walking the result.
- Runtime route mutation with `sw.modify(...)`.
- IPv4 type ergonomics: `Ip4.of("10.0.1.0").toBytes()`.

Maps to v3 §5 Scenarios B (loadPipeline / pipeline acceptance), C (insert /
modify), and D (batch).

## Prerequisites

- **Java 21+**.
- **Docker** with `p4lang/behavioral-model` reachable.
- See [`../README.md`](../README.md) for the one-line `docker run`.

## Run

```bash
cd examples
./gradlew :simple-loadbalancer:run
```

Optional: `--args="my-bmv2-host:50051"` to override the device address.

## Expected output

```
[LB] connected as primary on 127.0.0.1:50051, pipeline pushed
[LB] installed 3 routes (allSucceeded=true)
[LB] backend_lookup after install: 3 entries
[LB]   10.0.1.0/24 → port 1
[LB]   10.0.2.0/24 → port 2
[LB]   10.0.3.0/24 → port 3
[LB] moved 10.0.2.0/24 to port 4
[LB] backend_lookup after modify: 3 entries
[LB]   10.0.1.0/24 → port 1
[LB]   10.0.2.0/24 → port 4
[LB]   10.0.3.0/24 → port 3
[LB] cleaned up; goodbye
```

The `after modify` block confirms the modify-then-readback round trip resolved
to the new port.

## Try this next

- Insert a *more-specific* prefix (e.g. `10.0.1.5/32 → port 9`) and observe
  that BMv2's LPM table picks it before the `/24`.
- Use `sw.read("…").match("hdr.ipv4.dstAddr", new Match.Lpm(...)).all()` to
  filter the read on the server side and compare results to the unfiltered
  `.all()` (BMv2 implements server-side LPM filter strictly — see
  `NOTES.md` "BMv2 read filter semantics" in the main project).
- Wrap the cleanup loop in a try/finally so a mid-run crash still tidies the
  device state.
