---
title: P4PipelineException — no pipeline bound
description: Why operations against a freshly connected P4Switch throw P4PipelineException("no pipeline bound; ...") — jp4 fail-fast for schema-dependent operations before bindPipeline or loadPipeline has populated P4Info.
keywords: [jp4, troubleshooting, P4PipelineException, no pipeline bound, bindPipeline, loadPipeline, P4Info]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# `P4PipelineException: no pipeline bound`

## Symptom

A call to a read, write, or PacketIn-related method on a freshly connected `P4Switch` throws:

```
io.github.zhh2001.jp4.exceptions.P4PipelineException:
    no pipeline bound; call bindPipeline() or loadPipeline() first
```

Methods that surface this include:
- `sw.insert(...)` / `modify(...)` / `delete(...)` / `read(...)` (table operations need the schema for name resolution)
- `sw.onPacketIn(...)` / `packetInStream()` / `pollPacketIn(...)` (PacketIn parsing needs the metadata schema)
- `sw.readCounter(...)` / `readMeter(...)` / `readRegister(...)` / `readActionProfileMember(...)` / `readActionProfileGroup(...)` / `readMulticastGroup()` / `readCloneSession()` (entity reads gate on the bound pipeline)
- `sw.onDigest(...)` / `onIdleTimeout(...)` (stream-message reverse parsing needs the schema)
- `sw.enableDigest(...)` (DigestEntry construction needs the schema)

## Reason

jp4's schema-dependent operations require a bound `P4Info` for name resolution and metadata parsing. There are exactly two ways to populate it:

- **`sw.bindPipeline(p4info, deviceConfig)`** — primary-only; pushes the pipeline to the device and binds the schema locally in one step.
- **`sw.loadPipeline()`** — read-only; fetches the device's currently-installed `P4Info` and binds it locally. Works for both primary and secondary clients.

Without one of these, the operation has no schema to translate names against (or to decode wire-side metadata ids back into names). jp4 surfaces this as `P4PipelineException` rather than silently failing — silent failures here would either send malformed RPCs or drop every PacketIn without any signal that something is wrong.

## Fix

For a controller that owns the pipeline:

```java
try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {
    // ... operations work ...
}
```

The fluent chain `.bindPipeline(...)` returns the `P4Switch` so it composes left-to-right with `connectAsPrimary`.

For a secondary / read-only controller:

```java
try (P4Switch monitor = P4Switch.connect("127.0.0.1:50051")
        .electionId(ElectionId.of(1))
        .asSecondary()) {
    monitor.loadPipeline();           // pulls P4Info from the device
    monitor.packetInStream().subscribe(...);   // now works
}
```

`loadPipeline()` is the read-side complement of `bindPipeline`; secondaries call it because they can't write but they can pull the device's schema.

## Background

The fail-fast posture is deliberate. For PacketIn specifically, the inbound parser has no way to map wire-side metadata field ids back to names without a `P4Info` — a controller that never called `bindPipeline` or `loadPipeline` would silently drop every PacketIn. The exception forces the controller to register the schema before opting into PacketIn dispatch.

For table operations, the misspelled-name UX (known-list error messages) also requires the schema — without `P4Info`, jp4 can't tell whether `MyIngress.ipv4_lpm` is a real table name or a typo.

## See also

- [Pipelines](/guides/pipeline) — `bindPipeline` vs `loadPipeline`, the P4Info name index, pipeline drift.
- [Getting started](/guides/getting-started) — the connect → bindPipeline → operate lifecycle pattern.
- [PacketIn observation from a secondary controller](/cookbook/packet-in-secondary) — the secondary recipe that shows the `loadPipeline()` step.
