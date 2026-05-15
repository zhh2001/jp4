# Changelog

All notable changes to jp4 are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(future v1.x work tracked in the Roadmap section)

## [1.5.0] — 2026-05-16

v1.5 is a SemVer-minor addition over v1.4; the v1.4 public surface
is unchanged. See [`docs/migration-1.4-to-1.5.md`](docs/migration-1.4-to-1.5.md)
for usage examples of each new method. The v1.5 release completes
the per-entity-type read surface: the two packet replication engine
entities — multicast groups and clone sessions — are now readable
through name-less typed query builders that take controller-assigned
numeric ids only, the program-agnostic counterpart to the v1.4
table-driven reads.

### Added

- **`P4Switch.readMulticastGroup()` + `MulticastGroupReadQuery`
  interface + `MulticastGroupEntry` record** (commit `b893a53`) —
  name-less typed read of one multicast group programmed on the
  device's packet replication engine. The query builder offers a
  `groupId(long)` server-side filter, a non-default
  `where(Predicate)` client-side filter, and the five terminals
  (`all` / `one` / `stream` / `allAsync` / `oneAsync`). The entry
  method takes no `String` argument — the packet replication engine
  is program-agnostic and multicast groups are addressed by
  controller-assigned numeric id only. `MulticastGroupEntry` is a
  three-field record (`multicastGroupId`, an ordered list of
  `Replica` fan-out slots, opaque controller-defined `Bytes
  metadata`); the metadata field was added in P4Runtime 1.4.0 and
  surfaces as empty bytes on older devices.
- **Shared `Replica` and `BackupReplica` records** (commit
  `b893a53`) — entity-package records used by both multicast groups
  and the forthcoming clone sessions. `Replica` carries a nullable
  `Bytes port`, the per-clone `instance` id, and an ordered list of
  `BackupReplica` fallback ports. The `port` field is nullable in
  two cases that v1.5 treats identically: when the P4Runtime
  `port_kind` oneof is unset, and when the deprecated
  `egress_port` int32 field is set instead — the same flat-nullable
  shape `WeightedMember.watchPort` uses for the action-profile-group
  `watch_kind` oneof shipped in v1.4. `BackupReplica` is a record
  corresponding to a P4Runtime 1.5.0 spec addition, the first jp4
  release that surfaces a v1.5-spec-level type; older devices return
  empty `backup_replicas` lists.
- **`P4Switch.readCloneSession()` + `CloneSessionReadQuery`
  interface + `CloneSessionEntry` record** (commit `f709b5a`) —
  name-less typed read of one clone session, mirroring the
  multicast-group shape with `sessionId(long)` as the server-side
  filter. `CloneSessionEntry` is a four-field record carrying the
  `sessionId`, an ordered list of `Replica` fan-out slots, a
  `classOfService` widened from the proto's `uint32` to a Java
  `long` (the same widening convention `groupId`, `memberId`,
  `multicastGroupId`, and `sessionId` itself already follow on
  existing entity records), and a `packetLengthBytes` truncation
  control (mapped to plain `int` because the proto declares it as
  `int32` rather than `uint32`). A `packetLengthBytes` of `0` means
  "do not truncate"; a positive value means "truncate cloned
  payload to this many bytes".
- **BMv2 end-to-end integration tests for the new PRE read
  surfaces** (commit `8032783`) — a dedicated
  `PacketReplicationEngineIntegrationTest` class drives a real
  `simple_switch_grpc` instance through wire-level Write + Read
  round-trips for one multicast group and one clone session. Local
  verification against `simple_switch_grpc` 1.15.1 found that BMv2
  fully implements both `MulticastGroupEntry` and
  `CloneSessionEntry` Read RPCs — the opposite of the
  `RegisterEntry` case from v1.4 where the same BMv2 returns
  `UNIMPLEMENTED`. The new tests retain a defensive
  `Assumptions.assumeTrue` skip pattern on `UNIMPLEMENTED` even so,
  for forward-compatibility against older BMv2 builds or alternate
  spec-compliant servers that might refuse the read. The fixture
  `counters_meters_registers_groups.p4` from v1.4 serves these
  tests as-is — the packet replication engine carries no
  P4-program declarations, so no fixture extension is needed.

## [1.4.0] — 2026-05-16

