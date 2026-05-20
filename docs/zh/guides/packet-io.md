---
title: 报文 I/O
description: 三种 PacketIn 消费风格(回调、Flow.Publisher、阻塞 poll)、如何发送 PacketOut、controller_packet_metadata 的来回,以及线程模型 —— 单线程回调执行器、外出执行器,以及回调内调用 send 不会死锁的保证。
keywords: [jp4, P4Runtime, PacketIn, PacketOut, StreamChannel, Flow.Publisher, controller_packet_metadata, 线程模型, 回调执行器]
---

# 报文 I/O

P4Runtime 设备通过双向 StreamChannel 把报文送给控制器(PacketIn),并
接受控制器的报文(PacketOut)。jp4 暴露三种 PacketIn 消费风格 —— 按你的
程序并发模型挑一种 —— 以及一个同步或异步的 PacketOut 发送 API。

## 三种 PacketIn 风格

<!-- illustrative: concept fragment -->

```java
sw.onPacketIn(packet -> ...);                            // 1. 回调(常见情形)
sw.packetInStream().subscribe(subscriber);               // 2. Flow.Publisher(多订阅者、背压)
sw.pollPacketIn(Duration.ofSeconds(1)).ifPresent(...);   // 3. 阻塞拉取
```

三种都消费同一条内部报文流。每个 PacketIn 都会投递给 **当前活动的
`onPacketIn` 处理器、每个 `packetInStream` 订阅者、以及 `pollPacketIn`
deque**。多种风格混用是支持且良定义的;`onPacketIn` 处理器是单一的(再次
调用 `onPacketIn` 替换前一个),订阅者列表无上限。

一些定向提示:

- 在 `bindPipeline` 或 `loadPipeline` 之前调用三种之中任意一个,都会抛
  `P4PipelineException("no pipeline bound; …")`。这是有意为之的 fail-fast:
  PacketIn 元数据没 P4Info 解不出来,默默丢弃每个报文是最糟糕的失败模式。
- 回调运行在 jp4 单线程回调执行器上 —— 与 `onMastershipChange` 同样的
  FIFO 契约。慢处理器拖慢后续 PacketIn 分发,但永远不会阻塞 gRPC 入站
  线程。
- poll deque 容量为 1024;溢出时丢弃最旧的未读报文,并打一行 SLF4J
  `WARN` 日志。

## 风格 1 —— `onPacketIn(Consumer<PacketIn>)`

90% 的情形。注册一个处理器,jp4 逐报文调用它:

<!-- illustrative -->

```java
sw.onPacketIn(packet -> {
    int ingressPort = packet.metadataInt("ingress_port");
    byte[] frame = packet.payload().toByteArray();
    log.info("PacketIn  ingress=" + ingressPort + " bytes=" + frame.length);
    // ...
});
```

*实际使用: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

`packet.metadata(name)` 返回任何在 `packet_in` 头上声明的
`controller_packet_metadata` 字段的原始 `Bytes`。`metadataInt(name)` 是
便捷方法,对装得下 Java `int` 的字段(≤ 31 位)适用;字段缺失或宽度超出
会抛 `IllegalStateException`。

再次调用 `onPacketIn` 会替换前一个处理器。没有 `removePacketInHandler()`
方法 —— 如果要在不关闭交换机的情况下停止分发,传一个 `p -> {}` 即可。

## 风格 2 —— `packetInStream()`

返回一个 `Flow.Publisher<PacketIn>`(JDK 9+,无额外依赖)。每个订阅者都
看到每一个报文:

<!-- illustrative -->

```java
sw.packetInStream().subscribe(new Flow.Subscriber<>() {
    @Override public void onSubscribe(Flow.Subscription s) {
        s.request(Long.MAX_VALUE);
    }
    @Override public void onNext(PacketIn p) {
        // process p
    }
    @Override public void onError(Throwable t) { /* ... */ }
    @Override public void onComplete()         { /* device closed stream */ }
});
```

*实际使用: [`network-monitor`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/).*

`network-monitor` 的完整运行输出(来自一次真实运行,逐字摘录)展示了
主控注入 + Flow 订阅者计数的循环,加上一小段从属演示 —— 证明
`loadPipeline()` 在不持有主控权时也能用:

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

`Flow.Subscriber` 跑在 **主控** 的 `packetInStream` 上,因为 BMv2 只把
PacketIn 投递给主控客户端(规范 §16.1 规定 MUST 主控、SHOULD 从属;BMv2
只做了 MUST 那部分)。在广播给从属的目标设备上,同一个订阅者可以原样
挂在从属上。详见仓库 `NOTES.md` 中 "BMv2 PacketIn delivery is
primary-only" 一节。

