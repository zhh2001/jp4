# jp4

A native Java client library for P4Runtime — connect to a P4Runtime-enabled
device, push pipelines, read and write table entries, and send and receive
packets through the StreamChannel.

[![CI](https://github.com/zhh2001/jp4/actions/workflows/ci.yml/badge.svg)](https://github.com/zhh2001/jp4/actions/workflows/ci.yml)
![JDK](https://img.shields.io/badge/JDK-21%2B-blue)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

<!-- illustrative -->

```java
try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    sw.insert(TableEntry.in("MyIngress.ipv4_lpm")
            .match("hdr.ipv4.dstAddr", new Match.Lpm(Ip4.of("10.0.1.0").toBytes(), 24))
            .action("MyIngress.forward").param("port", 1)
            .build());

    sw.onPacketIn(p -> handle(p));
}
```

*Real usage: [`examples/simple-l2-switch/`](examples/simple-l2-switch/).*

The example shows the four things jp4 does: connect as primary, push a
pipeline, write a table entry, observe inbound packets. No code generation
in user space, no SDN-controller framework dependency, no reflection at
runtime — P4Runtime is the dependency, jp4 is the binding.

## Why jp4

Other P4Runtime clients exist in the ecosystem.
[**finsy**](https://github.com/plvision/finsy) is the Python equivalent;
if your tooling is Python, use it. **p4runtime-go-client** covers the Go
side. jp4 fills the Java gap — embed it in any Java application, no
controller framework attached. **ONOS** is a complete SDN controller
platform (multi-protocol, distributed, application model); jp4 is a
focused client library scoped to one P4Runtime device per `P4Switch`
instance. Pick ONOS if you need a controller framework, jp4 if you're
wiring P4 into a Java service.

Three things that matter day-to-day:

- **Names, not numeric ids.** Once you call `bindPipeline(p4info, dc)`,
  every operation references tables / actions / match fields / metadata
  by their P4 source-level name. You never write `tableId 33554497`.
- **Strongly typed match keys + escape hatches.** `Match.Exact`,
  `Match.Lpm`, `Match.Ternary`, `Match.Range`, `Match.Optional` are a
  sealed type — exhaustive `switch` at compile time. `Mac`, `Ip4`,
  `Ip6`, `Bytes` model what controllers actually carry; `byte[]` is
  accepted everywhere strong types are.
- **AutoCloseable everything.** `P4Switch implements AutoCloseable`;
  read streams close deterministically via try-with-resources; the
  callback executor and outbound thread are shut down cleanly on
  `close()`.

## Quick start

About five minutes from a fresh clone to seeing the first example output.
Prerequisites: **Java 21+** and **Docker**. Nothing else — jp4 ships its
own Gradle wrapper and the BMv2 device runs in a container.

```bash
git clone https://github.com/zhh2001/jp4.git
cd jp4

# Start a BMv2 instance pinned to the same image content jp4's CI runs against.
docker run --rm -d --name jp4-bmv2 -p 50051:50051 \
    p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247 \
    simple_switch_grpc \
        --no-p4 --device-id 0 --log-console -L info \
        -i 0@lo \
        -- --grpc-server-addr 0.0.0.0:50051 --cpu-port 255

# Run the L2 learning switch example.
cd examples
./gradlew :simple-l2-switch:run

# Done — clean up.
docker rm -f jp4-bmv2
```

Expected console output from the example (verbatim from a real run):

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

If you see those `LEARN` lines, jp4 is working — table entries were
written, PacketIn round-tripped through the BMv2 data plane, and the
controller learned `srcAddr → ingress_port` from the metadata.

## Examples

Three runnable controllers under [`examples/`](examples/), each with its
own README and a five-section walk-through:

| Example | API surface | Use it as a template for |
|---|---|---|
| [simple-l2-switch](examples/simple-l2-switch/) | `onPacketIn` + `insert` + `send` | Reactive controllers that learn from PacketIn and write data-plane entries. |
| [simple-loadbalancer](examples/simple-loadbalancer/) | `batch` + `read` + `modify` + LPM | Configuration-management controllers that pre-populate tables and update them at runtime. |
| [network-monitor](examples/network-monitor/) | `asSecondary` + `loadPipeline` + `packetInStream` | Observation-only controllers running in HA topologies (read-only, not primary). |

## Documentation

In-depth guides for each major API surface live under [`docs/`](docs/):

- [Getting started](docs/getting-started.md) — install, first connect, first table entry, in 15 minutes.
- [Connection and arbitration](docs/connection-and-arbitration.md) — primary / secondary, election ids, mastership listeners, auto-reconnect.
- [Pipelines](docs/pipeline.md) — `bindPipeline` vs `loadPipeline`, P4Info parsing, `DeviceConfig` variants.
- [Tables](docs/tables.md) — `TableEntry` builder, all five `Match` kinds, read / write / batch / modify / delete.
- [Packet I/O](docs/packet-io.md) — three consumption styles (callback / Flow.Publisher / blocking poll), sending PacketOut, controller_packet_metadata.
- [Error handling](docs/error-handling.md) — the four exception types and when each fires.

The Javadoc for the public API is the canonical reference; these guides
are how-to / tutorial-shaped, not API catalogues.

## Compatibility

| Component | Version |
|---|---|
| JDK | 21+ (compiled to release 21; CI tests JDK 21 and 25) |
| P4Runtime spec | v1.4-v1.6 features (proto vendored from [`p4lang/p4runtime`](https://github.com/p4lang/p4runtime)) |
| Build | Gradle 9.x (wrapper bundled) |
| BMv2 | `p4lang/behavioral-model` — verified against the digest pinned in `DockerBackend` (the `:latest` content as of 2026-05-05) |

The API is target-agnostic — anything speaking spec-compliant P4Runtime
should work — but only BMv2 is exercised in CI. Tofino / Stratum / other
targets should function but are not in the verification matrix; specific
device behaviours that differ from BMv2 (e.g. the partial-failure shape
documented in [`NOTES.md`](NOTES.md)) are noted as device-side.

## Status

v0.1 is the first release line; the public API surface is locked in this
release line and will not break across v0.1.x patches. Features deferred
to v0.2 (multi-switch coordination, action profiles, counters / meters /
registers, multicast, Tofino device config, additional pipeline actions,
`ReadQuery.where(filter)` / `.fields(projection)`) are listed in
[`CHANGELOG.md`](CHANGELOG.md) under the "Roadmap" heading.

## Engineering notes

[`NOTES.md`](NOTES.md) captures BMv2 / runtime quirks and project-level
engineering decisions that are useful when running jp4 in production but
not part of the API surface — e.g. the JDK 24+ Netty `Unsafe` warning
mitigation, the BMv2 mastership-transition retry pattern, the rotation
procedure for the Docker BMv2 image digest pin.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Issues and PRs are welcome on
[GitHub](https://github.com/zhh2001/jp4/issues).

## License

Apache 2.0. See [`LICENSE`](LICENSE).
