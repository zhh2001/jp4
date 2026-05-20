---
title: Replica.port is null on a Read response
description: Why r.port() returns null on a Replica returned by readMulticastGroup or readCloneSession — the proto port_kind oneof was unset, or the deprecated egress_port int32 field was set instead.
keywords: [jp4, troubleshooting, Replica, port_kind, oneof, null, deprecated, egress_port]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# `Replica.port` is null on a Read response

## Symptom

Code that iterates the replicas of a multicast group or clone session hits a null `port`:

```java
for (Replica r : group.replicas()) {
    byte[] portBytes = r.port().toByteArray();   // NullPointerException
    // ...
}
```

Other replicas in the same group / session may have non-null ports. The null doesn't fire on `BackupReplica.port`, only on `Replica.port`.

## Reason

The proto `Replica` message has a `port_kind` oneof with one defined variant (`bytes port = 4`) plus a deprecated alternative (`int32 egress_port = 1`). Three spec-conformant device states can produce a null on the typed jp4 surface:

| `port_kind` oneof | deprecated `egress_port` | jp4 surface | What it means |
|---|---|---|---|
| set | unset | non-null `Bytes` | the standard 1.4+ case |
| unset | unset | **`null`** | no port specified at all (likely a misconfigured group) |
| unset | set | **`null`** | port encoded the deprecated way (pre-1.4 device) |

jp4 v1.5 collapses the second and third rows into the same `null` because the typed `port` is not what describes that replica in either case.

`BackupReplica.port` is non-null by proto contract — the `BackupReplica` proto message (added in P4Runtime 1.5) has no oneof, just a plain `bytes port = 1` always present.

## Fix

**1. Guard against null at the iteration site:**

```java
for (Replica r : group.replicas()) {
    if (r.port() == null) {
        log.warn("replica with no port (group={}) — possibly a v1.0-era device " +
                 "using deprecated egress_port", group.multicastGroupId());
        continue;
    }
    int egressPort = new BigInteger(1, r.port().toByteArray()).intValueExact();
    // ...
}
```

**2. If you must read the deprecated path**, parse the wire `Replica` proto directly through the generated class (`p4.v1.P4RuntimeOuterClass.Replica`) — jp4 v1.5 does not surface the `egress_port` int32 path through the typed record.

## Background

Two-state Java surfaces survive proto evolution better than three-state. A `null`-or-`Bytes` shape means a future P4Runtime spec adding a third typed `port_kind` variant can lift it into a new typed accessor without breaking the `port`-is-`null` convention for existing callers; today's null still means "the typed `port` is not set", and the new accessor surfaces the new variant separately.

A three-state enum exposing `{TYPED_PORT, UNSET, DEPRECATED_INT32}` would force every caller to update their `switch` when the spec evolves. The flat-nullable shape pushes the rare deprecated-path case down to the wire-proto fallback, which is what the spec is doing internally with the deprecation marker.

The same idiom applies to `WeightedMember.watchPort` (action-profile-group member liveness probe), which has the same `watch_kind` oneof shape.

## See also

- [`port_kind` idiom](/concepts/port-kind-idiom) — full reasoning, the analogous `WeightedMember.watchPort` case, why `BackupReplica.port` differs.
- [Inspecting multicast group state](/cookbook/multicast-group) — recipe that handles this null in the walk loop.
- [v1.4 → v1.5 migration guide](/migrations/migration-1.4-to-1.5) — the surface introduction.
