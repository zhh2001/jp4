<!-- doc-lint: skip-file (migration guide; code blocks are usage examples for the v1.4 -> v1.5 additive surface, not user-facing best-practice examples) -->

# Migration guide: jp4 v1.4 → v1.5

This guide documents the API surface changes between v1.4 and v1.5,
with usage examples for callers adopting the new methods. It is the
authoritative reference for the v1.4 → v1.5 transition; the
[CHANGELOG](../CHANGELOG.md) carries the same information in
release-note form.

v1.5 is a SemVer-minor addition over v1.4:

- **Public surface**: 0 method removals, 0 signature changes, 0
  behaviour changes.
- **What's new**: the two packet replication engine (PRE) entity
  types — multicast groups and clone sessions — are now readable
  through name-less typed query builders. They are program-agnostic
  entities addressed by controller-assigned numeric ids only, the
  counterpart to the v1.4 table-driven reads that take a P4-program
  name.
- **Migration effort**: zero. v1.4 patterns continue to work
  unchanged; the new methods are entirely opt-in.

The guide reflects this profile by omitting "Behaviour changes" and
"Documentation changes" sections that would be empty.

Each entry below cites the commit hash where the change landed; use
`git show <hash>` for the full diff.

## Additions (opt-in)

These new methods and types are available on the v1.5 surface; v1.4
patterns continue to work indefinitely.

### `P4Switch.readMulticastGroup` and `MulticastGroupReadQuery`

(commit `b893a53`, severity: opt-in addition)

Name-less typed read of one multicast group on the device's packet
replication engine. The query builder offers a `groupId(long)`
server-side filter (populates the wire `multicast_group_id` field),
a `where(Predicate<? super MulticastGroupEntry>)` client-side filter
applied after fetch, and the five terminals: `all` / `one` /
`stream` / `allAsync` / `oneAsync`. Unlike the v1.4 table-driven
read APIs, the entry method takes no `String` name argument: the
packet replication engine is program-agnostic and multicast groups
carry no P4-program declaration.

```java
// Read every multicast group programmed on the device.
List<MulticastGroupEntry> all = sw.readMulticastGroup().all();
for (MulticastGroupEntry g : all) {
    log.info("group {} replicas={} metadata={} bytes",
            g.multicastGroupId(),
            g.replicas().size(),
            g.metadata().toByteArray().length);
}

// Read one specific group; throws P4OperationException if the device
// returns more than one entry.
Optional<MulticastGroupEntry> group7 = sw.readMulticastGroup()
        .groupId(7L)
        .one();

// Client-side filter: only groups whose primary replica targets a
// non-empty port.
List<MulticastGroupEntry> nonEmptyReplicas = sw.readMulticastGroup()
        .where(g -> !g.replicas().isEmpty())
        .all();
```

`MulticastGroupEntry` is a flat three-field record carrying the
`multicastGroupId` (a `long` widened from the proto's `uint32` to
preserve the full unsigned range), an ordered immutable list of
`Replica` fan-out slots, and an opaque `Bytes metadata` payload the
controller stores unchanged. The metadata field was added in
P4Runtime 1.4.0 and surfaces as empty bytes on older devices.

### `P4Switch.readCloneSession` and `CloneSessionReadQuery`

(commit `f709b5a`, severity: opt-in addition)

Name-less typed read of one clone session, mirroring the
multicast-group shape with `sessionId(long)` as the server-side
filter.

```java
// Read every clone session programmed on the device.
List<CloneSessionEntry> all = sw.readCloneSession().all();
for (CloneSessionEntry s : all) {
    log.info("session {} replicas={} cos={} truncate_to={}",
            s.sessionId(),
            s.replicas().size(),
            s.classOfService(),
            s.packetLengthBytes() == 0 ? "<no truncation>" : s.packetLengthBytes() + " bytes");
}

// Read one specific session.
Optional<CloneSessionEntry> session42 = sw.readCloneSession()
        .sessionId(42L)
        .one();
```

`CloneSessionEntry` is a four-field record (`sessionId`, ordered
`Replica` list, `classOfService`, `packetLengthBytes`). The
`classOfService` field is widened from the proto's `uint32` to a
Java `long` — the same widening convention `groupId`, `memberId`,
`multicastGroupId`, and `sessionId` itself already follow on
existing entity records. The `packetLengthBytes` field is mapped to
a plain Java `int` because the proto declares it as `int32`
(already signed), and encodes the device-side truncation behaviour:
a value of `0` means "do not truncate" (every clone carries the
original payload in full) and a positive value means "truncate
cloned payload to this many bytes" (the device drops any trailing
payload beyond the limit).

### Shared `Replica` and `BackupReplica` records

(commit `b893a53`, severity: opt-in addition)

