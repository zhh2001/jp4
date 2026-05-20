---
title: P4ArbitrationLost thrown on connect or re-claim
description: Why P4Switch.asPrimary throws P4ArbitrationLost — another client holds a higher election id on the device. How to read the carried election ids and retry with a higher id.
keywords: [jp4, troubleshooting, P4ArbitrationLost, primary, secondary, election id, mastership, MasterArbitrationUpdate]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# `P4ArbitrationLost` thrown on connect or re-claim

## Symptom

A call to `P4Switch.connectAsPrimary(...)`, `P4Switch.connect(...).asPrimary()`, or `sw.asPrimary()` (re-claim after a `Lost` mastership event) throws:

```
io.github.zhh2001.jp4.exceptions.P4ArbitrationLost:
    primary denied; our election_id=ElectionId(low=10), current primary election_id=ElectionId(low=100)
```

The exception carries `ourElectionId()` and `currentPrimaryElectionId()` — both `ElectionId` values.

## Reason

P4Runtime allows multiple clients to connect to the same device simultaneously. The client holding the highest `election_id` is the primary; everyone else is secondary. When jp4 issues `MasterArbitrationUpdate` requesting primary with election id X, and the device already has a client with election id Y > X, the device replies denying primary. jp4 surfaces that denial as `P4ArbitrationLost`.

`P4ArbitrationLost` is a subclass of `P4ConnectionException`, so a catch-all `catch (P4ConnectionException)` still covers it — but the specialisation lets controllers handle the "we lost primary" case differently from generic connection failures.

## Fix

Three patterns, depending on what the controller should do when it loses arbitration:

**1. Retry with a higher election id:**

```java
try {
    sw.connectAsPrimary(address);
} catch (P4ArbitrationLost e) {
    ElectionId higher = ElectionId.of(e.currentPrimaryElectionId().low() + 1);
    sw.connect(address).electionId(higher).asPrimary();   // try again
}
```

**2. Fall back to secondary role** (read-only / observability use cases):

```java
try {
    P4Switch.connectAsPrimary(address);
} catch (P4ArbitrationLost e) {
    return P4Switch.connect(address)
            .electionId(ElectionId.of(1))      // any low id
            .asSecondary();
}
```

**3. Fail-fast with operator alert:** primary-mandatory controllers (e.g., HA pairs where being denied means a peer is already active) should propagate the exception, not retry — log `currentPrimaryElectionId()` for the operator and exit.

For the **re-claim case** (`sw.asPrimary()` after a `Lost` callback), the `preserveRoleOnReconnect(true)` setting on `Connector` is the recommended pattern for primary-mandatory applications — it closes the switch automatically on a downgrade so subsequent writes throw `P4ArbitrationLost` instead of silently succeeding as secondary.

## Background

Election ids are unsigned 128-bit (`Uint128` proto, `ElectionId.of(low, high)` in jp4). The "highest wins" rule is total over the 128-bit space; there's no tiebreaker. Operations teams that coordinate primary controllers across a fleet typically allocate election id ranges to each controller (e.g., timestamp-based, or sequence-based per process generation).

P4Runtime spec §6.4 covers the full mastership semantics. Devices MUST notify all connected clients when the primary set changes — secondaries observe their own `Lost` status when a new primary takes over, and the previous primary observes `Lost` when demoted.

## See also

- [Connection and arbitration](/guides/connection-and-arbitration) — full mastership lifecycle, HA re-claim pattern, `onMastershipChange` listener.
- [Error handling](/guides/error-handling) — `P4ArbitrationLost` as a subclass of `P4ConnectionException`.
- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — how `asPrimary` translates to `MasterArbitrationUpdate`.
