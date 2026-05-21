---
title: Migration guide — jp4 v1.3 → v1.4
description: API surface additions in jp4 v1.4 — typed entity reads for counters, meters, registers, action-profile members and groups. Shared query-builder shape across all five families. Includes the BMv2 register-UNIMPLEMENTED and canonical-bytestring limitations.
keywords: [jp4, migration guide, v1.3, v1.4, readCounter, readMeter, readRegister, action-profile, canonical bytestring, BMv2 UNIMPLEMENTED]
---

<!-- doc-lint: skip-file (migration guide; code blocks are usage examples for the v1.3 -> v1.4 additive surface, not user-facing best-practice examples) -->

# Migration guide: jp4 v1.3 → v1.4

This guide documents the API surface changes between v1.3 and v1.4,
with usage examples for callers adopting the new methods. It is the
authoritative reference for the v1.3 → v1.4 transition; the
[CHANGELOG](https://github.com/zhh2001/jp4/blob/main/CHANGELOG.md) carries the same information in
release-note form.

v1.4 is a SemVer-minor addition over v1.3:

- **Public surface**: 0 method removals, 0 signature changes, 0
  behaviour changes.
- **What's new**: the per-entity-type read surface is now complete.
  Counter, meter, register, action-profile member, and
  action-profile group cells are readable through name-based,
  typed query builders that mirror the shape `ReadQuery` already
  uses for table reads.
- **Migration effort**: zero. v1.3 patterns continue to work
  unchanged; the new methods are entirely opt-in.

The guide reflects this profile by omitting "Behaviour changes" and
"Documentation changes" sections that would be empty.

Each entry below cites the commit hash where the change landed; use
`git show <hash>` for the full diff.

## Additions (opt-in)

These new methods and types are available on the v1.4 surface; v1.3
patterns continue to work indefinitely.

### P4Info index extension

(commit `a280a1e`, severity: opt-in addition)

`P4Info` now indexes counters, meters, registers, and action
profiles in the same parse-loop pass that already indexes tables and
actions. Three accessor groups land per entity type: forward by
name, reverse by id, and a name listing. The forward accessors throw
`P4PipelineException` with a known-list hint on miss; the reverse
accessors return `null`, matching the convention shared with
`tableInfoById` / `actionInfoById`.

```java
// Forward: name → typed value object. Eager validation; throws
// P4PipelineException("no counter named ... known: [...]") if missing.
CounterInfo cinfo  = pipe.p4info().counter("MyIngress.pkt_counter");
MeterInfo   minfo  = pipe.p4info().meter("MyIngress.rate_meter");
RegisterInfo rinfo = pipe.p4info().register("MyIngress.flow_counters");
ActionProfileInfo apinfo = pipe.p4info().actionProfile("MyIngress.lb_ap");

// Reverse: id → typed value object. Returns null on unknown id.
CounterInfo cellOwner = pipe.p4info().counterById(0x0c000001);

// Listing: every name declared in the bound P4Info.
List<String> counters = pipe.p4info().counterNames();
```

The four new value types — `CounterInfo`, `MeterInfo`,
`RegisterInfo`, `ActionProfileInfo` — are immutable and safe to
share across threads. `MeterInfo.Unit` exposes only `UNSPECIFIED`,
`BYTES`, and `PACKETS` because the P4Runtime `MeterSpec.Unit` proto
itself only has those three; counters carry the additional `BOTH`
variant. `RegisterInfo` does not expose the per-cell
`P4DataTypeSpec` — typed register payloads are held for a future
v1.x release, matching the convention `DigestEvent.data` follows for
`P4Data`. `ActionProfileInfo` exposes the table-id set the profile
is referenced from, so consumers can locate the selector-driven
tables without re-walking the P4Info.

### `P4Switch.readCounter` and `CounterReadQuery`

(commit `de3e031`, severity: opt-in addition)

Name-based typed read of one counter array. The query builder
offers an `index(long)` server-side filter (populates the wire
`Index` field), a `where(Predicate<? super CounterEntry>)`
client-side filter applied after fetch, and the five terminals:
`all` / `one` / `stream` / `allAsync` / `oneAsync`. The interface
is new in v1.4, so the `where` method has no default body — the
canonical implementation returned by `P4Switch.readCounter` is the
only implementer.

```java
// Read every cell in MyIngress.pkt_counter.
List<CounterEntry> all = sw.readCounter("MyIngress.pkt_counter").all();
for (CounterEntry e : all) {
    log.info("cell {} packets={} bytes={}",
            e.index(), e.packetCount(), e.byteCount());
}

// Read one specific cell; throws P4OperationException if the device
// returns more than one entry.
Optional<CounterEntry> cell0 = sw.readCounter("MyIngress.pkt_counter")
        .index(0L)
        .one();

// Client-side filter: only cells that have seen traffic.
List<CounterEntry> nonZero = sw.readCounter("MyIngress.pkt_counter")
        .where(e -> e.packetCount() > 0L)
        .all();
```

`CounterEntry` is a flat record carrying the resolved counter name
(reverse-looked up from the wire `counter_id` during response
parsing), the cell index, and both `packetCount` and `byteCount` as
primitive `long`s. Which value is meaningful is determined by the
counter's unit through `P4Info.counter(name).unit()` — a `BYTES`
counter reports `byteCount`, a `PACKETS` counter reports
`packetCount`, a `BOTH` counter populates both.

### `P4Switch.readMeter` and `MeterReadQuery`

(commit `e29bd3e`, severity: opt-in addition)

Name-based typed read of one meter array. Same query builder shape
as `CounterReadQuery`, typed on `MeterEntry`.

```java
// Read every cell with its rate configuration and per-color counter data.
List<MeterEntry> all = sw.readMeter("MyIngress.rate_meter").all();
for (MeterEntry e : all) {
    MeterConfig cfg = e.config();
    MeterCounterData cd = e.counterData();
    log.info("cell {} cir={} cburst={} green_packets={} red_packets={}",
            e.index(), cfg.cir(), cfg.cburst(),
            cd.green().packetCount(), cd.red().packetCount());
}
```

`MeterEntry` is a nested record (`meterName`, `index`,
`MeterConfig`, `MeterCounterData`); the nesting mirrors the
P4Runtime `MeterEntry` proto directly. `MeterConfig` carries
`cir`, `cburst`, `pir`, `pburst`, and `eburst` as primitive
`long`s — the last was added in P4Runtime 1.4.0 and is only used
by srTCM meters; for trTCM meters or for devices that predate the
addition it surfaces as zero. `MeterCounterData` groups the three
per-color cumulative counters (green / yellow / red) defined by
RFC 2697 / RFC 2698 (srTCM and trTCM); the `MeterCounterData`
proto message itself was added in P4Runtime 1.4.0. `CounterData` is
a small two-field nested helper (`packetCount`, `byteCount`) used
only inside `MeterCounterData` — `CounterEntry` deliberately does
not share it because a counter cell carries a single counter
datum whereas a meter cell carries three colored ones.

### `P4Switch.readRegister` and `RegisterReadQuery`

(commit `dd152cf`, severity: opt-in addition)

Name-based typed read of one register array. Same query builder
shape, typed on `RegisterEntry`.

```java
import p4.v1.P4DataOuterClass.P4Data;

// Read every cell; decode the typed value from the serialised P4Data bytes.
List<RegisterEntry> all = sw.readRegister("MyIngress.flow_counters").all();
for (RegisterEntry e : all) {
    P4Data datum = P4Data.parseFrom(e.data().toByteArray());
    // For the common bit<W> / int<W> register, the payload is the
    // bitstring oneof variant:
    byte[] value = datum.getBitstring().toByteArray();
    log.info("cell {} value-bytes={}", e.index(), value.length);
}
```

`RegisterEntry` is a flat three-field record (register name, cell
index, and a `Bytes data`). The `data` field is the serialised
bytes of the wire `p4.v1.P4Data` proto message — that is, what
`proto.getData().toByteArray()` returns, not what
`proto.getData().getBitstring()` returns. This matches the
convention `DigestEvent.data` already follows and the contract
`RegisterInfo`'s javadoc promised when the index landed in
`a280a1e`: "v1.4 surfaces register cell data as raw `Bytes` on the
read path, matching the convention `DigestEvent` uses for
`P4Data`." Preserving the full `P4Data` envelope keeps all twelve
oneof variants reachable — the `bit<W>` and `int<W>` case is
overwhelmingly common in practice, but `struct`, `header`,
`header_stack`, `enum`, `error`, `varbit`, and `bool` registers
are all spec-legal. Typed register payloads — `RegisterEntry` with
a decoded value type field — are held for a future v1.x release.

### `P4Switch.readActionProfileMember` / `readActionProfileGroup` and the action-profile family

(commit `6e43a4b`, severity: opt-in addition)

Name-based typed read of both halves of the action-profile entity
family. Each query builder offers a server-side id filter
(`memberId(long)` and `groupId(long)` respectively), a non-default
`where(Predicate)`, and the five terminals.

```java
// Read every member of an action profile.
List<ActionProfileMember> members =
        sw.readActionProfileMember("MyIngress.lb_ap").all();
for (ActionProfileMember m : members) {
    log.info("member {} -> action {}",
            m.memberId(), m.action().name());
    // The bound action carries its parameter values, name-keyed:
    for (var p : m.action().params().entrySet()) {
        log.info("  param {} = {} bytes",
                p.getKey(), p.getValue().toByteArray().length);
    }
}

// Read every group of an action profile; iterate its weighted members.
List<ActionProfileGroup> groups =
        sw.readActionProfileGroup("MyIngress.lb_ap").all();
for (ActionProfileGroup g : groups) {
    log.info("group {} maxSize={} members={}",
            g.groupId(), g.maxSize(), g.members().size());
    for (WeightedMember wm : g.members()) {
        log.info("  member={} weight={} watchPort={}",
                wm.memberId(), wm.weight(),
                wm.watchPort() == null
                        ? "<unset>"
                        : wm.watchPort().toByteArray().length + " bytes");
    }
}
```

`ActionProfileMember` is a three-field record (`actionProfileName`,
`memberId`, `ActionInstance action`); the existing `ActionInstance`
value type is reused, so an action-profile member round-trips into
the same shape table-entry reads return for a direct action. The
new `public static ActionInstance.of(String name, Map<String, Bytes>
params)` factory is the smallest surface addition that lets the
read path build `ActionInstance` without going through the
`TableEntryBuilder` chain.

`ActionProfileGroup` is a four-field record (`actionProfileName`,
`groupId`, `maxSize`, `List<WeightedMember> members`). The
`maxSize` value is the per-group maximum the device will enforce;
a value of zero means the static `max_group_size` from P4Info
applies instead. `WeightedMember` carries `memberId`, `weight`,
and a nullable `Bytes watchPort` whose null surface covers two
spec cases identically: the `watch_kind` oneof unset, and the
deprecated `watch` int32 field set. The P4Runtime spec does not
expose any third `watch_kind` variant in 1.4, so the two-state
shape is exhaustive; controllers that need the deprecated int32
path can parse the wire `ActionProfileGroup.Member` proto directly.

## Limitations

These are spec-conformant behaviours of the wire protocol or of
specific P4Runtime servers that adopters should know about.

- **BMv2 register reads are upstream UNIMPLEMENTED.**
  `simple_switch_grpc` 1.15.1 (the BMv2 binary jp4 tests against)
  returns `Status{code=UNIMPLEMENTED, description=Register reads
  are not supported yet}` for the `RegisterEntry` read RPC. The
  P4Runtime spec permits a server to refuse a read with
  `UNIMPLEMENTED`, and that is what BMv2 currently does. jp4's
  `readRegister` surface is correct end-to-end and is exercised by
  the unit test in `P4SwitchReadRegisterTest` against an in-process
  gRPC fake; the BMv2 integration test
  (`CountersMetersRegistersGroupsIntegrationTest`) catches the
  UNIMPLEMENTED status and skips through `Assumptions.assumeTrue`,
  so it becomes a no-op until BMv2 adds support.
- **P4Runtime 1.3+ canonical-bytestring encoding strips leading
  zero bytes.** A 9-bit value of 5 sent to the device as
  `{0x00, 0x05}` round-trips as `{0x05}` on the read side; values
  with leading zero bytes returned by a Read RPC are normalised the
  same way. Consumers comparing read-back bytes to a known
  reference should canonicalise the reference (strip leading zero
  bytes) before comparing, or compare numerically by interpreting
  both as unsigned big-endian integers.
- **Typed `P4Data` unwrapping for register cells is deferred.**
  Today `RegisterEntry.data` carries the serialised `p4.v1.P4Data`
  proto bytes; a typed register-value surface that pre-decodes the
  `bit<W>` / `int<W>` case is a future v1.x topic. The decode is
  a single `P4Data.parseFrom(...).getBitstring()` call away on the
  consumer side; the recipe is documented in the
  `P4Switch.readRegister` subsection above and on the
  `RegisterEntry` record's javadoc.

## v1.x roadmap

Capabilities not in v1.4 but tracked for future v1.x point releases
without committed dates. The canonical list lives in the
[CHANGELOG Roadmap section](https://github.com/zhh2001/jp4/blob/main/CHANGELOG.md#roadmap-v1x-candidates);
this section is a cross-reference, kept in sync with that file.

- Multi-switch coordination (a `P4Controller` with deliberate
  fan-out / parallelism / error-aggregation semantics).
- `ReadQuery.fields(...)` for client-side projection. Design TBD;
  held for a future v1.x release.
- `DeviceConfig.Tofino` variant alongside `Bmv2` and `Raw` —
  community-driven; no internal commitment, contributions welcome
  with hardware-validated test results.
- Examples-CI assertion strengthening — held; the 1.2.0 release
  added the `Connector`-level packet-ingestion control surface
  (`c35eb5a`) that filters noise before it reaches the application,
  but investigation during that release prep found the residual
  demo loss-rate flake on busy loopback hosts has its root cause
  upstream of jp4 (BMv2 outbound saturation under sustained
  loopback noise). The Connector-level surface is shipped and
  useful for any application running against an environment with
  real noise; this entry remains held only because the demos
  currently run on an interface where lo-noise dominates.
