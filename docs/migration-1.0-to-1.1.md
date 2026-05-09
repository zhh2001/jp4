<!-- doc-lint: skip-file (migration guide; code blocks are usage examples for the v1.0 -> v1.1 additive surface, not user-facing best-practice examples) -->

# Migration guide: jp4 v1.0 → v1.1

This guide documents the API surface changes between v1.0 and v1.1,
with usage examples for callers adopting the new methods. It is the
authoritative reference for the v1.0 → v1.1 transition; the
[CHANGELOG](../CHANGELOG.md) carries the same information in
release-note form.

v1.1 is a SemVer-minor addition over v1.0:

- **Public surface**: 0 method removals, 0 signature changes, 0
  behaviour changes.
- **What's new**: three method additions (one on `ReadQuery`, one on
  `Connector`, one constant on `Mac`) plus documentation polish.
- **Migration effort**: zero. v1.0 patterns continue to work
  unchanged; the new methods are entirely opt-in.

The guide reflects this profile by omitting "Behaviour changes" and
"Documentation changes" sections that would be empty.

Each entry below cites the commit hash where the change landed; use
`git show <hash>` for the full diff.

## Additions (opt-in)

These new methods are available on the v1.1 surface; v1.0 patterns
continue to work indefinitely.

### `ReadQuery.where(Predicate<? super TableEntry>)`

(commit `6277b18`, severity: opt-in addition)

Client-side filtering on a `Read` result. Each call appends a
predicate; entries that fail any predicate are excluded from the
terminal call. Filters are applied in the order added, after the
device's own server-side `match(...)` filtering has narrowed the
read.

```java
// v1.0 — still works on v1.1: read everything, filter on the caller.
List<TableEntry> primaryRoutes = sw.read("MyIngress.ipv4_lpm").all().stream()
        .filter(e -> e.action() != null
                && 1 == e.action().paramInt("port"))
        .toList();
```

```java
// v1.1 — equivalent, expressed inside the read builder; reads
// stream the entries through the predicate without materialising
// the full list first.
List<TableEntry> primaryRoutes = sw.read("MyIngress.ipv4_lpm")
        .where(e -> e.action() != null
                && 1 == e.action().paramInt("port"))
        .all();
```

`where(...)` composes with the existing `match(...)` filters: the
device narrows by match key, then `where(...)` further narrows on the
client. The `.stream()` terminal applies the predicate lazily via
`Stream.filter`.

The method is a `default` on the `ReadQuery` interface that throws
`UnsupportedOperationException`. External implementers of
`ReadQuery` (test doubles, custom wrappers) keep compiling; the
failure surfaces only on an actual `.where(...)` call against an
implementation that has not been updated. The built-in implementation
returned by `P4Switch.read(...)` overrides the default with a real
implementation.

### `Connector.preserveRoleOnReconnect(boolean)`

(commit `53e4e8e`, severity: opt-in addition)

Fail-fast on reconnect role downgrade for primary-mandatory
applications. When enabled and a reconnect yields a non-Primary role
— because another client arbitrated with a higher election id during
the disconnect window — the switch closes itself and stores the
resulting `P4ArbitrationLost` as the close cause; subsequent user
calls throw that cause via the existing writability and readability
gates instead of the generic `"switch is closed"`
`P4ConnectionException`. The exception carries the original election
id (`ourElectionId()`) and the device's new primary election id
(`currentPrimaryElectionId()`) so the application can retry with a
higher id.

```java
// v1.0 — still works on v1.1: a reconnect that lands on Secondary
// fires a Lost callback but leaves the switch open; writes fail
// one-by-one against the writability gate.
try (P4Switch sw = P4Switch.connect(addr)
        .electionId(myEid)
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(...))
        .asPrimary()) {
    sw.onMastershipChange(status -> {
        if (status.isLost()) {
            // application-side handling
        }
    });
    // ... uses sw ...
}
```

```java
// v1.1 — opt into fail-fast.
try (P4Switch sw = P4Switch.connect(addr)
        .electionId(myEid)
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(...))
        .preserveRoleOnReconnect(true)
        .asPrimary()) {
    // ... uses sw; the next call after a downgraded reconnect
    // throws P4ArbitrationLost with the relevant election ids ...
} catch (P4ArbitrationLost ex) {
    ElectionId higher = ElectionId.of(
            ex.currentPrimaryElectionId().low() + 1);
    // reconnect with the higher id, retry application work
}
```

Default is `false`; v1.0 callers see no change. The flag is a no-op
for connectors that started as `.asSecondary()`, since a
post-reconnect Primary is the user yielding intentionally rather
than a downgrade.

### `Mac.ZERO`

(commit `d1bf319`, severity: opt-in addition)

`public static final Mac` constant for the all-zero MAC address
(`00:00:00:00:00:00`). Useful as a sentinel for invalid or
default-initialised source addresses.

```java
// v1.0 — still works on v1.1: build the constant ad-hoc.
Mac empty = Mac.fromBytes(new byte[6]);
if (frameSrc.equals(empty)) {
    return;   // skip uninitialised frame
}
```

```java
// v1.1 — equivalent with no per-call construction.
if (frameSrc.equals(Mac.ZERO)) {
    return;   // skip uninitialised frame
}
```

## v1.x roadmap

Capabilities not in v1.1 but tracked for future v1.x point releases
without committed dates. The canonical list lives in the
[CHANGELOG Roadmap section](../CHANGELOG.md#roadmap-v1x-candidates);
this section is a cross-reference, kept in sync with that file.

- Multi-switch coordination (a `P4Controller` with deliberate
  fan-out / parallelism / error-aggregation semantics).
- Other entity-type reads — counters, meters, registers, action
  profiles, multicast groups, packet replication.
- `ReadQuery.fields(...)` for client-side projection. Design TBD;
  held for a future v1.x release.
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
  distinctive lines from each example's stdout; v1.x should diff the
  full captured output against each example's README "Expected
  output" block. Reliable byte-identical diff requires a
  `Connector`-level packet-ingestion control surface (so unrelated
  kernel traffic on the BMv2 ingress interface cannot leak into the
  example output via the unbounded fan-out path); design TBD, held
  for a future v1.x release.