v1.4 is a SemVer-minor addition over v1.3; the v1.3 public surface
is unchanged. See [`docs/migration-1.3-to-1.4.md`](docs/migration-1.3-to-1.4.md)
for usage examples of each new method. The v1.4 release completes
the per-entity-type read surface: counter, meter, register,
action-profile member, and action-profile group cells are now
readable through name-based, typed query builders that mirror the
shape `ReadQuery` already uses for table reads.

### Added

- **P4Info index extension for the four new entity types** (commit
  `a280a1e`) — `P4Info` now indexes counters, meters, registers, and
  action profiles in the same parse-loop pass that already indexes
  tables and actions, with paired forward (`counter(String)`,
  `meter(String)`, `register(String)`, `actionProfile(String)`),
  reverse-id (`counterById(int)`, `meterById(int)`,
  `registerById(int)`, `actionProfileById(int)`), and name-listing
  (`counterNames()`, `meterNames()`, `registerNames()`,
  `actionProfileNames()`) accessors. The forward accessors throw
  `P4PipelineException` with a known-list hint on miss; the reverse
  accessors return `null`, matching the convention shared with
  `tableInfoById` / `actionInfoById`.
- **Four pipeline value types** (commit `a280a1e`) —
  `CounterInfo` (name + id + unit + size), `MeterInfo` (name + id
  + unit + size; the spec's `MeterSpec.Unit` exposes only
  `UNSPECIFIED` / `BYTES` / `PACKETS`, not the `BOTH` shape
  counters have), `RegisterInfo` (name + id + size — per-cell
  `P4DataTypeSpec` is intentionally not exposed, matching the
  convention `DigestEvent.data` follows for `P4Data`), and
  `ActionProfileInfo` (name + id + size + max-group-size + the
  table-id set the profile is referenced from).
- **`P4Switch.readCounter(String)` + `CounterReadQuery` interface
  + `CounterEntry` record** (commit `de3e031`) — name-based typed
  read of one counter array. The query builder offers an
  `index(long)` server-side filter, a non-default
  `where(Predicate)` client-side filter, and the five terminals
  (`all` / `one` / `stream` / `allAsync` / `oneAsync`).
  `CounterEntry` is a flat record carrying the resolved counter
  name, the cell index, and both `packetCount` and `byteCount`;
  which value is meaningful is determined by the counter's unit
  through `P4Info.counter(name).unit()`. The implementation
  reuses the existing `outboundExecutor`, `awaitRead`,
  `mapReadFailure`, and `readabilityException` infrastructure
  verbatim, establishing the per-entity read template the v1.4
  surface expands.
- **`P4Switch.readMeter(String)` + `MeterReadQuery` interface +
  `MeterEntry` / `MeterConfig` / `MeterCounterData` /
  `CounterData` records** (commit `e29bd3e`) — name-based typed
  read of one meter array. `MeterEntry` is a nested record
  (meter name, index, `MeterConfig`, `MeterCounterData`); the
  shape mirrors the P4Runtime `MeterEntry` proto directly. The
  nested `MeterConfig` carries `cir`, `cburst`, `pir`, `pburst`,
  and `eburst` — the last was added in P4Runtime 1.4.0 and is
  only used by srTCM meters; for trTCM meters or for devices that
  predate the addition it surfaces as zero. `MeterCounterData`
  groups the per-color cumulative counters (green / yellow / red)
  defined by RFC 2697 / RFC 2698; the message itself was added in
  P4Runtime 1.4.0. `CounterData` is a small two-field nested
  helper (`packetCount`, `byteCount`) used only inside
  `MeterCounterData`; `CounterEntry` deliberately does not share
  it because a counter cell carries a single counter datum
  whereas a meter cell carries three.
- **`P4Switch.readRegister(String)` + `RegisterReadQuery`
  interface + `RegisterEntry` record** (commit `dd152cf`) —
  name-based typed read of one register array. `RegisterEntry`
  is a three-field record (register name, index, `Bytes data`)
  whose `data` is the serialised bytes of the wire `p4.v1.P4Data`
  proto — that is, what `proto.getData().toByteArray()` returns,
  not what `proto.getData().getBitstring()` returns. This matches
  the convention `DigestEvent.data` already follows and the
  contract `RegisterInfo`'s javadoc promised in `a280a1e`.
  Consumers decode via
  `P4Data.parseFrom(entry.data().toByteArray())` and access the
  appropriate oneof variant; for the common `bit<W>` / `int<W>`
  register the field is `.getBitstring()`. Typed P4Data
  unwrapping is held for a future v1.x release.
