---
title: LPM 路由表批量安装
description: 通过一次 P4Runtime Write RPC 安装多条 LPM 路由的 recipe —— 用 Match.lpm 构造 TableEntry、用 sw.batch().insert().execute() 批量发送、用 WriteResult 处理部分失败。
keywords: [jp4, cookbook, LPM, 批量, WriteResult, IPv4 路由, TableEntry, Match.lpm]
---

# LPM 路由表批量安装

**我想要做:** 用一次 P4Runtime Write RPC 安装一张 LPM 前缀表(例如 `10.0.x.0/24 → port N`),读回验证,然后处理某些更新被设备拒绝的情形。

## 模式

<!-- illustrative: trimmed adaptation of examples/simple-loadbalancer/src/main/java/io/github/zhh2001/jp4/examples/loadbalancer/SimpleLoadbalancer.java#installRoutes, edited for didactic clarity -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.batch.WriteResult;
import io.github.zhh2001.jp4.batch.UpdateFailure;

try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    WriteResult r = sw.batch()
            .insert(routeEntry("10.0.1.0/24", 1))
            .insert(routeEntry("10.0.2.0/24", 2))
            .insert(routeEntry("10.0.3.0/24", 3))
            .execute();

    System.out.printf("installed %d routes (allSucceeded=%s)%n",
            r.submitted(), r.allSucceeded());

    if (!r.allSucceeded()) {
        for (UpdateFailure f : r.failures()) {
            System.err.printf("update[%d] failed: %s %s%n",
                    f.index(), f.code(), f.message());
        }
    }

    // Read-back verification.
    for (TableEntry e : sw.read("MyIngress.backend_lookup").all()) {
        Match m = e.match("hdr.ipv4.dstAddr");
        int port = e.action().paramInt("port");
        System.out.printf("  %s → port %d%n", m, port);
    }
}

private static TableEntry routeEntry(String cidr, int port) {
    return TableEntry.in("MyIngress.backend_lookup")
            .match("hdr.ipv4.dstAddr", Match.lpm(cidr))
            .action("MyIngress.forward").param("port", port)
            .build();
}
```

*实际使用: [`simple-loadbalancer`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/).*

## 走读

1. **用辅助方法构造条目。** `routeEntry(cidr, port)` 把表名、匹配字段名、动作名都收纳在一处 —— 调用点写作 `routeEntry("10.0.1.0/24", 1)`。拼错的名字在 `sw.insert(...)` 时刻失败并带 known-list 错误,永远不到达设备。
2. **`Match.lpm(cidr)` 解析 CIDR。** 等价于 `new Match.Lpm(Ip4.of("10.0.1.0").toBytes(), 24)`,但 IPv4 路由场景下读起来更自然。
3. **`sw.batch().insert(...).insert(...).execute()` 把多条 `Update` 打包到一次 Write RPC。** 设备按顺序应用,但 P4Runtime **不** 要求原子批量 —— 失败不会回滚前面已成功的更新。
4. **`WriteResult` 携带逐条结果。** 当且仅当 `failures()` 为空,`allSucceeded()` 为真。每条 `UpdateFailure` 带原始 batch 下标、gRPC `ErrorCode`、设备消息 —— 足够日志、重试,或针对失败更新做修正。
5. **读回验证** —— `sw.read("table_name").all()` 返回设备报告的条目,顺序由设备决定。`Match` 的 `toString()` 把 LPM 渲染为 `lpm 10.0.1.0/24`,便于 grep。

## modify 与 delete

把一条路由迁到另一个端口:

<!-- illustrative: concept fragment -->

```java
sw.modify(routeEntry("10.0.2.0/24", 4));   // throws if key doesn't exist
```

移除一条路由:

<!-- illustrative: concept fragment -->

```java
sw.delete(routeEntry("10.0.2.0/24", 4));   // only match key matters for delete
```

对 `delete` 而言,动作部分在线上传输时静默忽略;任何值都可以。

## 参见

- [表](/zh/guides/tables) —— 完整 `Match` 构建器(LPM / ternary / range / optional)。
- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— `sw.batch().execute()` 如何翻译为单次 `Write` RPC + 多个 `Update`。
- [Canonical bytestring 编码](/zh/concepts/canonical-bytestring) —— 读回的 IP 地址字节数为何可能少于写入时。
- [`simple-loadbalancer` 示例](/zh/examples/simple-loadbalancer) —— 本 recipe 提取自该示例。
