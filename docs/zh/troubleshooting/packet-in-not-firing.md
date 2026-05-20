---
title: PacketIn 处理器从未触发
description: 为何 onPacketIn 回调或 Flow.Subscriber 从未收到报文 —— 最常见原因是 BMv2 上不是主控、流水线未绑定、或设备侧 P4 程序不 punt 流量给控制器。
keywords: [jp4, 故障排查, PacketIn, 回调, Flow.Subscriber, BMv2, 从属, 主控权, punt]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# PacketIn 处理器从未触发

## 症状

你注册了 `sw.onPacketIn(...)`、订阅了 `sw.packetInStream()`、或调用了 `sw.pollPacketIn(...)`,但没有报文到达。连接看起来正常 —— 没异常、没 SLF4J 警告、`onError`/`onComplete` 都不触发。处理器就是从不运行。

## 最常见原因(按频率排序)

### 1. 你在 BMv2 上是从属

P4Runtime 规范 §16.1 规定 PacketIn **必须** 发给主控客户端,**应当** 发给备份。BMv2 只实现了 MUST —— 它只把 PacketIn 投递给主控,从属什么也收不到。BMv2 上的从属连接会成功注册订阅,但永远不会看到一个报文。

**处理:** 要么成为主控(原有主控必须让出),要么在主控侧用复用器分发 PacketIn 观察。部分 Tofino / Stratum 构建会广播给从属 —— 那些目标上同样的订阅者代码原样挂上去即可。

### 2. 你没调用 `bindPipeline` / `loadPipeline`

PacketIn 解析需要 `P4Info` schema 来解码 `controller_packet_metadata` 字段。没有的话,注册调用抛 `P4PipelineException("no pipeline bound; ...")` —— 但如果你 **先于** 异常注册了处理器(或异常被吞掉的代码路径),已注册的处理器永远不会触发,因为入站解析永远不接受报文。

**处理:** 在任何 `onPacketIn` / `packetInStream` / `pollPacketIn` 调用之前,总是 `bindPipeline(...)`(主控)或 `loadPipeline()`(从属)。见 [故障排查: no pipeline bound](/zh/troubleshooting/no-pipeline-bound)。

### 3. 设备侧 P4 程序不 punt

PacketIn 是设备把帧打给控制器。P4 程序决定 punt 哪些帧 —— 通常通过 `clone_preserving_field_list` / `recirculate` / `digest` / 显式扇出到 `CPU_PORT`。没有 punt 路径的流水线无论控制器怎么连都不会产生 PacketIn。

**处理:** 检查 P4 源里的 punt 动作。对 BMv2 来说,`simple_switch_grpc --cpu-port 255` 要求 P4 程序转发到端口 255 才能 punt —— 验证程序确实这么做。jp4 的 `simple-l2-switch` 和 `network-monitor` 示例里,punt 路径是 `l2_forward` 的数据面 miss 动作(表 miss → 控制器)。

### 4. poll deque 溢出(静默丢弃)

如果你用 `sw.pollPacketIn(...)` 而 poll 循环跟不上,deque(默认容量 1024)在溢出时丢弃最旧的未读报文。每丢一个打一行 SLF4J `WARN`。如果 deque 满了 *且* 没人轮询,SLF4J 警告会堆积,但没异常浮现。

**处理:** 加快轮询节奏,或切换到不入队的 `onPacketIn`(回调)/ `packetInStream`(Flow.Publisher),或在能容忍丢失的负载下接受丢弃。

### 5. 设备关闭了流

如果 gRPC StreamChannel 终止且没配置重连(默认 `ReconnectPolicy.noRetry()`),订阅者的 `onComplete()` 触发一次后就再无动静。没有日志的 `onError` / `onComplete`,这很容易漏。

**处理:** 把 `onComplete()` 和 `onError(Throwable)` 回调打日志。要自动重连就配置 `ReconnectPolicy.exponentialBackoff(...)`。

## 诊断 checklist

```java
// 1. Confirm you're primary on BMv2.
sw.onMastershipChange(s -> log.info("mastership: {}", s));

// 2. Confirm pipeline is bound.
log.info("pipeline bound: {}", sw.boundP4Info() != null);

// 3. Log every state transition on the subscriber.
sw.packetInStream().subscribe(new Flow.Subscriber<>() {
    @Override public void onSubscribe(Flow.Subscription s) {
        log.info("subscribed");
        s.request(Long.MAX_VALUE);
    }
    @Override public void onNext(PacketIn p)         { log.info("packet"); }
    @Override public void onError(Throwable t)       { log.error("stream error", t); }
    @Override public void onComplete()               { log.info("stream completed"); }
});

// 4. Confirm device-side traffic. tcpdump on the BMv2 host's interfaces.
```

## 参见

- [报文 I/O](/zh/guides/packet-io) —— 三种消费风格、线程模型、BMv2 §16.1 注意点。
- [从从属控制器观察 PacketIn](/zh/cookbook/packet-in-secondary) —— 在上下文里解释 BMv2 §16.1 差距的 recipe。
- [连接与仲裁](/zh/guides/connection-and-arbitration) —— 主控 vs 从属、主控变更通知。
