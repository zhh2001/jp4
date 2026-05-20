---
title: 连接与仲裁
description: jp4 如何打开通往 P4Runtime 设备的 gRPC 通道、仲裁主控身份、支持从属只读控制器、监听主控变更通知,以及在丢失后重新争取主控。包含自动重连策略与 BMv2 PacketIn 投递的实现细节。
keywords: [jp4, P4Runtime, 主控, 从属, 仲裁, election id, ReconnectPolicy, P4ArbitrationLost]
---

# 连接与仲裁

每个 jp4 程序的起点都一样:对 P4Runtime 设备打开一条 gRPC 通道,
然后争取主控身份。本指南说明这两步背后实际发生了什么,以及它们支持
的控制器模式。

## P4Runtime 主控权一分钟速览

P4Runtime 允许多个控制器同时连接到同一台设备,但任意时刻只有一个是
**主控**(primary),其余为**从属**(secondary)。在已连接客户端里,
谁持有最高的 `election_id`,谁就是主控 —— 也只有它可以执行写操作:
推送流水线、insert / modify / delete 表条目、发送 PacketOut。从属仍然可以
**读** —— 读表、观察 PacketIn。完整的主控权语义见 P4Runtime 规范 §6.4。

在 jp4 中,`P4Switch` 表示某个客户端到某台设备的一条连接。同一台设备
被两个客户端连接,就对应两个 `P4Switch` 实例;它们是否在同一个 JVM 或
同一个进程内并不影响。

## 主路径

99% 的场景都是一台设备配一个主控控制器:

<!-- illustrative -->

```java
try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    // ... operate ...
}
```

*实际使用: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

`connectAsPrimary(address)` 是 `connect(address).asPrimary()` 的快捷形式,
默认 deviceId = 0,自动生成 election id。若需要不同设置,用完整链式形式
显式指定:

<!-- illustrative -->

```java
try (P4Switch sw = P4Switch.connect("127.0.0.1:50051")
        .deviceId(7)
        .electionId(ElectionId.of(0xCAFE))
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(10), /*maxRetries*/ 5))
        .asPrimary()
        .bindPipeline(p4info, deviceConfig)) {

    // ...
}
```

*实际使用: [`network-monitor`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/).*

`asPrimary()` 会阻塞直到设备确认仲裁结果。如果设备拒绝授予主控(因为
另一个客户端已持有更高的 election id),它抛出 `P4ArbitrationLost` ——
`P4ConnectionException` 的子类,同时携带你提出的 election id 与当前
真正主控的 election id。

## 从属控制器

只读和可观察控制器以更低的 election id + `asSecondary()` 接入:

<!-- illustrative -->

```java
try (P4Switch monitor = P4Switch.connect("127.0.0.1:50051")
        .electionId(ElectionId.of(1))
        .asSecondary()) {

    // 从属不能 bindPipeline(那是写操作);它可以 loadPipeline ——
    // 一个只读 RPC,获取设备当前安装的 P4Info。
    monitor.loadPipeline();

    monitor.packetInStream().subscribe(observer);
    // ... 读表 / 观察 PacketIn ...
}
```

*实际使用: [`network-monitor`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/).*

两点实务提示:

- **要观察 PacketIn 的从属必须先调用 `loadPipeline()`。** 入站解析需要
  元数据 schema 才能解析 `controller_packet_metadata` 字段;没有绑定流水线
  的状态下,`onPacketIn` / `packetInStream` / `pollPacketIn` 抛出
  `P4PipelineException("no pipeline bound; …")`。这是有意的 fail-fast ——
  默默丢弃每个 PacketIn 是最糟糕的失败模式。
- **PacketIn 是否投递给从属,看具体目标设备的实现。** P4Runtime §16.1
  规定 PacketIn 必须(MUST)投递给主控,也应当(SHOULD)投递给从属。
  BMv2 只实现了 MUST —— 从属即使订阅了也收不到 BMv2 的 PacketIn。其它
  规范合规的目标设备可能会广播。仓库 `NOTES.md` 的 "BMv2 PacketIn delivery
  is primary-only" 一节有实证细节;`network-monitor` 示例解释了由此带来的
  设计取舍。

## 主控变更通知

注册监听器来对主控权迁移做出反应(例如另一个控制器抢占了你,或者你
之前丢失主控、现在重新拿回):

<!-- illustrative: concept fragment -->

```java
sw.onMastershipChange(status -> {
    switch (status) {
        case MastershipStatus.Acquired a -> log("now primary, election_id=" + a.ourElectionId());
        case MastershipStatus.Lost l ->     log("lost primary; current is " + l.currentPrimary());
    }
});
```

`onMastershipChange` 是单一可替换式(再次调用替换前一个监听器),回调
运行在 jp4 单线程回调执行器上 —— 与 `onPacketIn` 同样的 FIFO 契约。慢
监听器会拖慢后续回调,但永远不会阻塞 gRPC 入站线程。

## 丢失后重新争取主控 (HA 模式)

`asPrimary()` 是幂等的。如果一个交换机被更高的 election id 降级,而那个
持有者后来又离开了,再次调用 `sw.asPrimary()` 会带着原 election id 重发
`MasterArbitrationUpdate`,并等待设备的回应:

<!-- illustrative: concept fragment -->

```java
sw.onMastershipChange(status -> {
    if (status.isLost()) {
        reportToOps();
        sw.asPrimary();   // 尝试重夺;若仍被拒绝则抛出 P4ArbitrationLost
    }
});
```

这覆盖了常见的 HA 模式:热备控制器观察到活动控制器掉线,然后接管。

## 自动重连

`ReconnectPolicy.exponentialBackoff(...)` 在传输层错误时重建整条
`ManagedChannel` + StreamChannel + 仲裁。默认是
`ReconnectPolicy.noRetry()` —— 失败仍然会很明显(下一次操作抛
`P4ConnectionException`),因此显式重试是 opt-in 的。

<!-- illustrative: concept fragment -->

```java
P4Switch.connect(address)
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100),  // 初始延迟
                Duration.ofSeconds(10),  // 最大延迟
                5))                      // 放弃前最大重试次数
        .asPrimary();
```

自动重连后,新会话以同样的 election id 重发仲裁,所以原本是主控的客户
端会自动重夺主控权。若设备拒绝(在你断开期间有人变成了主控),下次操作
抛 `P4ConnectionException`;通过 `onMastershipChange` 观察的代码会看到
`Lost` 状态。

## 关闭

`P4Switch implements AutoCloseable`。`close()` 幂等,可在任意上下文(包括
回调内部)安全调用。它会取消正在进行的重连、向设备发送 `onCompleted`、
关闭内部 executor,并关掉 gRPC 通道。

<!-- illustrative: concept fragment -->

```java
P4Switch sw = P4Switch.connectAsPrimary(address);   // 打开
// ...
sw.close();   // 显式关闭 —— 或者直接用 try-with-resources
```

关闭后,任何操作都会抛 `P4ConnectionException("switch is closed")`。

## 参见

- [流水线](pipeline) —— `bindPipeline` 和 `loadPipeline` 各自实际推送 / 拉取
  了什么。
- [错误处理](error-handling) —— 当连接失败、仲裁被拒、设备拒绝写入时
  该怎么处理。
- [`examples/network-monitor/`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/)
  在同一个 JVM 内展示了完整的主控 + 从属模式。
