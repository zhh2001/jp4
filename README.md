# jp4

A native Java client library for P4Runtime — connect to a P4Runtime-enabled
device, push pipelines, read and write table entries, and send and receive
packets through the StreamChannel.

[![CI](https://github.com/zhh2001/jp4/actions/workflows/ci.yml/badge.svg)](https://github.com/zhh2001/jp4/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.zhh2001/jp4)](https://central.sonatype.com/artifact/io.github.zhh2001/jp4)
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

Other P4Runtime clients exist in the ecosystem. **finsy** is the Python equivalent;
if your tooling is Python, use it. **[p4runtime-go-controller](https://github.com/zhh2001/p4runtime-go-controller)** covers the Go
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
- [Migration guide: v0.1 → v1.0](docs/migration-0.1-to-1.0.md) — API surface changes between v0.1 and v1.0, with before/after examples for callers updating.
- [Migration guide: v1.0 → v1.1](docs/migration-1.0-to-1.1.md) — usage examples for the methods added in v1.1; SemVer-minor, no breaking changes for v1.0 callers.
- [Migration guide: v1.1 → v1.2](docs/migration-1.1-to-1.2.md) — usage examples for the packet-ingestion control surface added in v1.2 (DropEvent, onPacketDropped, packetInFilter); SemVer-minor, no breaking changes for v1.1 callers.
- [Migration guide: v1.2 → v1.3](docs/migration-1.2-to-1.3.md) — usage examples for the stream-message dispatch family added in v1.3 (DigestEvent, IdleTimeoutEvent, onDigest, onIdleTimeout, DigestConfig, enableDigest, TableEntry.idleTimeoutNs); SemVer-minor, no breaking changes for v1.2 callers.
- [Migration guide: v1.3 → v1.4](docs/migration-1.3-to-1.4.md) — usage examples for the per-entity read APIs added in v1.4 (readCounter, readMeter, readRegister, readActionProfileMember, readActionProfileGroup) with their typed query builders and entity records; SemVer-minor, no breaking changes for v1.3 callers.

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

## Production readiness

v1.0 locks the public API surface; the surface is stable across v1.x
patches. v1.x adds capabilities (see roadmap below); 2.0 is the
earliest version where the v1.0 surface is allowed to break.

### Validation status

The CI matrix (8 jobs per push) exercises:

- **BMv2** (`p4lang/behavioral-model`, digest-pinned `:latest` content
  as of 2026-05-05): Docker image on Ubuntu CI runners; native binaries
  on Linux dev hosts.
- **JDK 21** (Temurin, LTS) and **JDK 25** (preview) build matrix.
- **Mastership arbitration** — single primary + multiple secondaries,
  primary handover, re-arbitration after lost primary.
- **Pipeline push** via `SetForwardingPipelineConfig` — P4Info text +
  binary protobuf, BMv2 device-config JSON.
- **Table CRUD** — insert / read / modify / delete, batch Write RPCs
  with per-update partial-failure attribution.
- **All five match kinds** — exact, lpm, ternary, range, optional.
- **PacketIn / PacketOut** via StreamChannel, including
  `controller_packet_metadata` decoding.
- **Auto-reconnect** — stream-error recovery, election-id preservation
  across reconnect, configurable backoff.
- **282 unit + integration tests** across 8 CI jobs (build×2,
  docs-lint, examples-l2 / examples-lb / examples-monitor,
  publish-dry-run).
- **Linux** — Ubuntu CI runners + WSL2 development host.

Untested environments — expected to work but not validated:

- **Hardware P4 targets** (Tofino, Mellanox/NVIDIA, Cisco Silicon One,
  other ASICs). jp4 implements the P4Runtime gRPC spec faithfully —
  proto sources are vendored unmodified from
  [`p4lang/p4runtime`](https://github.com/p4lang/p4runtime) — and
  should work on any spec-compliant target. Hardware testing is
  community-driven; users deploying on hardware are encouraged to
  share results in
  [GitHub Discussions](https://github.com/zhh2001/jp4/discussions) so
  this list can grow with verified deployments.
- **macOS / Windows JVMs**. jp4 has no platform-specific code; should
  work on any JDK 21+ but not validated.

### Production-ready scope

v1.0 is production-ready for:

- Single P4Runtime device control from a Java application.
- BMv2-based testing, development, and CI environments.
- Educational and research SDN controllers.
- Custom packet-processing controllers (PacketIn / PacketOut over the
  StreamChannel).
- Embedding in JVM applications already on Java 21+.

### Known limitations and v1.x roadmap

What jp4 1.0 does **not** cover:

- **Multi-switch coordination** — a controller of N>1 switches with
  fan-out / parallelism / error-aggregation semantics. v1.x roadmap;
  v1.0 users compose `List<P4Switch>` themselves.
- **Hardware target validation** — see "Untested environments" above.
- **High-throughput production benchmarks** — jp4's performance
  envelope under sustained PacketIn load (>10k pps) has not been
  measured. Run a load test before relying on jp4 in high-throughput
  paths.
- **Other entity-type reads** — multicast groups and
  packet-replication. v1.x roadmap. (v1.4 delivered counters,
  meters, registers, and action-profile members and groups; see
  [`docs/migration-1.3-to-1.4.md`](docs/migration-1.3-to-1.4.md).)
- **Tofino-specific `DeviceConfig`** — currently use
  `DeviceConfig.Raw` for Tofino contexts; a typed
  `DeviceConfig.Tofino` is v1.x roadmap.

BMv2-specific runtime quirks (partial-failure shape,
mastership-transition retry pattern, PacketIn-primary-only delivery,
others) are documented in [`NOTES.md`](NOTES.md). The full v1.x
roadmap candidate list lives in [`CHANGELOG.md`](CHANGELOG.md) under
the "Roadmap" heading.

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