- **`P4Switch.readActionProfileMember(String)` and
  `readActionProfileGroup(String)` + their
  `ActionProfileMemberReadQuery` and
  `ActionProfileGroupReadQuery` interfaces + three records
  (`ActionProfileMember`, `ActionProfileGroup`, `WeightedMember`)
  + new `ActionInstance.of(String, Map<String, Bytes>)` factory**
  (commit `6e43a4b`) — name-based typed read of both halves of
  the action-profile entity family. Each query builder offers a
  server-side id filter (`memberId(long)` and `groupId(long)`
  respectively), a non-default `where(Predicate)`, and the five
  terminals. `ActionProfileMember` reuses the existing
  `ActionInstance` value type for its action; the new public
  static `ActionInstance.of` factory is the smallest surface
  addition that lets the read path build the record without
  going through the `TableEntryBuilder` chain. `WeightedMember`
  carries `memberId`, `weight`, and a nullable `Bytes watchPort`
  whose null surface covers both an unset `watch_kind` oneof and
  the deprecated `watch` int32 path; a controller that needs the
  deprecated path can parse the wire proto directly.
- **BMv2 integration tests for the new read surfaces** (commit
  `1199952`) — a dedicated `counters_meters_registers_groups.p4`
  fixture declares all four indirect features (counter, meter,
  register, action_selector) in one pipeline so a single BMv2
  spawn amortises across the five read API families.
  `CountersMetersRegistersGroupsIntegrationTest` reads counter,
  meter, register, action-profile member, and action-profile
  group cells against a real `simple_switch_grpc` instance.
  Two BMv2-shape findings shape the test code: first,
  `simple_switch_grpc` 1.15.1 returns `UNIMPLEMENTED` for the
  `RegisterEntry` read RPC, so the register integration test
  catches that status code and skips through
  `Assumptions.assumeTrue` until BMv2 adds support — the unit
  test in `P4SwitchReadRegisterTest` already covers the jp4
  read path end-to-end against the gRPC fake; second, P4Runtime
  1.3+ canonical-bytestring encoding strips leading zero bytes,
  so a 9-bit port value of 5 sent to BMv2 as `{0x00, 0x05}`
  round-trips as a single-byte `{0x05}` on the read side, and
  the action-profile tests send the canonical form to match.

## [1.3.0] — 2026-05-14

v1.3 is a SemVer-minor addition over v1.2; the v1.2 public surface
is unchanged. See [`docs/migration-1.2-to-1.3.md`](docs/migration-1.2-to-1.3.md)
for usage examples of each new method. The v1.3 release completes the
stream-message dispatch family: a P4Runtime device's inbound
DigestList and IdleTimeoutNotification arms now have typed event
records, listener APIs, and the corresponding control-plane enable
surface, making both feature families end-to-end usable against a
real BMv2 device.

### Added

- **`DigestEvent` record** (commit `c5ed6b1`) — typed value carrying
  the resolved digest name, ack-protocol list id, an immutable list
  of raw per-entry payload bytes, a wall-clock timestamp, and the
  numeric digest id. Delivered by the new
  `P4Switch.onDigest(Consumer<DigestEvent>)` listener; each entry in
  the `data` list is the serialised form of one `p4.v1.P4Data`
  message that consumers decode through `P4Data.parseFrom` if they
  want a typed view.
- **`IdleTimeoutEvent` record** (commit `c5ed6b1`) — typed value
  carrying an immutable list of `TableEntry`s that idled out and a
  wall-clock timestamp. The list preserves the wire shape directly,
  so a single notification can span multiple tables and the
  library does not regroup by `table_id`.
- **`P4Info.digestNameById(int)` and `digestIdByName(String)`
  accessors** (commits `252990b`, `617f5bb`) — paired forward and
  reverse index of digest extern declarations from P4Info, populated
  in one parse-loop alongside the existing
  `tableInfoById` / `actionInfoById` / `packetInFieldById` family.
  Both return null when the name or id is unknown, matching the
  lookup-fail-equals-null convention shared across the existing
  reverse-id accessors.
