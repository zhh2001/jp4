---
title: 线程模型
description: jp4 三条内部执行线 —— gRPC 入站线程、单线程回调执行器、单线程外出执行器 —— 各自跑什么、各自的 FIFO 和串行化契约、回调内调用 send 不会死锁的保证,以及多调用方线程并发的规则。
keywords: [jp4, P4Runtime, 线程模型, 回调执行器, 外出执行器, gRPC, 死锁, 并发]
---

# 线程模型

jp4 中每个 `P4Switch` 有三条内部执行线。其中两个是 jp4 自有的单线程
执行器;第三个是 grpc-java 拥有的 gRPC 入站线程。明白哪段代码跑在哪个
执行器,是写出"自然横向扩展"和"莫名死锁或丢事件"的控制器之间的分水岭。

本页解释这三个执行器、各自的 FIFO 契约、回调内调用 send 的死锁自由
保证,以及多个调用方线程并发的规则。

## 三个执行器

**gRPC 入站线程** —— grpc-java 所有。每个 `P4Switch` 都只有一条双向
StreamChannel;设备发回来的一切 —— `MasterArbitrationUpdate` 回复、
`PacketIn`、`DigestList`、`IdleTimeoutNotification` —— 都到达这条线。
jp4 不在入站线程上运行用户代码。入站处理器的全部工作就是解析 proto、
扇出到对应 sink(回调执行器的队列、`Flow.Publisher` 订阅者、或 poll
deque),然后立刻返回。慢回调永远不会阻塞入站线程。

**回调执行器** —— 单线程,jp4 所有。运行每一个用户提供的监听器:

- `sw.onPacketIn(Consumer<PacketIn>)`
- `sw.onMastershipChange(Consumer<MastershipStatus>)`
- `sw.onDigest(Consumer<DigestEvent>)`
- `sw.onIdleTimeout(Consumer<IdleTimeoutEvent>)`
- `sw.onPacketDropped(Consumer<DropEvent>)`
- `sw.packetInStream()` 订阅者的每一次 `Flow.Subscriber.onNext`

这些都共享同一个回调执行器。它们永远不会并发执行。慢监听器拖慢该交换机
上后续的监听器分发 —— 报文 2 等报文 1 的处理器返回,主控变更等当前报文
处理完,等等。契约是跨所有监听器类型的 FIFO:事件按入站线程扇出的顺序
被分发。

**外出执行器** —— 单线程,jp4 所有。运行每一个写侧操作:

- `sw.insert(entry)` / `modify` / `delete` 及其 `*Async` 变体
- `sw.batch().…​.execute()`
- `sw.bindPipeline(...)`
- `sw.enableDigest(name, config)`
- `sw.send(packet)` 和 `sendAsync`
- `sw.asPrimary()` / `asSecondary()` 重新仲裁调用

这些都通过 FIFO 顺序串行化到 StreamChannel 上。从 N 个用户线程并发
`sw.insert(...)` 是安全的,在线上产出确定的顺序;执行器的内部队列解决
任何对通道的竞争。

## 回调内调用 send 不会死锁的保证

回调里调用 `sw.send(...)`(学习交换机响应 PacketIn 的常见模式)**不会**
死锁,即便回调和 send 都触及 jp4 自有的执行器:

<!-- illustrative: concept fragment -->

```java
sw.onPacketIn(packet -> {
    // 跑在回调执行器上
    PacketOut response = buildResponse(packet);
    sw.send(response);   // 调度到外出执行器 —— 不会死锁
    // 返回;回调执行器继续处理下一个事件
});
```

`sw.send(...)` 把任务排到 **外出** 执行器,并阻塞调用线程直到该任务完成。
调用线程是 **回调** 执行器;外出执行器是另一个线程。它们从不共享队列。
send 完成、外出执行器返回,调用线程(回调执行器)恢复并结束处理器。

同样的模式适用于 PacketIn 处理器里调用 `sw.insert(...)`、主控变更监听器
里调用 `sw.modify(...)` —— 任何回调里的写侧调用都因为同样的理由免于死锁。

