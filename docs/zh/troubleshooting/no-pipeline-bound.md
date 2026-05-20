---
title: P4PipelineException —— no pipeline bound
description: 为何在 P4Switch 刚连接后立刻调用某些操作会抛 P4PipelineException("no pipeline bound; ...") —— jp4 在 bindPipeline 或 loadPipeline 填充 P4Info 之前对依赖 schema 的操作做 fail-fast。
keywords: [jp4, 故障排查, P4PipelineException, no pipeline bound, bindPipeline, loadPipeline, P4Info]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# `P4PipelineException: no pipeline bound`

## 症状

在刚连接的 `P4Switch` 上调用读、写、或 PacketIn 相关方法时抛出:

```
io.github.zhh2001.jp4.exceptions.P4PipelineException:
    no pipeline bound; call bindPipeline() or loadPipeline() first
```

会触发的方法包括:
- `sw.insert(...)` / `modify(...)` / `delete(...)` / `read(...)`(表操作需要 schema 做按名解析)
- `sw.onPacketIn(...)` / `packetInStream()` / `pollPacketIn(...)`(PacketIn 解析需要元数据 schema)
- `sw.readCounter(...)` / `readMeter(...)` / `readRegister(...)` / `readActionProfileMember(...)` / `readActionProfileGroup(...)` / `readMulticastGroup()` / `readCloneSession()`(实体读以绑定流水线为前提)
- `sw.onDigest(...)` / `onIdleTimeout(...)`(流消息反向解析需要 schema)
- `sw.enableDigest(...)`(DigestEntry 构造需要 schema)

## 原因

jp4 中所有 schema 相关的操作都要求绑定 `P4Info` 用于按名解析、以及把线上元数据 id 解码回名字。只有两种方式填充它:

- **`sw.bindPipeline(p4info, deviceConfig)`** —— 仅主控可用;一步推送流水线到设备并把 schema 绑定到本地。
- **`sw.loadPipeline()`** —— 只读;获取设备当前安装的 `P4Info` 并把它绑到本地。主控和从属客户端都可用。

没有这两步,操作就没有 schema 可以做名字翻译(也没法把线上元数据 id 解码回名字)。jp4 选择 fail-fast 浮现这种情形 —— 静默失败要么发出畸形 RPC,要么默默丢弃每个 PacketIn,两者都是最糟的失败模式。

## 处理

主控控制器拥有流水线的情形:

```java
try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {
    // ... operations work ...
}
```

流式链 `.bindPipeline(...)` 返回 `P4Switch`,所以与 `connectAsPrimary` 从左到右串联。

从属 / 只读控制器的情形:

```java
try (P4Switch monitor = P4Switch.connect("127.0.0.1:50051")
        .electionId(ElectionId.of(1))
        .asSecondary()) {
    monitor.loadPipeline();           // pulls P4Info from the device
    monitor.packetInStream().subscribe(...);   // now works
}
```

`loadPipeline()` 是 `bindPipeline` 的读侧对应物;从属调用它,因为不能写但可以拉取设备的 schema。

## 背景

fail-fast 姿态是有意为之。专门针对 PacketIn:没有 `P4Info`,入站解析就没法把线上元数据字段 id 映射回名字 —— 没调用过 `bindPipeline` 或 `loadPipeline` 的控制器会默默丢弃每个 PacketIn。异常迫使控制器在订阅 PacketIn 分发之前注册 schema。

对表操作,拼错名字的 UX(known-list 错误消息)也需要 schema —— 没有 `P4Info`,jp4 无法判断 `MyIngress.ipv4_lpm` 是真实表名还是手误。

## 参见

- [流水线](/zh/guides/pipeline) —— `bindPipeline` 与 `loadPipeline`、P4Info 名称索引、流水线漂移。
- [入门](/zh/guides/getting-started) —— connect → bindPipeline → operate 的生命周期。
- [从从属控制器观察 PacketIn](/zh/cookbook/packet-in-secondary) —— 展示 `loadPipeline()` 步骤的从属 recipe。