- **`P4Switch.onDigest(Consumer<DigestEvent>)`** (commit `3d4e91e`)
  — single replaceable listener for inbound `DigestList` stream
  messages, ack-first by design: the dispatch path issues a
  `DigestListAck` for every received list unconditionally — before
  any drop check or listener delivery — so the device's spec-defined
  `ack_timeout_ns` suppression window is never entered when the
  pipeline is unbound, when no listener is registered, or when
  P4Info has no digest with the received id. Eagerly requires a
  bound pipeline at registration so the silent-drop trap that would
  otherwise apply is caught at the call site.
- **`DigestConfig` record + `P4Switch.enableDigest(String,
  DigestConfig)` / `enableDigestAsync`** (commit `617f5bb`, refined
  in `85c8744`) — the control-plane enable surface for digest
  emission. The record carries the three knobs the P4Runtime
  `DigestEntry.Config` defines: `max_timeout_ns`, `max_list_size`,
  and `ack_timeout_ns`; the method writes a `DigestEntry` update
  through the same outbound dispatch path that backs
  `insert / modify`. Without this call the device emits no
  `DigestList` even if `onDigest` is registered; the pair is
  required for the dispatch family to actually surface traffic.
- **`P4Switch.onIdleTimeout(Consumer<IdleTimeoutEvent>)`** (commit
  `e7a54a9`) — single replaceable listener for
  `IdleTimeoutNotification`. The dispatch path reverse-parses each
  wire `TableEntry` through `EntryProto.fromProto`, the same parser
  the Read RPC uses. An entry on an action-profile or selector
  table is rejected by that parser today; the dispatch helper
  catches the exception, WARN-logs the drop, and swallows the whole
  notification rather than delivering a partial event — matching
  the per-message fail-open posture `dispatchPacketIn` uses for
  unparseable inbound packets. Unlike `onDigest`, no outbound ack
  is sent: `IdleTimeoutNotification` has no corresponding
  `StreamMessageRequest` arm per the P4Runtime spec.
- **`TableEntry.idleTimeoutNs()` accessor +
  `TableEntryBuilder.idleTimeoutNs(long)` setter +
  `ActionBuilder.idleTimeoutNs(long)` passthrough** (commit
  `5d5bd28`) — the control-plane enable surface for idle expiration.
  Setting the value to a positive number opts a table entry into
  idle-timeout aging on devices whose P4 program declared the
  containing table with idle-timeout support; the device fires an
  `IdleTimeoutNotification` for the entry once it has not been hit
  within the configured window. The default `0` matches the
  v1.0 / v1.1 / v1.2 behaviour and the protobuf wire encoding then
  omits the field. `EntryProto.toProto` writes the field
  conditionally on `> 0`, mirroring the existing `setPriority`
  convention; `EntryProto.fromProto` round-trips it back onto
  the builder so a Read response carries the value forward.
- **BMv2 integration tests for the new dispatch and enable
  surfaces** (commit `85c8744`) — a dedicated `digest_idle.p4`
  fixture declares one digest extern and one exact-match table with
  idle-timeout support; `DigestIntegrationTest` and
  `IdleTimeoutIntegrationTest` drive a real BMv2 instance through
  the full enable + dispatch loop and assert the device emits
  `DigestList` and `IdleTimeoutNotification` for the configured
  paths. The same commit also corrects two implementation details
  surfaced by the integration tests: `enableDigestAsync` issues
  the `DigestEntry` write with `Update.Type.INSERT` rather than
  `MODIFY` (BMv2 rejects `MODIFY` for a digest that has not yet
  been INSERTed), and the new P4 fixture uses the standalone
  `support_timeout = true` table property rather than the
  `@support_timeout(true)` annotation so p4c emits
  `idle_timeout_behavior = NOTIFY_CONTROL_PLANE` in the P4Info
  (BMv2 reads the typed field rather than the annotation).

## [1.2.0] — 2026-05-11

v1.2 is a SemVer-minor addition over v1.1; the v1.1 public surface
is unchanged. See [`docs/migration-1.1-to-1.2.md`](docs/migration-1.1-to-1.2.md)
for usage examples of each new method. The v1.2 release introduces
the packet-ingestion control surface: a typed drop event, a listener
to observe drops, and a pre-fan-out filter to reject inbound packets
before any sink sees them.

### Added

