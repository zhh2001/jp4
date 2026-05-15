<!-- doc-lint: skip-file (migration guide; code blocks are usage examples for the v1.2 -> v1.3 additive surface, not user-facing best-practice examples) -->

# Migration guide: jp4 v1.2 → v1.3

This guide documents the API surface changes between v1.2 and v1.3,
with usage examples for callers adopting the new methods. It is the
authoritative reference for the v1.2 → v1.3 transition; the
[CHANGELOG](../CHANGELOG.md) carries the same information in
release-note form.

v1.3 is a SemVer-minor addition over v1.2:

- **Public surface**: 0 method removals, 0 signature changes, 0
  behaviour changes.
- **What's new**: the stream-message dispatch family is now
  complete. Inbound `DigestList` and `IdleTimeoutNotification`
  messages — silently dropped in 1.0 through 1.2 — surface as
  typed events through `P4Switch.onDigest` and
  `P4Switch.onIdleTimeout`; the corresponding control-plane enable
  surface (`P4Switch.enableDigest` with a `DigestConfig`, and
  `TableEntry.idleTimeoutNs`) lets callers opt in to emission from
  the device side.
- **Migration effort**: zero. v1.2 patterns continue to work
  unchanged; the new methods are entirely opt-in.

The guide reflects this profile by omitting "Behaviour changes" and
"Documentation changes" sections that would be empty.

Each entry below cites the commit hash where the change landed; use
`git show <hash>` for the full diff.

## Additions (opt-in)

These new methods and types are available on the v1.3 surface; v1.2
patterns continue to work indefinitely.

### `DigestEvent` record

(commit `c5ed6b1`, severity: opt-in addition)

A typed value carrying the resolved digest name, the ack-protocol
`list_id`, an immutable list of raw per-entry payload bytes, a
wall-clock timestamp, and the numeric digest id. Delivered by the
new `P4Switch.onDigest(Consumer<DigestEvent>)` listener, but the
type itself is useful independently — applications that consume
the listener can pass `DigestEvent` instances around, persist them,
derive counters from them, and so on.

```java
// v1.3 — read the resolved name and the raw per-entry payloads.
sw.onDigest((DigestEvent event) -> {
    log.info("digest {} list_id={} entries={}",
            event.digestName(), event.listId(), event.data().size());
    for (Bytes payload : event.data()) {
        // Each Bytes is the serialised form of one p4.v1.P4Data message.
        // Decode through the protobuf class when typed values are needed:
        var p4data = p4.v1.P4DataOuterClass.P4Data.parseFrom(payload.toByteArray());
        // ... use p4data.getStruct() / getBitstring() / etc. ...
    }
});
```

All five record components are non-null by construction: the
canonical constructor rejects null with `NullPointerException` for
each reference component. The payload list is copied through
`List.copyOf` so post-construction mutation of the caller's list
does not affect the event.

### `P4Info.digestNameById(int)` and `digestIdByName(String)`

(commits `252990b`, `617f5bb`, severity: opt-in addition)

Paired forward and reverse index of digest extern declarations from
P4Info, populated in one parse-loop alongside the existing
`tableInfoById` / `actionInfoById` / `packetInFieldById` family.
Both return `null` when the name or id is unknown, matching the
lookup-fail-equals-null convention shared with the existing
reverse-id accessors.

```java
// v1.3 — resolve numeric digest_id from wire to P4 name.
String name = sw.p4info().digestNameById(390699902);   // "MyIngress.learn_digest" or null

// v1.3 — resolve P4 name to numeric id for control-plane writes.
Integer id = sw.p4info().digestIdByName("MyIngress.learn_digest");  // 390699902 or null
```

The control plane primarily uses these accessors internally
(`onDigest` to resolve the inbound `digest_id`, `enableDigest` to
build the outbound `DigestEntry`); they are also useful for
applications that want to log or persist digest metadata.

