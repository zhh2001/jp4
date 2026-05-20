---
title: 查看设备上的克隆会话
description: 从 P4Runtime 设备包复制引擎读取克隆会话的 recipe —— 解码 packetLengthBytes 的截断行为、classOfService、replica 列表。包含目前如何写入克隆会话的说明。
keywords: [jp4, cookbook, 克隆会话, 流量镜像, packetLengthBytes, 截断, readCloneSession]
---

# 查看设备上的克隆会话

**我想要做:** 读取设备包复制引擎上配置的克隆会话 —— 一般用于流量镜像或遥测 —— 解码每个会话是否截断克隆负载、检视逐会话的服务类别。

## 模式

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

## 走读

1. **`sw.readCloneSession()` 返回 `CloneSessionReadQuery`。** 与 `readMulticastGroup` 一样,不接 P4 name 参数 —— 克隆会话与 P4 程序无关,按控制器分配的 `sessionId` 寻址。
2. **`packetLengthBytes` 用一个 int 编码截断行为。**
   - `0` 表示"不截断" —— 每个克隆携带完整原始负载。
   - 正值表示"截断克隆负载到这么多字节" —— 设备丢弃此限制之外的尾部负载。
   - 字段映射到 Java `int`(不是 `long`),因为 proto 声明为 `int32`,且截断长度天然是有符号范围;负值由规范禁止。
3. **`classOfService` 从 proto 的 `uint32` 拓宽为 `long`** —— 与 `sessionId`、`groupId`、`memberId`、`multicastGroupId` 相同的拓宽约定。线上范围完整保留。
4. **`sessionId(long)` 是服务端过滤。** 填充线上 `session_id` 字段;设备只返回匹配的会话。
5. **`where(Predicate<? super CloneSessionEntry>)`** 是客户端过滤,fetch 后应用。

## 目前如何写入 —— typed API 尚未覆盖

jp4 v1.5 暴露读侧。今天要在设备上 **创建** 或 **修改** 克隆会话,需要通过生成的 protobuf 类构造原始 P4Runtime `WriteRequest`,然后直接通过 gRPC 通道发送:

<!-- illustrative: concept fragment -->

```java
import p4.v1.P4RuntimeOuterClass.WriteRequest;
import p4.v1.P4RuntimeOuterClass.Update;
import p4.v1.P4RuntimeOuterClass.Entity;
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry;
import p4.v1.P4RuntimeOuterClass.CloneSessionEntry;
// ... build the proto types directly, then send via the underlying P4RuntimeBlockingStub ...
```

完整模式在项目集成测试 fixture 里:[`PacketReplicationEngineIntegrationTest.seedCloneSession`](https://github.com/zhh2001/jp4/blob/main/src/test/java/io/github/zhh2001/jp4/integration/PacketReplicationEngineIntegrationTest.java) 展示了用来为 v1.5 读测试准备设备状态的逐字 raw-proto 写入序列。

本 recipe 覆盖的是 typed jp4 API 今天暴露的检视工作流。

## 参见

- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— `readCloneSession` 如何映射到线上 `Entity.packet_replication_engine_entry.clone_session_entry`。
- [`port_kind` 习惯](/zh/concepts/port-kind-idiom) —— `Replica.port` 的可空性同样适用于克隆会话的 replica。
- [v1.4 → v1.5 迁移指南](/migrations/migration-1.4-to-1.5) —— 接口引入。
- [查看设备上的组播组状态](/zh/cookbook/multicast-group) —— 同形态的姊妹 recipe,针对组播组实体。