`Replica` is the per-port fan-out slot shared by `MulticastGroupEntry`
and `CloneSessionEntry`. The record carries a nullable `Bytes port`,
the per-clone `instance` id, and an ordered immutable list of
`BackupReplica` fallback ports.

```java
import p4.v1.P4DataOuterClass.P4Data;   // unused in this snippet; just to show the import path stays the same

MulticastGroupEntry g = sw.readMulticastGroup().groupId(1L).one().orElseThrow();
for (Replica r : g.replicas()) {
    if (r.port() == null) {
        // The port_kind oneof was unset on the wire, OR the device used the deprecated
        // egress_port int32 variant. v1.5 treats both as null; consumers wanting the
        // deprecated int32 must parse the wire proto directly through the generated class.
        log.warn("replica with no port — possibly a v1.0-era device using deprecated egress_port");
    } else {
        log.info("replica port={} instance={} backups={}",
                bytesToHex(r.port()),
                r.instance(),
                r.backupReplicas().size());
    }
}
```

The `port` field is nullable in two cases that v1.5 treats
identically: when the P4Runtime `port_kind` oneof is unset, and
when the deprecated `egress_port` int32 field is set instead — the
same flat-nullable shape `WeightedMember.watchPort` uses for the
action-profile-group `watch_kind` oneof shipped in v1.4 (see the
[v1.3 → v1.4 guide](migration-1.3-to-1.4.md) for that family).
Controllers needing the deprecated `egress_port` int32 path can
parse the wire `Replica` proto directly through the generated
class; v1.5 does not surface it on this record.

`BackupReplica` is a record corresponding to a P4Runtime 1.5.0
spec addition — the first jp4 release that surfaces a v1.5-spec-level
type. The record is small (two fields: `Bytes port` and `int instance`),
matching the proto field by field. The `port` field is **non-null**
here (the `BackupReplica` proto has no oneof, just a plain `bytes
port = 1` always present). Devices running older spec versions
return empty `backup_replicas` lists, so existing controllers see
the addition as a non-breaking surface expansion.

### BMv2 end-to-end integration coverage

(commit `8032783`, severity: test infrastructure)

A new `PacketReplicationEngineIntegrationTest` class drives a real
`simple_switch_grpc` instance through wire-level Write + Read
round-trips for one multicast group and one clone session,
following the lifecycle `BMv2TestSupport.checkEnvironment()` +
`@BeforeAll` spawn + `@AfterAll` teardown that
`CountersMetersRegistersGroupsIntegrationTest` established in v1.4.

Local verification against `simple_switch_grpc` 1.15.1 found that
this BMv2 fully implements both `MulticastGroupEntry` and
`CloneSessionEntry` Read RPCs — the opposite of the `RegisterEntry`
case from v1.4 where the same BMv2 returns `UNIMPLEMENTED`. The two
new tests run live on standard BMv2 deployments, not skipped. A
defensive `Assumptions.assumeTrue(false, ...)` skip block on
`UNIMPLEMENTED` is retained anyway as forward-compatibility for
older BMv2 builds or alternate spec-compliant servers that might
refuse the read.

## Limitations

These are spec-conformant behaviours of the wire protocol or of
specific P4Runtime servers that adopters should know about.

- **P4Runtime 1.3+ canonical-bytestring encoding** strips leading
  zero bytes — a 9-bit port value of 5 sent as `{0x00, 0x05}`
  round-trips as a single-byte `{0x05}` on the read side. This
  affects every `Bytes` field on `Replica` and `BackupReplica`
  exactly as it affects `CounterEntry`, `ActionProfileMember`, etc.
  Consumers comparing read-back bytes to a known reference should
  canonicalise the reference (strip leading zero bytes) before
  comparing, or compare numerically by interpreting both as
  unsigned big-endian integers. The v1.4 guide documents the same
  point for action-profile reads.
- **Deprecated `egress_port` int32 path on `Replica`** is not
  surfaced as a typed field; it collapses into a null `port`. The
  identical pattern already applies to `WeightedMember.watchPort`
  for the action-profile-group `watch_kind` oneof in v1.4.
  Controllers that need to read the deprecated path can parse the
  wire `Replica` proto directly through the generated class.
- **Typed `P4Data` unwrapping for register cells** remains
  deferred. `RegisterEntry.data` still carries the serialised
  `p4.v1.P4Data` proto bytes; a typed register-value surface that
  pre-decodes the `bit<W>` / `int<W>` case is a future v1.x topic.
  The decode is a single `P4Data.parseFrom(...).getBitstring()`
  call away on the consumer side; the recipe is documented in the
  [v1.3 → v1.4 guide](migration-1.3-to-1.4.md) and on the
  `RegisterEntry` record's javadoc.

## v1.x roadmap

Capabilities not in v1.5 but tracked for future v1.x point releases
without committed dates. The canonical list lives in the
[CHANGELOG Roadmap section](../CHANGELOG.md#roadmap-v1x-candidates);
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