### `P4Switch.onDigest(Consumer<DigestEvent>)`

(commit `3d4e91e`, severity: opt-in addition)

Single replaceable listener for inbound `DigestList` stream messages
the device emits when a configured digest extern collects entries.
The dispatch path is ack-first: a `DigestListAck` is issued
unconditionally before any listener delivery — so the device's
spec-defined `ack_timeout_ns` suppression window is never entered
when the pipeline is unbound, when no listener is registered, or
when P4Info has no digest with the received id.

```java
// v1.3 — register a digest listener after pipeline bind.
sw.bindPipeline(p4info, deviceConfig);
sw.onDigest(event -> {
    metrics.recordDigest(event.digestName(), event.data().size());
});
```

Registration eagerly requires a bound pipeline; the dispatch path
resolves `digest_id` to a name through `P4Info.digestNameById` and
that resolution needs the bound P4Info. Registering pre-bind throws
`P4PipelineException`, catching the silent-drop trap that would
otherwise apply to every received digest. The callback runs on the
same single-threaded callback executor as
`onMastershipChange` / `onPacketIn` / `onPacketDropped`, so a slow
listener holds up subsequent dispatches but never the gRPC inbound
thread.

### `DigestConfig` record + `P4Switch.enableDigest(String, DigestConfig)` / `enableDigestAsync`

(commit `617f5bb`, refined in `85c8744`, severity: opt-in addition)

The control-plane enable surface for digest emission. Without this
call the device emits no `DigestList` even if `onDigest` is
registered; the pair is required for the dispatch family to surface
real traffic.

`DigestConfig` carries the three knobs the P4Runtime
`DigestEntry.Config` defines: `maxTimeout` (the outstanding-data
deadline), `maxListSize` (the size threshold), and `ackTimeout`
(how long the device waits before considering an ack lost). The two
time fields are `Duration` to match the project's existing
time-shaped API; the canonical constructor rejects null and
negative durations and a non-positive `maxListSize`.

```java
// v1.3 — bind pipeline, register listener, enable emission.
sw.bindPipeline(p4info, deviceConfig);
sw.onDigest(event -> { /* ... */ });
sw.enableDigest("MyIngress.learn_digest", new DigestConfig(
        Duration.ofMillis(100),   // max timeout — flush within 100 ms
        16,                       // max list size — flush every 16 entries
        Duration.ofSeconds(10))); // ack timeout — generous
```

`enableDigest` blocks until the device acknowledges the write;
`enableDigestAsync` returns a `CompletableFuture<Void>` that
completes with success or the underlying exception, paired the same
way as `insert` / `insertAsync`. The write is issued as an
`Update.Type.INSERT` of the `DigestEntry` — BMv2 1.15.1 requires
INSERT for first-time enable. Reconfiguring a previously installed
digest is a separate v1.x topic and not in the v1.3 surface.

### `IdleTimeoutEvent` record

(commit `c5ed6b1`, severity: opt-in addition)

A typed value carrying an immutable list of `TableEntry`s that
idled out and a wall-clock timestamp. The list preserves the wire
shape directly, so a single notification can span multiple tables
and the library does not regroup by `table_id`.

```java
// v1.3 — observe entries that aged out, grouped by table on the application side.
sw.onIdleTimeout((IdleTimeoutEvent event) -> {
    Map<String, List<TableEntry>> byTable = event.entries().stream()
            .collect(Collectors.groupingBy(TableEntry::tableName));
    byTable.forEach((tableName, entries) ->
            log.info("table {} idled {} entries at {}",
                    tableName, entries.size(), event.timestamp()));
});
```

Both record components are non-null by construction. The entries
list is copied through `List.copyOf`, but note that `TableEntry`
uses reference equality (no `equals`/`hashCode` override), so two
`IdleTimeoutEvent`s built from independently parsed wire bytes
compare unequal even when the underlying entries match.