- **`DropEvent` record + `Reason` enum** (commit `4e88813`) — a
  typed value carrying the dispatch-site reason
  (`SUBSCRIBER_LAG`, `QUEUE_FULL`, `FILTERED`), wall-clock
  timestamp, parsed `PacketIn`, and a free-form human-readable
  message useful for log correlation without machine-parsing the
  reason enum. The record is the data shape the new
  `P4Switch.onPacketDropped` listener delivers.
- **`P4Switch.onPacketDropped(Consumer<DropEvent>)`** (commit
  `8ca1a5b`) — single replaceable listener for inbound PacketIn
  drops detected by the dispatch path. Observes `SUBSCRIBER_LAG`
  (a `Flow.Publisher` subscriber offer drop) and `QUEUE_FULL`
  (the poll-style deque at capacity); a future `FILTERED` reason
  fires from the `Connector.packetInFilter` dispatch site. Mirrors
  `onMastershipChange` shape: volatile field, NPE on null,
  last-write-wins, runs on the shared single-threaded callback
  executor. Existing WARN logs on each drop site fire
  synchronously before the listener is scheduled — log and
  listener are independent surfaces (operator-grep vs
  application-handle).
- **`Connector.packetInFilter(Predicate<? super PacketIn>)`**
  (commit `c35eb5a`) — pre-fan-out filter that runs in
  `P4Switch.dispatchPacketIn` after `PacketProto.parseIn` and
  before the callback / Publisher / deque fan-out. A packet for
  which the filter returns false is dropped (no sink sees it) and
  a `FILTERED` `DropEvent` fires through the
  `P4Switch.onPacketDropped` listener with the message
  `"rejected by packetInFilter"`. A filter that throws is treated
  as a drop (safe default), logs at WARN, and fires a `FILTERED`
  `DropEvent` whose message names the thrown exception's simple
  class name. Default null = pass all; v1.0 / v1.1 callers see no
  behaviour change. The `Predicate<? super PacketIn>` shape
  matches `ReadQuery.where`'s PECS convention from 1.1.0.

## [1.1.0] — 2026-05-09

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
- `ReadQuery.fields(...)` for server-side or client-side projection;
  the P4Runtime `ReadRequest` spec carries no projection field, so
  this would be a client-side helper. Design TBD; held for a future
  v1.x release.
- `DeviceConfig.Tofino` variant alongside `Bmv2` and `Raw` —
  community-driven; no internal commitment, contributions welcome
  with hardware-validated test results.
- Examples-CI assertion strengthening — current `examples-l2` /
  `examples-lb` / `examples-monitor` jobs grep a small set of
  distinctive lines from each example's stdout. v1.x should diff
  the full captured output against each example's README "Expected
  output" block, so docs-vs-actual mismatches fail CI rather than
  waiting for a manual review pass. The 1.2.0 release added a
  `Connector`-level packet-ingestion control surface (`c35eb5a`)
  that filters noise before it reaches the application; an attempt
  to use that surface to unlock byte-identical diff during the
  c35eb5a-era investigation found that the residual loss-rate flake
  on busy loopback hosts has its root cause upstream of jp4 —
  BMv2's outbound StreamChannel saturates under sustained loopback
  noise and AA / BB demo packets are queued behind the noise.
  Held; the root cause is environmental (BMv2 + interface choice)
  and a fix would either move the demos to a quieter interface
  (veth pair) or change the demo timing budget. The Connector-level
  packet-ingestion control surface itself (`c35eb5a`) is shipped
  and useful for any application running against an environment
  with real noise; this Examples-CI entry remains held only because
  the demos currently run on an interface where lo-noise dominates.

[Unreleased]: https://github.com/zhh2001/jp4/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/zhh2001/jp4/releases/tag/v1.5.0
[1.4.0]: https://github.com/zhh2001/jp4/releases/tag/v1.4.0
[1.3.0]: https://github.com/zhh2001/jp4/releases/tag/v1.3.0
[1.2.0]: https://github.com/zhh2001/jp4/releases/tag/v1.2.0
[1.1.0]: https://github.com/zhh2001/jp4/releases/tag/v1.1.0
[1.0.0]: https://github.com/zhh2001/jp4/releases/tag/v1.0.0
[0.1.0]: https://github.com/zhh2001/jp4/releases/tag/v0.1.0