## 异步路径与完成线程

`*Async` 路径(`insertAsync`、`sendAsync`、`allAsync` 等)返回
`CompletableFuture<Void>` 或 `CompletableFuture<List<...>>`。future 在
外出执行器上完成 —— 那条发起 RPC 并处理设备响应的线程。不显式指定执行器
的续接(`thenRun`、`thenAccept`、`whenComplete`)运行在 **那个** 执行器上:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(entry)
        .thenRun(() -> log("inserted"));   // 跑在外出执行器上
```

做实际工作的续接(把条目写到数据库、计算派生值)应当显式跳出外出执行器,
否则会拖住下一个外出 RPC:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(entry)
        .thenRunAsync(() -> db.record(entry), userExecutor);
```

标准 `CompletableFuture` 规则适用 —— `thenApplyAsync`、`thenComposeAsync`、
`whenCompleteAsync` 都接受一个 executor 把续接路由过去。

## 哪些情形 **不** 被串行化

**同一交换机上的并发读取。** `sw.read("...").stream()` 在外出执行器上
启动,**消费** 在终端方法(`forEach`、`iterator()` 上的 for-loop 等)的
调用线程上。从同一交换机迭代多条流的多个消费者,在各自的线程上并发运行;
底层 gRPC 迭代器互相独立。外出执行器只在初始化时介入。

**独立交换机。** 两个 `P4Switch` 实例 —— 不论对同一台设备还是两台不同的
设备 —— 拥有完全独立的执行器。没有跨交换机的顺序保证;两个 switch 对
同一台设备写入是正常赛跑。

**从入站线程发出。** jp4 永远不会让入站线程做真正的工作,而且入站线程
也不会直接写设备(所有写都经过外出执行器)。模式纯粹是扇出:入站解析、
入队到回调执行器、立刻返回。

## 一个具体例子: PacketIn → 表 insert + send

L2 学习交换机是 PacketIn-到-写入工作流的样板:

<!-- illustrative: concept fragment -->

```java
sw.onPacketIn(packet -> {
    Mac src = Mac.fromBytes(extractSrc(packet));
    int  ingress = packet.metadataInt("ingress_port");

    sw.insert(TableEntry.in("MyIngress.mac_learn")
            .match("hdr.ethernet.srcAddr", new Match.Exact(src.toBytes()))
            .action("MyIngress.mac_seen").param("port", ingress)
            .build());
    // (无 send —— 学习条目落地后数据面自然转发)
});
```

各步在哪里执行:

1. BMv2 在 StreamChannel 上送出一个 PacketIn。grpc-java 入站线程收到。
2. 入站线程解析线上 proto,构造 `PacketIn` 值,排到回调执行器的队列。
3. 回调执行器从队列取出下一个事件(与之前的 digest / mastership / drop /
   更早的 packet 事件按 FIFO),调用 `onPacketIn`。
4. 处理器调用 `sw.insert(...)`。该调用把任务排到外出执行器,阻塞回调
   线程直到外出执行器完成 RPC。
5. 外出执行器执行 Write RPC,等待设备响应,完成。
6. 回调线程恢复,处理器返回,回调执行器从队列取出下一个事件。

处理器里慢一点的数据库调用拖慢第 6 步(也因此拖慢下一个 PacketIn 分发),
但永远不会拖慢第 2 步的入站线程。poll deque 有可配置容量(默认 1024),
溢出时丢弃最旧的未读报文,并打一行 WARN 日志 —— 这是应用跟不上时的背压
机制。

## 参见

- [报文 I/O](/zh/guides/packet-io) —— 三种 PacketIn 风格(回调 /
  Flow.Publisher / poll)汇入同一个回调执行器。
- [表](/zh/guides/tables) —— 多用户线程的并发 insert。
- [错误处理](/zh/guides/error-handling) —— 异步路径通过 future 反馈失败,
  从不在调用线程上抛出。
