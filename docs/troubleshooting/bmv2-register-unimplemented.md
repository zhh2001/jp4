---
title: BMv2 returns UNIMPLEMENTED on register read
description: A P4OperationException with errorCode UNIMPLEMENTED and the message "Register reads are not supported yet" when calling sw.readRegister — the BMv2 1.15.1 server implementation, not a jp4 bug.
keywords: [jp4, troubleshooting, BMv2, register, UNIMPLEMENTED, simple_switch_grpc, RegisterEntry]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# BMv2 returns `UNIMPLEMENTED` on register read

## Symptom

A call to `sw.readRegister("...")` (or `readRegisterAsync`) throws:

```
io.github.zhh2001.jp4.exceptions.P4OperationException:
    UNIMPLEMENTED: Register reads are not supported yet
    (operationType=READ)
```

Counter, meter, table, action-profile, multicast, and clone reads against the same device work normally. Only register reads hit this.

## Reason

The P4Runtime specification allows a server to refuse a Read RPC with the `UNIMPLEMENTED` gRPC status code if the entity type is not implemented. BMv2 `simple_switch_grpc` version `1.15.1` (the version pinned in the jp4 CI matrix and the version most BMv2 docker images currently ship with) does not implement `RegisterEntry` Read; it returns `UNIMPLEMENTED` for any `Read` request whose `entity` oneof selects `register_entry`.

This is a device-side limitation, not a jp4 bug. jp4's `readRegister` surface is exercised end-to-end by the unit test `P4SwitchReadRegisterTest` against an in-process gRPC fake, and is correct on both the request-build and response-parse paths.

## Fix

- **In integration tests against BMv2**: catch the `P4OperationException`, check for `errorCode() == UNIMPLEMENTED`, and skip the assertion through JUnit's `Assumptions.assumeTrue(false, ...)`. The pattern is used by jp4's own `CountersMetersRegistersGroupsIntegrationTest` — see the `Assumptions.assumeTrue` block in that file.
- **In production controllers**: catch `P4OperationException`, inspect `errorCode()`, and if it's `UNIMPLEMENTED` fall back to a different observability mechanism (a packet probe, a counter alongside the register write, etc.) for the specific BMv2 deployment.
- **For canonical-bytestring-aware controllers** using a non-BMv2 server: register reads work on any P4Runtime server that implements them (spec-compliant Tofino / Stratum builds).

## Background

The P4Runtime spec's tolerance of `UNIMPLEMENTED` for read RPCs reflects that not every device backend supports the same entity types. The jp4 surface is target-agnostic; the device-side runtime decides which entity reads it can answer.

The `Read` RPC is one of the few P4Runtime RPCs where `UNIMPLEMENTED` is a legitimate spec-conformant response, alongside `OK` (with rows) and `OK` (empty). It's not a transient error to retry — the device is telling you the entity type itself isn't supported.

## See also

- [Reading counters, meters, registers, and action-profile entries](/cookbook/entity-reads) — the recipe; carries this same caveat in its BMv2 note.
- [v1.3 → v1.4 migration guide](/migrations/migration-1.3-to-1.4) — the surface introduction, also notes this BMv2 limitation.
- [Error handling](/guides/error-handling) — how `P4OperationException` carries the gRPC `errorCode()`.
