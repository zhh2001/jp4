---
title: port_kind oneof idiom
description: Why jp4 surfaces Replica.port and WeightedMember.watchPort as nullable Bytes — the two-state collapse of the P4Runtime port_kind / watch_kind oneof unset case and the deprecated egress_port / watch int32 case.
keywords: [jp4, P4Runtime, Replica, BackupReplica, WeightedMember, port_kind, watch_kind, oneof, nullable]
---

# `port_kind` oneof idiom

Two jp4 records — `Replica` and `WeightedMember` — carry a nullable
`Bytes port` (respectively, `Bytes watchPort`) field that maps to a
proto `oneof` with one defined typed variant plus an "unset" state and
a "deprecated int32" alternative. jp4 collapses both non-typed states
into a Java `null` and lets the controller distinguish-as-needed
through the wire proto. This page explains why.

## The proto shape

`Replica` (used by `MulticastGroupEntry` and `CloneSessionEntry`):

```proto
message Replica {
  // ... other fields ...
  oneof port_kind {
    bytes port = 4;       // typed, canonical-bytestring port id
  }
  // deprecated:
  int32 egress_port = 1;  // pre-1.4 spec encoding
  // ... other fields ...
}
```

`WeightedMember` (nested inside `ActionProfileGroup.Member`):

```proto
message Member {
  // ... other fields ...
  oneof watch_kind {
    bytes watch_port = 4;
  }
  int32 watch = 2;        // deprecated since 1.4
}
```

In both proto messages, the `oneof` has exactly one variant (the
typed `bytes` field). That's by design — a `oneof` with a single arm
gives the proto wire a "presence" bit on the field that a plain `bytes`
field would not have. A controller can distinguish "port is unset" from
"port is set to empty bytes" by checking which arm of the oneof is
selected.

## The three observable states

Combine the oneof's two states ({unset, port-set}) with the deprecated
field's two states ({unset, set}) and four observable cases drop out:

| oneof   | int32      | jp4 surface              | what it means                                        |
|---------|------------|--------------------------|------------------------------------------------------|
| set     | unset      | non-null `Bytes`         | typed port id, the standard 1.4+ case                |
| unset   | unset      | `null`                   | no port specified at all                             |
| unset   | set        | `null`                   | port encoded the deprecated way (pre-1.4 device)     |
| set     | set        | (spec-forbidden)         | a conforming device must not emit both               |

jp4 surfaces the second and third rows as the same `null` — both mean
"the typed port is not what describes this replica". A controller that
must distinguish the third case (parse the deprecated int32) reads the
wire `Replica` proto directly through the generated class.

## Why collapse into nullable

Two-state Java surfaces survive proto evolution better than
three-state. If a future P4Runtime spec adds a third typed `port_kind`
variant — say, a `MulticastGroupId` for nested fan-out — jp4 can lift
that variant into a new typed accessor without breaking the
`port`-is-`null` convention for existing callers: today's null still
means "the typed `port` is not set", and the new accessor surfaces the
new variant with its own typed shape.

A three-state enum exposing `{TYPED_PORT, UNSET, DEPRECATED_INT32}`
forces every caller to update their `switch` when the spec evolves; a
two-state `Bytes`-or-null pushes the rare deprecated-path case down to
the wire-proto fallback, which is exactly what the spec is doing
internally with the deprecation marker.

## The same idiom in two records

`Replica.port` (multicast / clone replica destination) and
`WeightedMember.watchPort` (action-profile group member liveness
probe) use this idiom for the same reason — both gained the typed
`bytes`-shaped variant in P4Runtime 1.4 and both had a pre-1.4 int32
field that the spec deprecated. jp4 surfaces them identically:

<!-- illustrative: concept fragment -->

```java
for (Replica r : group.replicas()) {
    if (r.port() == null) {
        // either oneof unset, or deprecated egress_port set.
        // both rare; consumer must parse the wire proto to
        // distinguish.
        continue;
    }
    int egressPort = new BigInteger(1, r.port().toByteArray()).intValueExact();
    // ...
}
```

<!-- illustrative: concept fragment -->

```java
for (WeightedMember wm : group.members()) {
    if (wm.watchPort() == null) {
        // typed watch_port unset (or deprecated watch field set).
        continue;
    }
    // ...
}
```

The handler shape is identical because the proto shape is identical.

## `BackupReplica` is different

`BackupReplica` (added in P4Runtime 1.5) carries a plain `bytes port`
field — no `oneof`, no deprecated int32 alternative. jp4 surfaces it as
**non-null** `Bytes port` because the proto shape gives no way for the
field to be unset on a 1.5+ device:

<!-- illustrative: concept fragment -->

```java
for (Replica r : multicastGroup.replicas()) {
    for (BackupReplica b : r.backupReplicas()) {
        // b.port() is never null — the BackupReplica proto requires it.
        int backupEgressPort = new BigInteger(1, b.port().toByteArray()).intValueExact();
    }
}
```

This is the only port-bearing record where jp4 surfaces a non-nullable
shape, and the only difference is the proto shape — `BackupReplica` is
the first spec-level type that landed *after* P4Runtime added the
deprecation mechanism for the int32 path, so it never carried one.

## See also

- [v1.4 → v1.5 migration guide](/migrations/migration-1.4-to-1.5) — the
  PRE read landing brought `Replica` / `BackupReplica` into the jp4
  surface for the first time.
- [Canonical bytestring](/concepts/canonical-bytestring) — once
  `port()` is non-null, the bytes themselves obey the leading-zero
  stripping rule.
- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — the
  entity-read families that produce these records.
