# Changelog

All notable changes to jp4 are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(future v1.x work tracked in the Roadmap section)

## [1.1.0] — YYYY-MM-DD

<!-- date filled at release time -->

v1.1 is a SemVer-minor addition over v1.0; the v1.0 public surface
is unchanged. See [`docs/migration-1.0-to-1.1.md`](docs/migration-1.0-to-1.1.md)
for usage examples of each new method.

### Added

- **`ReadQuery.where(Predicate<? super TableEntry>)`** (commit
  `6277b18`) — client-side filtering on the read result. Each call
  appends a predicate; entries that fail any predicate are excluded
  from the terminal call (`.all()` / `.one()` / `.stream()` and async
  variants). The method is a default on the `ReadQuery` interface
  that throws `UnsupportedOperationException`, naming `1.1.0` and
  directing the caller to update; the built-in implementation
  returned by `P4Switch.read(...)` overrides it. SemVer-safe for any
  external implementer of `ReadQuery`.
- **`Connector.preserveRoleOnReconnect(boolean)`** (commit
  `53e4e8e`) — opts a `.asPrimary()` connector into fail-fast on
  reconnect role downgrade. When enabled and a reconnect yields a
  non-Primary role (because another client arbitrated higher during
  the disconnect window), the switch closes itself and stores the
  resulting `P4ArbitrationLost` as the close cause; subsequent user
  calls throw that cause via the existing writability and readability
  gates instead of the generic `"switch is closed"`. Default `false`
  preserves the v1.0 silent-Secondary behaviour.
- **`Mac.ZERO`** (commit `d1bf319`) — public `static final Mac`
  constant for the all-zero MAC address (`00:00:00:00:00:00`),
  useful as a sentinel for invalid or default-initialised source
  addresses.

### Documentation

- **AI-tool and bot attribution rules in `CONTRIBUTING.md`**
  (commit `3fb8354`) — the commit-message section now enumerates the
  rejected forms (AI-naming `Co-Authored-By:` trailers, footer
  banners citing automated assistance, bot emails matching
  `noreply@*`, robot or AI-tool emoji), and preserves the existing
  carve-out for `Co-Authored-By:` lines naming real human
  collaborators.
- **Post-1.0 source-reference cleanup** (commit `fc70d0d`) — the
  build version was bumped from `1.0.0` to `1.1.0-SNAPSHOT`,
  opening the v1.1 development cycle, and 17 production / docs /
  example references to `v0.1` and `v0.2` were updated to reflect
  post-1.0 reality (descriptive comments naming `v0.1` ship-set
  updated to `v1.0`; forward-looking `v0.2 will...` notes updated to
  `v1.x will...`).

## [1.0.0] — 2026-05-08

v1.0 locks the public API surface; the surface is stable across v1.x
patches. See [`docs/migration-0.1-to-1.0.md`](docs/migration-0.1-to-1.0.md)
for v0.1 → v1.0 surface changes with before/after examples.

### Added

- **`ActionInstance.paramInt(String)` and `paramLong(String)`**
  (commit `b544caa`) — convenience accessors mirroring
  `PacketIn.metadataInt(String)`. Extract a primitive integer
  parameter without manually wrapping
  `new BigInteger(1, b.toByteArray())`. Throws
  `IllegalStateException` on absent parameter or value too wide for
  the target primitive.
- **`PacketIn.metadataLong(String)`** (commit `b544caa`) —
  long-shaped sister of `metadataInt(String)`, closing the asymmetry
  with `paramInt` / `paramLong` on `ActionInstance`.
- **Symmetric `fromBytes(byte[])` factories** (commit `42e3cfa`) —
  `Ip4.fromBytes(byte[])`, `Ip6.fromBytes(byte[])`,
  `Mac.fromBytes(byte[])`, `DeviceConfig.Bmv2.fromBytes(byte[])`,
  `DeviceConfig.Raw.fromBytes(byte[])`. The naming family mirrors
  the pre-existing `P4Info.fromBytes(byte[])`. Defensive copy on
  construction.

### Changed

- **`MastershipStatus.toString()` format** (commit `23481ce`) —
  replaced the default record rendering with a compact, grep-friendly
  form: `Acquired(primary=10)`, `Lost(prev=null, primary=10)`,
  `Lost(prev=5, primary=10)`. The unified `primary=N` field name lets
  one grep pattern catch both states. Practically disruptive for log
  parsers; the underlying `ElectionId.toString()` is unchanged.
