# Changelog

All notable changes to jp4 are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

No unreleased changes. v0.1.0 is current.

## [0.1.0] — 2026-05

First release line. Public API surface is locked across v0.1.x patches.

### Added

- **`P4Switch`** — single-device controller. Synchronous and asynchronous
  forms of `insert` / `modify` / `delete` / `send`; `read` query builder;
  `onPacketIn` / `packetInStream` / `pollPacketIn`; `onMastershipChange`;
  `bindPipeline` / `loadPipeline`; `asPrimary` / `asSecondary` arbitration;
  auto-reconnect via `ReconnectPolicy`. `AutoCloseable`; idempotent `close()`.
- **`Connector`** — fluent builder for `P4Switch`. Configures device id,
  election id, reconnect policy, packet-in queue size before the connect.
- **`TableEntry` + `BatchBuilder`** — fluent name-based table-entry
  construction. P4Info-driven validation at switch-op time with
  known-list error messages on misspelled tables / fields / actions /
  params. Single-update and batched Write RPCs.
- **`ReadQuery`** — name-based table read. `.match(...)` filters; `.all()`
  / `.one()` / `.stream()` terminals (the latter `AutoCloseable`,
  cancels the gRPC iterator on close); `.allAsync()` / `.oneAsync()`
  variants returning `CompletableFuture`.
- **`PacketIn` / `PacketOut`** — three styles for receiving inbound
  packets (callback / `Flow.Publisher` / blocking poll, fan-out
  semantics) and synchronous + async send. `controller_packet_metadata`
  fields surface as a name-keyed map; `metadataInt(...)` convenience
  for ≤31-bit values.
- **`P4Info`** — text and binary protobuf parsers; auto-detect format;
  forward (name → entity) and reverse (id → entity) indexes for tables,
  actions, match fields, action params, and `controller_packet_metadata`.
- **`Match`** — sealed type covering all five P4Runtime match kinds
  (`Exact`, `Lpm`, `Ternary`, `Range`, `Optional`). Exhaustive switch at
  compile time.
- **Strong types** — `Bytes`, `Mac`, `Ip4`, `Ip6`, `ElectionId`. Each is
  a value type with deterministic `equals`/`hashCode`/`toString`. Every
  match / param / metadata setter accepts both the strong type and
  `byte[]` as an escape hatch.
- **Exception hierarchy** — `P4RuntimeException` parent; three concrete
  subclasses (`P4ConnectionException`, `P4PipelineException`,
  `P4OperationException`); `P4ArbitrationLost` specialises connection
  failure for primary-denied scenarios.
- **Examples** — three runnable Gradle modules under `examples/`:
  `simple-l2-switch` (learning switch), `simple-loadbalancer`
  (LPM-based router with batch + read + modify), `network-monitor`
  (secondary controller subscribing to `packetInStream`).
- **Docker BMv2 image pinning** — test infrastructure pins
  `p4lang/behavioral-model` to a specific manifest digest for
  reproducible CI runs. See `NOTES.md`.

### Compatibility

- **JDK 21+** — compiled to release 21; CI tests JDK 21 and JDK 25.
- **P4Runtime spec** — proto sources vendored from
  [`p4lang/p4runtime`](https://github.com/p4lang/p4runtime); supports
  v1.4 through v1.6 features.
- **Tested**: `p4lang/behavioral-model` (Docker image digest pinned to
  `sha256:7f28ab029368a17…`).
- **Not tested but expected to work**: other P4Runtime-spec-compliant
  targets like Tofino and Stratum. jp4's API is target-agnostic; CI
  only exercises BMv2.

### Known issues

- **Top-level-only partial-failure shape (non-BMv2 targets).** When a
  device returns a Write failure without per-update detail entries,
  `WriteResult.failures()` is empty and `allSucceeded()` returns true.
  BMv2 always returns details, so this is dormant against the verified
  target. Targets that surface only top-level status will need a
  `deviceFailed` marker on `WriteResult`; tracked for v0.2.
- **JDK 24+ Netty `Unsafe` warnings.** gRPC's shaded Netty triggers
  Unsafe-deprecation warnings on JDK 24+. The jp4 test JVM passes
  `--enable-native-access=ALL-UNNAMED` to suppress; downstream users
  on JDK 24+ should do the same. See `NOTES.md`.
- **BMv2 Docker mastership-transition quirk.** Under Docker BMv2,
  back-to-back primary handovers occasionally surface the new client's
  first arbitration as a stream error; jp4's connector layer does not
  work around this — production controllers running against Docker
  BMv2 should add explicit retry around `P4Switch.connect`. See
  `NOTES.md`.

### Roadmap (v0.2 candidates)

These were noticed during v0.1 development and are tracked but not
committed for any specific date:

- Multi-switch coordination (a `P4Controller` with deliberate fan-out
  / parallelism / error-aggregation semantics).
- Other entity-type reads — counters, meters, registers, action
  profiles, multicast groups, packet replication.
- `ReadQuery.where(Predicate<TableEntry>)` and `ReadQuery.fields(...)`
  for client-side filtering and projection.
- `DeviceConfig.Tofino` variant alongside `Bmv2` and `Raw`.
- `sw.onPacketDropped(Consumer<DropEvent>)` hook for backpressure
  observability.
- Symmetric `fromBytes` factories on `DeviceConfig.Bmv2 / .Raw`,
  `Ip4` / `Mac` / `Ip6`. `ActionInstance.paramInt(...)` / `paramLong(...)`
  to mirror `PacketIn.metadataInt(...)`.
- `ReconnectPolicy.preserveRoleOnReconnect()` so primary clients
  re-arbitrate automatically after an auto-reconnect.
- `Match.lpm(String cidr)` CIDR-string factory (e.g.
  `Match.lpm("10.0.1.0/24")`) so common LPM construction is one call
  instead of three. Scope (IPv4-only / IPv6-only / both via
  prefix-detection) decided at v0.2 design time.
- `MastershipStatus.Lost` / `Acquired` `toString()` simplification.
  Current rendering is verbose
  (`Lost[previousElectionId=null, currentPrimaryElectionId=ElectionId(10)]`);
  a compact form like `Lost(prev=null, primary=10)` reads better
  in pasted log output and downstream documentation.

[Unreleased]: https://github.com/zhh2001/jp4/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zhh2001/jp4/releases/tag/v0.1.0
