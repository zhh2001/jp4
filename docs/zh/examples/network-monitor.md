---
title: network-monitor —— 主控 + 从属观察模式
description: network-monitor 示例走读 —— 两层被动监控模式,主控控制器推送流水线并注入合成流量,从属控制器演示 loadPipeline。包含 BMv2 PacketIn 仅投递给主控的注意点。
keywords: [jp4, 示例, network-monitor, 从属, Flow.Publisher, loadPipeline, 主控权, 可观测性]
---

# `network-monitor`

两层被动监控:主控控制器推送流水线并注入合成流量;独立的 **从属** 控制器(只读)演示 `loadPipeline()` 与 §6.4 "不持主控也能读"的契约。PacketIn 观察在主控上跑,因为 BMv2 只把 PacketIn 投递给主控。

**GitHub 源码**: [`examples/network-monitor/`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/)(Java + P4 + Gradle 构建)

## 示例展示了什么

- 以 **从属** 身份用低 election id 连接(`asSecondary()`,无主控特权)。
- 读侧的更宽松门控契约:从属能不持主控就发 `loadPipeline()` 和 `read`(P4Runtime 规范 §6.4)。
- `loadPipeline()` 作为 `bindPipeline(...)` 的读侧对应物 —— 获取设备当前安装的 P4Info 到本地交换机,让入站报文解析器拿到所需元数据 schema。
- 用 `Flow.Subscriber<PacketIn>` 消费 `Flow.Publisher`(对照 `simple-l2-switch` 里的 `onPacketIn(...)` 回调)。
- 多 switch 单 JVM 生命周期(同一设备的两个 `P4Switch` 实例,各自 try-with-resources)。

## 本地运行

```bash
cd examples
./gradlew :network-monitor:run
```

可选: `--args="my-bmv2-host:50051"` 覆盖设备地址。BMv2 启动行见 [示例的 README](https://github.com/zhh2001/jp4/blob/main/examples/network-monitor/README.md)。

## 期望输出

```
[MON] primary connected (election_id=10), pipeline pushed
[MON] secondary connected (election_id=1)
[MON] secondary mastership: Lost(prev=null, primary=10)
[MON] secondary loadPipeline() OK — spec §6.4 read-without-primary verified
[MON] injecting 30 synthetic frames at 30ms intervals…
[MON] observed 30 / 30 expected packets
[MON]   port 1: 8 packets, 304 bytes total, 38 avg
[MON]   port 2: 8 packets, 336 bytes total, 42 avg
[MON]   port 3: 7 packets, 314 bytes total, 44 avg
[MON]   port 4: 7 packets, 342 bytes total, 48 avg
[MON] stream completed
```

`secondary mastership: Lost(...)` 行 + `loadPipeline() OK` 行是证明点 —— 从属客户端(低 election id)在不持主控的情况下成功发了一个只读 RPC,验证 P4Runtime 规范 §6.4。逐端口计数由 demo 的端口轮转模式决定(`(seq % 4) + 1`);确切字节累计取决于循环产生的合成帧大小。

## 为什么观察在主控上跑

P4Runtime 规范 §16.1 规定 PacketIn **必须** 发给主控客户端,**应当** 发给备份。BMv2 只实现了 MUST —— 它把 PacketIn 投递给主控,从属什么也收不到。所以本示例里的观察一侧(收集统计的 `Flow.Subscriber`)挂在主控连接上。

本示例里从属的生命周期按设计很短:连接、主控权读为 `Lost`、发一个只读 RPC(`loadPipeline()`)成功、关闭。这就是规范 §6.4 的演示 —— "客户端无论是否主控都能发 Read RPC"。

广播 PacketIn 给备份的目标(部分 Tofino / Stratum 构建)上,同样的 `Flow.Publisher` 订阅者代码原样挂到从属即可。Java 代码不知道也不在乎自己跑在哪边。

## 可以尝试

- 把 `monitor.loadPipeline()` 注释掉重跑。PacketIn 会到达但在分发时被丢(见 SLF4J 警告 "no pipeline bound; dropping PacketIn") —— 见 [故障排查: no pipeline bound](/zh/troubleshooting/no-pipeline-bound)。
- 中途取消订阅: 在 `onSubscribe` 里捕获 `Subscription`、收到 10 个报文后调 `cancel()`,主控继续注入但订阅者停止接收。
- 同时开两个从属连接(两个交换机上两个订阅者),确认 BMv2 给两边都广播 PacketIn(确实如此 —— 但只在主控连接上)。

## 关于"双 switch"模式

生产中,主控和从属通常住在不同进程或主机里。本示例把两者塞进一个 JVM 只是为了 demo 自包含 —— 从属的 try-with-resources 块就是你针对广播 PacketIn 的目标会部署的独立观察者进程。

## 参见

- [从从属控制器观察 PacketIn](/zh/cookbook/packet-in-secondary) —— 从本示例抽取的 recipe。
- [连接与仲裁](/zh/guides/connection-and-arbitration) —— 主控 vs 从属、election id、§6.4 "不持主控也能读"契约。
- [报文 I/O](/zh/guides/packet-io) —— `packetInStream()` 机制与 BMv2 §16.1 注意点。
- [故障排查: PacketIn 处理器从未触发](/zh/troubleshooting/packet-in-not-firing) —— 把 §16.1 差距作为症状优先指南。