当你想要背压(`request` 比 `Long.MAX_VALUE` 更小的数),或者你的应用本来
就是 reactive 的、想把 `Flow.Publisher` 接到你已有的 reactive 栈上(一行
适配),就用这种风格:

<!-- illustrative: concept fragment -->

```java
// Reactor:
Flux<PacketIn> flux = reactor.adapter.JdkFlowAdapter
        .flowPublisherToFlux(sw.packetInStream());

// RxJava 3 (经过 Reactive Streams 适配器):
Flowable<PacketIn> flow = Flowable.fromPublisher(
        org.reactivestreams.FlowAdapters.toPublisher(sw.packetInStream()));
```

不同订阅者间的 `subscribe()` 和 `cancel()` 互相独立 —— 取消一个订阅不
影响其它订阅、不影响已注册的 `onPacketIn` 处理器、也不影响 poll deque。
`sw.close()` 时每个订阅者的 `onComplete()` 都会触发。

## 风格 3 —— `pollPacketIn(Duration)`

阻塞调用线程直到报文到达或超时:

<!-- illustrative: concept fragment -->

```java
Optional<PacketIn> p = sw.pollPacketIn(Duration.ofSeconds(1));
p.ifPresent(this::process);
```

超时时返回 `Optional.empty()`。适合脚本化的单控制器程序 —— 想要过程式
的读循环、不想引入 executor 或 reactive 栈。

## 发送 PacketOut

同步发送:

<!-- illustrative -->

```java
sw.send(PacketOut.builder()
        .payload(rawBytes)
        .metadata("egress_port", 1)
        .build());
```

*实际使用: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

异步变体返回 `CompletableFuture<Void>`:

<!-- illustrative: concept fragment -->

```java
sw.sendAsync(PacketOut.builder()
        .payload(rawBytes)
        .metadata("egress_port", 1)
        .build());
```

`PacketOut` 由 `PacketOut.builder()` 构造。`payload(byte[])` / `payload(Bytes)`
设置设备最终发出的原始字节。`metadata(name, value)` 接受 `int` / `long` /
`Bytes` / `byte[]` / `Mac`;值会在序列化到线时,按 P4Info 中声明的位宽
规范化。负数 `int`/`long` 会被 `IllegalArgumentException` 拒绝。

`PacketOut` 不可变且可复用 —— 同一个实例多次发送是安全的。

`send` 需要主控身份(P4Runtime 规范 §6.1:PacketOut 是写侧操作)。从属
调用 `send` 会收到 `P4ConnectionException("not primary")`。相反,PacketIn
对从属是开放的。

## controller_packet_metadata 声明

`packet_in` 和 `packet_out` 头部在你的 P4 程序里声明,p4c 编出来后会以
`controller_packet_metadata` 条目的形式进入 P4Info:

```p4
@controller_header("packet_in")
header packet_in_h {
    bit<9> ingress_port;
    bit<7> _pad;
}

@controller_header("packet_out")
header packet_out_h {
    bit<9> egress_port;
    bit<7> _pad;
}
```

每个字段在 jp4 中都是按名可寻址的元数据槽位:通过
`PacketIn.metadata("ingress_port")` 读,通过
`PacketOut.builder().metadata("egress_port", 1)` 写。`_pad` 字段用来把打包
后的位流对齐到字节边界(9 + 7 = 16 位 = 2 字节);PacketOut 时 jp4 把
`_pad` 写零,PacketIn 时除非你显式读,否则会忽略。

`P4Info` 也以编程方式暴露这些声明:

<!-- illustrative: concept fragment -->

```java
List<PacketMetadataInfo> in  = p4info.packetInMetadata();
List<PacketMetadataInfo> out = p4info.packetOutMetadata();
```

## 并发规则

- `onPacketIn` 回调、`Flow.Subscriber.onNext`、以及 `onMastershipChange`
  监听器共享 **同一个** 回调执行器。它们永远不会并发执行。其中任何一个
  慢下来都会拖慢其它两个。
- 多线程同时调用 `sw.send(...)` 是安全的;外出执行器把报文串行化到
  StreamChannel。
- 一个 `PacketIn` 处理器内部调用 `sw.send(...)` **不会** 死锁 —— 处理器
  跑在回调执行器,send 等待外出执行器,两者是不同的线程。

## 参见

- [连接与仲裁](connection-and-arbitration) —— 主控与从属的区别,以及从属
  观察 PacketIn 之前必须 `loadPipeline()` 的前提条件。
- [`examples/simple-l2-switch/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/)
  端到端展示 `onPacketIn` 回调风格。
- [`examples/network-monitor/`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/)
  展示 `packetInStream()` 的 Flow.Publisher 风格。