- **`DeviceConfig.{Bmv2,Raw}.fromFile`** (commit `2bb1acd`) — wraps
  `IOException` as `java.io.UncheckedIOException` instead of plain
  `RuntimeException`. Strictly more specific (subclass of
  `RuntimeException`), so existing `catch (RuntimeException)`
  continues to work unchanged.
- **Accessor methods reject null parameter** (commit `ff71c89`) —
  `ActionInstance.param(String)`, `PacketIn.metadata(String)`, and
  `TableEntry.match(String)` now throw `NullPointerException` for a
  null name, aligning with the project-wide null-rejection convention
  documented in each `package-info.java`. Unknown but non-null names
  still return `null` as before.
- **`MastershipStatus.Acquired(null)` rejected** (commit `ff71c89`) —
  the `Acquired` record's canonical constructor now rejects a null
  `ourElectionId` with `NullPointerException`. The `Lost` record's
  two `ElectionId` fields remain nullable on purpose.
- **Actionable error messages on width-overflow** (commit `b544caa`) —
  the `IllegalStateException` thrown by `metadataInt` / `metadataLong`
  / `paramInt` / `paramLong` when a value exceeds the target
  primitive's range now names the recommended alternative ("use
  metadataLong or metadata(String) directly").

### Documentation

- **Thread-safety contracts** (commit `9c58ee6`) — explicit
  thread-safety paragraphs on 11 user-facing classes (the four
  builders, `ReadQuery`, three sealed-type interfaces, and three
  schema-family final classes). Builders are not safe for concurrent
  use; sealed-type record variants and schema metadata are safe to
  share across threads.
- **Null contract** (commit `ff71c89`) — explicit project-wide
  convention statement in all four `package-info.java` files: public
  methods reject null arguments with `NullPointerException` unless
  documented otherwise. Methods that accept null on purpose document
  the null semantics in their `@param`.
- **Production readiness** (commit `b106cfa`) — README
  `## Production readiness` section with three subsections:
  validation status (what the CI matrix exercises), production-ready
  scope, known limitations and v1.x roadmap.
- **Migration guide** (commit `8f20b03`) — new
  [`docs/migration-0.1-to-1.0.md`](docs/migration-0.1-to-1.0.md)
  with before/after examples and per-change severity tags for
  callers updating from v0.1.

## [0.1.0] — 2026-05-07

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

### Roadmap (v1.x candidates)

These are tracked for v1.x point releases without committed dates:

- Multi-switch coordination (a `P4Controller` with deliberate fan-out
  / parallelism / error-aggregation semantics).
- Other entity-type reads — counters, meters, registers, action
  profiles, multicast groups, packet replication.
- `ReadQuery.fields(...)` for server-side or client-side projection;
  the P4Runtime `ReadRequest` spec carries no projection field, so
  this would be a client-side helper. Design TBD; held for a future
  v1.x release.
- `DeviceConfig.Tofino` variant alongside `Bmv2` and `Raw` —
  community-driven; no internal commitment, contributions welcome
  with hardware-validated test results.
- `sw.onPacketDropped(Consumer<DropEvent>)` hook for backpressure
  observability.
- Digest and IdleTimeout stream-message handlers (P4Runtime spec
  §7 / §11.4) — currently dropped at the inbound parser; v1.x will
  add typed subscription APIs matching the existing `onPacketIn` /
  `onMastershipChange` shape.
- Examples-CI assertion strengthening — current `examples-l2` /
  `examples-lb` / `examples-monitor` jobs grep a small set of
  distinctive lines from each example's stdout. v1.x should diff the
  full captured output against each example's README "Expected
  output" block, so docs-vs-actual mismatches fail CI rather than
  waiting for a manual review pass. Reliable byte-identical diff
  requires a `Connector`-level packet-ingestion control surface (so
  unrelated kernel traffic on the BMv2 ingress interface cannot leak
  into the example output via the unbounded fan-out path); design
  TBD, held for a future v1.x release.

[Unreleased]: https://github.com/zhh2001/jp4/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/zhh2001/jp4/releases/tag/v1.1.0
[1.0.0]: https://github.com/zhh2001/jp4/releases/tag/v1.0.0
[0.1.0]: https://github.com/zhh2001/jp4/releases/tag/v0.1.0
