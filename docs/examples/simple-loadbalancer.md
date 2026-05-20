---
title: simple-loadbalancer — LPM route batching
description: Walkthrough of the simple-loadbalancer example — installs three /24 prefixes in a single batched Write RPC, reads back to verify, modifies one route, and reads again. Demonstrates batch lifecycle and per-update failure handling.
keywords: [jp4, example, simple-loadbalancer, LPM, batch, WriteResult, IPv4 routes]
---

# `simple-loadbalancer`

LPM-based load balancer with three /24 prefixes mapping to three backend ports. The example installs an initial route table as a single batched Write, reads it back to verify the device's view, modifies one route at runtime, and reads again to confirm.

**Source on GitHub**: [`examples/simple-loadbalancer/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/) (Java + P4 + Gradle build)

## What this example demonstrates

- Pushing a pipeline + batch-inserting many entries in one RPC (`sw.batch().insert(...).insert(...).execute()`).
- LPM match construction with `new Match.Lpm(Bytes, prefixLen)` and the `Match.lpm(cidr)` shorthand.
- Reading entries back with `sw.read("…").all()` and walking the result.
- Runtime route mutation with `sw.modify(...)`.
- IPv4 type ergonomics: `Ip4.of("10.0.1.0").toBytes()`.

## Running locally

```bash
cd examples
./gradlew :simple-loadbalancer:run
```

Optional: `--args="my-bmv2-host:50051"` to override the device address. See the [example's README](https://github.com/zhh2001/jp4/blob/main/examples/simple-loadbalancer/README.md) for the BMv2 setup line.

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

The `after modify` block confirms the modify-then-readback round trip resolved to the new port.

## Things to try

- Insert a *more-specific* prefix (e.g. `10.0.1.5/32 → port 9`) and observe that BMv2's LPM table picks it before the `/24`.
- Use `sw.read("…").match("hdr.ipv4.dstAddr", new Match.Lpm(...)).all()` to filter the read on the server side and compare results to the unfiltered `.all()` (BMv2 implements server-side LPM filter strictly).
- Wrap the cleanup loop in a try/finally so a mid-run crash still tidies the device state.
- Replace one of the `.insert(...)` calls with an intentionally-bad one (unknown table name) and inspect `WriteResult.failures()` to see how per-update rejections surface without aborting the whole batch.

## See also

- [LPM route table batch installation](/cookbook/lpm-routes) — the recipe extracted from this example.
- [Tables](/guides/tables) — the full `Match` builder surface (exact / LPM / ternary / range / optional) and the `batch()` API.
- [Canonical bytestring](/concepts/canonical-bytestring) — why a read-back IP address may have fewer bytes than the written form.
- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — how `sw.batch().execute()` translates to a single `Write` RPC with multiple `Update`s.
