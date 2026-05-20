---
title: Inspecting multicast group state on a device
description: Recipe for reading multicast groups from a P4Runtime device's packet replication engine — walk replicas + backup replicas, handle the nullable Replica.port case, and read the wire metadata payload. Plus a note on writing multicast groups today.
keywords: [jp4, cookbook, multicast group, packet replication engine, PRE, Replica, BackupReplica, readMulticastGroup]
---

# Inspecting multicast group state on a device

**I want to:** read the multicast groups configured on the device's packet replication engine — for diagnosis, monitoring, or auditing — walk their replica lists, observe backup replicas, and handle the deprecated-port edge case.

## The pattern

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.MulticastGroupEntry;
import io.github.zhh2001.jp4.entity.Replica;
import io.github.zhh2001.jp4.entity.BackupReplica;

import java.math.BigInteger;
import java.util.List;

try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    // Read every multicast group configured on the device.
    List<MulticastGroupEntry> groups = sw.readMulticastGroup().all();

    for (MulticastGroupEntry g : groups) {
        System.out.printf("group %d (replicas=%d, metadata=%d bytes)%n",
                g.multicastGroupId(),
                g.replicas().size(),
                g.metadata().toByteArray().length);

        for (Replica r : g.replicas()) {
            String portStr;
            if (r.port() == null) {
                // Either the port_kind oneof was unset on the wire, or the
                // deprecated egress_port int32 was set. v1.5 treats both
                // as null; parse the wire proto directly through the
                // generated class if the deprecated path must be read.
                portStr = "<unset or deprecated>";
            } else {
                portStr = String.valueOf(
                        new BigInteger(1, r.port().toByteArray()).intValueExact());
            }
            System.out.printf("  replica port=%s instance=%d backups=%d%n",
                    portStr, r.instance(), r.backupReplicas().size());

            for (BackupReplica b : r.backupReplicas()) {
                // BackupReplica.port is non-null by proto contract
                // (no oneof, plain bytes port = 1).
                int backupPort = new BigInteger(1, b.port().toByteArray()).intValueExact();
                System.out.printf("    backup port=%d instance=%d%n",
                        backupPort, b.instance());
            }
        }
    }

    // Read one specific group.
    sw.readMulticastGroup()
            .groupId(7L)
            .one()
            .ifPresent(g -> System.out.println("group 7: " + g.replicas().size() + " replicas"));

    // Client-side filter: only groups with at least one replica.
    List<MulticastGroupEntry> nonEmpty = sw.readMulticastGroup()
            .where(g -> !g.replicas().isEmpty())
            .all();
}
```

## Walkthrough

1. **`sw.readMulticastGroup()` returns a `MulticastGroupReadQuery`.** It takes no name argument — the packet replication engine is program-agnostic. Groups are addressed by controller-assigned numeric ids only.
2. **The five terminals are `all()` / `one()` / `stream()` / `allAsync()` / `oneAsync()`.** Same shape as the table read query and every v1.4 entity read.
3. **`groupId(long)` is the server-side filter.** It populates the wire `multicast_group_id` field on the Read RPC, so the device returns only the matching group (or zero rows).
4. **`where(Predicate<? super MulticastGroupEntry>)` filters on the client side.** Applied after fetch, useful when the discriminator isn't `multicast_group_id`.
5. **`r.port()` is nullable on `Replica`.** Two spec cases collapse to null: the `port_kind` oneof unset, and the deprecated `egress_port` int32 set instead. v1.5 treats both identically — controllers needing the deprecated path read the wire proto through the generated class.
6. **`b.port()` is non-null on `BackupReplica`.** The proto has no oneof, just a plain `bytes port = 1` always present.

## Writing today — what the typed API doesn't yet surface

jp4 v1.5 exposes the read side. To **create** or **modify** a multicast group on the device today, construct a raw P4Runtime `WriteRequest` through the generated protobuf classes (`p4.v1.P4RuntimeOuterClass.MulticastGroupEntry` and friends) and send it through the gRPC channel directly. The pattern looks roughly like:

<!-- illustrative: concept fragment -->

```java
import p4.v1.P4RuntimeOuterClass.WriteRequest;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry;
// ... build the proto types directly, then send via the underlying P4RuntimeBlockingStub ...
```

The full pattern lives in the project's integration test fixture: [`PacketReplicationEngineIntegrationTest.seedMulticastGroup`](https://github.com/zhh2001/jp4/blob/main/src/test/java/io/github/zhh2001/jp4/integration/PacketReplicationEngineIntegrationTest.java) shows the verbatim raw-proto write sequence used to set up device state for the v1.5 read tests.

This recipe covers the inspection workflow, which is what the typed jp4 API exposes today.

## See also

- [`port_kind` idiom](/concepts/port-kind-idiom) — why `Replica.port` is nullable but `BackupReplica.port` is not.
- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — how `readMulticastGroup` maps to the wire `Entity.packet_replication_engine_entry.multicast_group_entry`.
- [v1.4 → v1.5 migration guide](/migrations/migration-1.4-to-1.5) — the surface introduction.
- [Troubleshooting: Replica.port is null](/troubleshooting/replica-port-null) — symptom-first guide to the null-port case.
