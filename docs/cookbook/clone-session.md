---
title: Inspecting clone sessions on a device
description: Recipe for reading clone sessions from a P4Runtime device's packet replication engine — decode packetLengthBytes truncation behavior, classOfService, and replica lists. Plus a note on writing clone sessions today.
keywords: [jp4, cookbook, clone session, traffic mirroring, packetLengthBytes, truncation, readCloneSession]
---

# Inspecting clone sessions on a device

**I want to:** read the clone sessions configured on the device's packet replication engine — typically used for traffic mirroring or telemetry — decode whether each session truncates the cloned payload, and inspect the per-session class of service.

## The pattern

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.CloneSessionEntry;
import io.github.zhh2001.jp4.entity.Replica;

import java.math.BigInteger;
import java.util.List;

try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    List<CloneSessionEntry> sessions = sw.readCloneSession().all();

    for (CloneSessionEntry s : sessions) {
        String truncation = s.packetLengthBytes() == 0
                ? "no truncation (full payload)"
                : s.packetLengthBytes() + " bytes (truncated)";

        System.out.printf("session %d: replicas=%d cos=%d truncation=%s%n",
                s.sessionId(),
                s.replicas().size(),
                s.classOfService(),
                truncation);

        for (Replica r : s.replicas()) {
            String portStr = r.port() == null
                    ? "<unset>"
                    : String.valueOf(new BigInteger(1, r.port().toByteArray()).intValueExact());
            System.out.printf("  replica port=%s instance=%d%n", portStr, r.instance());
        }
    }

    // Read one specific session.
    sw.readCloneSession()
            .sessionId(42L)
            .one()
            .ifPresent(s -> System.out.printf(
                    "session 42: cos=%d packetLen=%d%n",
                    s.classOfService(), s.packetLengthBytes()));

    // Client-side filter: only sessions that truncate.
    List<CloneSessionEntry> truncating = sw.readCloneSession()
            .where(s -> s.packetLengthBytes() > 0)
            .all();
}
```

## Walkthrough

1. **`sw.readCloneSession()` returns a `CloneSessionReadQuery`.** Like `readMulticastGroup`, no P4 name argument — clone sessions are program-agnostic, addressed by controller-assigned `sessionId`.
2. **`packetLengthBytes` encodes the truncation behavior in one int.**
   - `0` means "do not truncate" — every clone carries the original payload in full.
   - A positive value means "truncate cloned payload to this many bytes" — the device drops any trailing payload beyond the limit.
   - The field is mapped to a Java `int` (not `long`) because the proto declares it as `int32` and the truncation length is naturally signed-range; negative values are spec-disallowed.
3. **`classOfService` is widened from the proto's `uint32` to `long`** — the same widening convention as `sessionId`, `groupId`, `memberId`, and `multicastGroupId`. The wire range is fully preserved.
4. **`sessionId(long)` is the server-side filter.** Populates the wire `session_id` field; the device returns only the matching session.
5. **`where(Predicate<? super CloneSessionEntry>)`** is the client-side filter, applied after fetch.

## Writing today — what the typed API doesn't yet surface

jp4 v1.5 exposes the read side. To **create** or **modify** a clone session on the device today, construct a raw P4Runtime `WriteRequest` through the generated protobuf classes and send it through the gRPC channel directly:

<!-- illustrative: concept fragment -->

```java
import p4.v1.P4RuntimeOuterClass.WriteRequest;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry;
import p4.v1.P4RuntimeOuterClass.CloneSessionEntry;
// ... build the proto types directly, then send via the underlying P4RuntimeBlockingStub ...
```

The full pattern lives in the project's integration test fixture: [`PacketReplicationEngineIntegrationTest.seedCloneSession`](https://github.com/zhh2001/jp4/blob/main/src/test/java/io/github/zhh2001/jp4/integration/PacketReplicationEngineIntegrationTest.java) shows the verbatim raw-proto write sequence used to set up device state for the v1.5 read tests.

This recipe covers the inspection workflow, which is what the typed jp4 API exposes today.

## See also

- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — how `readCloneSession` maps to the wire `Entity.packet_replication_engine_entry.clone_session_entry`.
- [`port_kind` idiom](/concepts/port-kind-idiom) — `Replica.port` nullability applies to clone-session replicas just as it does to multicast-group replicas.
- [v1.4 → v1.5 migration guide](/migrations/migration-1.4-to-1.5) — the surface introduction.
- [Inspecting multicast group state](/cookbook/multicast-group) — sister recipe with the same shape against the multicast-group entity.
