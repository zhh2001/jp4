---
title: 查看设备上的组播组状态
description: 从 P4Runtime 设备包复制引擎读取组播组的 recipe —— 遍历 replica 与 BackupReplica、处理 Replica.port 可空的情形、读取 wire metadata 负载。包含目前如何写入组播组的说明。
keywords: [jp4, cookbook, 组播组, 包复制引擎, PRE, Replica, BackupReplica, readMulticastGroup]
---

# 查看设备上的组播组状态

**我想要做:** 读取设备包复制引擎上配置的组播组 —— 用于诊断、监控、审计 —— 遍历它们的 replica 列表、观察备份 replica、处理已废弃端口的边界情形。

## 模式

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

## 走读

1. **`sw.readMulticastGroup()` 返回 `MulticastGroupReadQuery`。** 不接 name 参数 —— 包复制引擎与 P4 程序无关,组按控制器分配的数字 id 寻址。
2. **5 个终端方法 `all()` / `one()` / `stream()` / `allAsync()` / `oneAsync()`。** 与表读查询和 v1.4 每个实体读查询同样的形状。
3. **`groupId(long)` 是服务端过滤。** 填充线上 `multicast_group_id` 字段,设备只返回匹配的组(或零行)。
4. **`where(Predicate<? super MulticastGroupEntry>)` 是客户端过滤。** fetch 后应用,在区分维度不是 `multicast_group_id` 时有用。
5. **`r.port()` 在 `Replica` 上可空。** 两种规范情形折叠到 null:`port_kind` oneof 未设置;或已废弃 `egress_port` int32 已设置。v1.5 等同处理 —— 需要读已废弃路径时,通过生成的类解析线上 proto。
6. **`b.port()` 在 `BackupReplica` 上非空。** proto 没有 oneof,只有一个总是存在的 `bytes port = 1`。

## 目前如何写入 —— typed API 尚未覆盖

jp4 v1.5 暴露读侧。今天要在设备上 **创建** 或 **修改** 组播组,需要通过生成的 protobuf 类(`p4.v1.P4RuntimeOuterClass.MulticastGroupEntry` 等)构造原始 P4Runtime `WriteRequest`,然后直接通过 gRPC 通道发送。大致形态:

<!-- illustrative: concept fragment -->

```java
import p4.v1.P4RuntimeOuterClass.WriteRequest;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry;
// ... build the proto types directly, then send via the underlying P4RuntimeBlockingStub ...
```

完整模式在项目集成测试 fixture 里:[`PacketReplicationEngineIntegrationTest.seedMulticastGroup`](https://github.com/zhh2001/jp4/blob/main/src/test/java/io/github/zhh2001/jp4/integration/PacketReplicationEngineIntegrationTest.java) 展示了用来为 v1.5 读测试准备设备状态的逐字 raw-proto 写入序列。

本 recipe 覆盖的是 typed jp4 API 今天暴露的检视工作流。

## 参见

- [`port_kind` 习惯](/zh/concepts/port-kind-idiom) —— 为什么 `Replica.port` 可空但 `BackupReplica.port` 非空。
- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— `readMulticastGroup` 如何映射到线上 `Entity.packet_replication_engine_entry.multicast_group_entry`。
- [v1.4 → v1.5 迁移指南](/migrations/migration-1.4-to-1.5) —— 接口引入。
- [故障排查: Replica.port 为 null](/zh/troubleshooting/replica-port-null) —— 症状优先的 null-port 处理指南。