### `P4Switch.onIdleTimeout(Consumer<IdleTimeoutEvent>)`

(commit `e7a54a9`, severity: opt-in addition)

Single replaceable listener for `IdleTimeoutNotification`. The
dispatch path reverse-parses each wire `TableEntry` through
`EntryProto.fromProto`, the same parser the Read RPC uses.

```java
// v1.3 — register an idle-timeout listener after pipeline bind.
sw.bindPipeline(p4info, deviceConfig);
sw.onIdleTimeout(event -> {
    for (TableEntry e : event.entries()) {
        log.info("entry idled out in {}: {}", e.tableName(), e.matches());
    }
});
```

Unlike `onDigest`, no outbound ack is sent:
`IdleTimeoutNotification` has no corresponding
`StreamMessageRequest` arm per the P4Runtime spec. An entry on an
action-profile or selector table is rejected by
`EntryProto.fromProto` today; the dispatch helper catches that
exception, WARN-logs the drop, and swallows the whole notification
rather than delivering a partial event — matching the per-message
fail-open posture `dispatchPacketIn` uses for unparseable inbound
packets. Registration requires a bound pipeline for the same reason
`onDigest` does: the reverse-parser needs P4Info.

### `TableEntry.idleTimeoutNs()` accessor + `TableEntryBuilder.idleTimeoutNs(long)` setter + `ActionBuilder.idleTimeoutNs(long)` passthrough

(commit `5d5bd28`, severity: opt-in addition)

The control-plane enable surface for idle expiration. Setting the
value to a positive number opts a table entry into idle-timeout
aging on devices whose P4 program declared the containing table
with idle-timeout support; the device fires an
`IdleTimeoutNotification` for the entry once it has not been hit
within the configured window.

```java
// v1.3 — install a mac-learn entry that ages out after 5 seconds of no hits.
sw.onIdleTimeout(event -> { /* ... */ });
sw.insert(TableEntry.in("MyIngress.mac_learn")
        .match("hdr.ethernet.srcAddr", new Match.Exact(Bytes.of(srcMacBytes)))
        .action("MyIngress.mac_seen")
        .idleTimeoutNs(Duration.ofSeconds(5).toNanos())
        .build());
// After ~5 seconds with no hits on that entry, onIdleTimeout fires.
```

The default `0` matches the v1.0 / v1.1 / v1.2 behaviour, and the
protobuf wire encoding then omits the field — `EntryProto.toProto`
writes it conditionally on `> 0`, mirroring the existing
`setPriority` convention. `EntryProto.fromProto` round-trips the
field back onto the builder so a Read response carries the value
forward. Negative values are rejected at the builder setter with
`IllegalArgumentException`. `ActionBuilder` carries a parallel
passthrough so chains like `.action(...).param(...).idleTimeoutNs(...)`
stay flat, mirroring the existing `priority(...)` passthrough.

The P4 program side must declare the table with idle-timeout
support; in v1model that is the `support_timeout = true` table
property (a property inside the table body, not an annotation),
which makes p4c emit `idle_timeout_behavior = NOTIFY_CONTROL_PLANE`
in the P4Info — the field BMv2 reads to decide whether to accept
`idle_timeout_ns > 0` on table-entry writes.

## v1.x roadmap

Capabilities not in v1.3 but tracked for future v1.x point releases
without committed dates. The canonical list lives in the
[CHANGELOG Roadmap section](../CHANGELOG.md#roadmap-v1x-candidates);
this section is a cross-reference, kept in sync with that file.

- Multi-switch coordination (a `P4Controller` with deliberate
  fan-out / parallelism / error-aggregation semantics).
- Other entity-type reads — multicast groups and packet
  replication. (v1.4 shipped counters, meters, registers, and
  action-profile members and groups alongside the v1.0 table
  reads; see
  [`migration-1.3-to-1.4.md`](migration-1.3-to-1.4.md).)
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
