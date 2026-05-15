<!-- doc-lint: skip-file (migration guide; code blocks are usage examples for the v1.1 -> v1.2 additive surface, not user-facing best-practice examples) -->

# Migration guide: jp4 v1.1 → v1.2

This guide documents the API surface changes between v1.1 and v1.2,
with usage examples for callers adopting the new methods. It is the
authoritative reference for the v1.1 → v1.2 transition; the
[CHANGELOG](../CHANGELOG.md) carries the same information in
release-note form.

v1.2 is a SemVer-minor addition over v1.1:

- **Public surface**: 0 method removals, 0 signature changes, 0
  behaviour changes.
- **What's new**: a packet-ingestion control surface — a typed
  drop event (`DropEvent` with a `Reason` enum), a listener
  (`P4Switch.onPacketDropped`) to observe drops the dispatch path
  detects, and a pre-fan-out filter
  (`Connector.packetInFilter`) to reject inbound packets before any
  sink (handler / `Flow.Publisher` subscriber / poll deque) sees
  them.
- **Migration effort**: zero. v1.1 patterns continue to work
  unchanged; the new methods are entirely opt-in.

The guide reflects this profile by omitting "Behaviour changes" and
"Documentation changes" sections that would be empty.

Each entry below cites the commit hash where the change landed; use
`git show <hash>` for the full diff.

## Additions (opt-in)

These new methods and types are available on the v1.2 surface; v1.1
patterns continue to work indefinitely.

### `DropEvent` record + `Reason` enum

(commit `4e88813`, severity: opt-in addition)

A typed value carrying the dispatch-site reason, wall-clock
timestamp, parsed `PacketIn`, and a free-form human-readable
message. The record is what the new `P4Switch.onPacketDropped`
listener delivers, but the type itself is useful independently —
applications that consume the listener can pass `DropEvent`
instances around, persist them, derive counters from them, etc.

```java
// v1.2 — pattern-match on the reason in a listener.
sw.onPacketDropped((DropEvent event) -> {
    switch (event.reason()) {
        case SUBSCRIBER_LAG -> metrics.recordSlowSubscriber();
        case QUEUE_FULL     -> metrics.recordQueueOverflow();
        case FILTERED       -> metrics.recordApplicationFilter();
    }
});
```

All four record components are non-null by construction: the
canonical constructor rejects null with `NullPointerException` for
each. The `Reason` enum has three values in v1.2; new values may be
added in a future v1.x release (a SemVer-safe addition for any
application that handles unknown values defensively).

### `P4Switch.onPacketDropped(Consumer<DropEvent>)`

(commit `8ca1a5b`, severity: opt-in addition)

Single replaceable listener for inbound PacketIn drops that the
dispatch path detects. Observes the two backpressure-class drop
sites: `SUBSCRIBER_LAG` (a `Flow.Publisher` subscriber offer drop)
and `QUEUE_FULL` (the poll-style deque at capacity); from v1.2 it
also observes `FILTERED` (a `Connector.packetInFilter` reject).

```java
// v1.2 — register a listener that captures drops for diagnostics.
List<DropEvent> drops = new CopyOnWriteArrayList<>();
sw.onPacketDropped(drops::add);

// ... drive traffic ...

drops.stream()
        .filter(e -> e.reason() == DropEvent.Reason.QUEUE_FULL)
        .forEach(e -> log.warn("queue overflow at {}: {}",
                e.timestamp(), e.message()));
```

The listener runs on the same single-threaded callback executor as
`onPacketIn` / `onMastershipChange`, so a slow listener holds up
subsequent listener dispatches but never the gRPC inbound thread.
The existing WARN log on each drop site is unchanged — log and
listener are independent surfaces (operator-grep vs
application-handle), and the log fires synchronously before the
listener is scheduled.

Registration does not require a bound pipeline; drops require a
parsed `PacketIn` which in turn requires the pipeline, so the
listener stays quiet until the pipeline binds. NPE on null per the
project null-rejection convention.

### `Connector.packetInFilter(Predicate<? super PacketIn>)`

(commit `c35eb5a`, severity: opt-in addition)

Pre-fan-out filter that runs in `P4Switch.dispatchPacketIn` after
`PacketProto.parseIn` and before the callback / Publisher / deque
fan-out. A packet for which the filter returns false is dropped —
no sink sees it — and a `FILTERED` `DropEvent` fires through the
`P4Switch.onPacketDropped` listener.

```java
// v1.2 — reject inbound packets with an all-zero source MAC at
// connect time. A learning switch in production would also reject
// these as invalid sources; the filter applies at the library
// dispatch site so the rejected packets never enter the unbounded
// fan-out path.
try (P4Switch sw = P4Switch.connect(addr)
        .packetInFilter(p -> {
            byte[] frame = p.payload().toByteArray();
            if (frame.length < 12) return false;
            byte[] src = java.util.Arrays.copyOfRange(frame, 6, 12);
            return !Mac.ZERO.equals(Mac.fromBytes(src));
        })
        .asPrimary()) {
    sw.onPacketDropped(event -> {
        if (event.reason() == DropEvent.Reason.FILTERED) {
            // observe filter rejections (e.g. for counters)
        }
    });
    // ... uses sw ...
}
```

A filter that throws a `RuntimeException` is treated as a drop
(safe default — the user couldn't decide, so the library must not
silently let the packet through): the dispatch path catches the
exception, logs at WARN, and fires a `FILTERED` `DropEvent` whose
`message` names the thrown exception's simple class name. The
listener-side application can distinguish normal-reject
(`"rejected by packetInFilter"`) from filter-threw
(`"packetInFilter threw <ClassName>"`) on the message component.

Default null = pass all; v1.0 / v1.1 callers see no behaviour
change. The `Predicate<? super PacketIn>` shape is contravariant
per the PECS convention shared with
`ReadQuery.where(Predicate<? super TableEntry>)` from 1.1.0;
callers can supply a `Predicate<Object>` or `Predicate<PacketIn>`
alike.

## v1.x roadmap

Capabilities not in v1.2 but tracked for future v1.x point releases
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
- Digest and IdleTimeout stream-message handlers (P4Runtime spec
  §7 / §11.4) — shipped in v1.3 alongside `DigestConfig`,
  `P4Switch.enableDigest`, `P4Switch.onDigest`,
  `P4Switch.onIdleTimeout`, and `TableEntry.idleTimeoutNs`; see
  [`migration-1.2-to-1.3.md`](migration-1.2-to-1.3.md).
- Examples-CI assertion strengthening — held; the 1.2.0 release
  added the `Connector`-level packet-ingestion control surface
  (`c35eb5a`) that filters noise before it reaches the
  application, but investigation during that release prep found
  the residual demo loss-rate flake on busy loopback hosts has its
  root cause upstream of jp4 (BMv2 outbound saturation under
  sustained loopback noise). The Connector-level surface is
  shipped and useful for any application running against an
  environment with real noise; this entry remains held only
  because the demos currently run on an interface where lo-noise
  dominates.
