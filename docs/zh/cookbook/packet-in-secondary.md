---
title: 从从属控制器观察 PacketIn
description: 被动监控控制器的 recipe —— 以低 election id 连接为从属,调用 loadPipeline 填充本地 schema,然后通过 Flow.Publisher 订阅 packetInStream。包含 BMv2 PacketIn 投递的实现细节。
keywords: [jp4, cookbook, 从属, 监控, Flow.Publisher, loadPipeline, 主控权, 可观测性]
---

# 从从属控制器观察 PacketIn

**我想要做:** 跑一个只读监控程序,在不占用主控权的前提下订阅 PacketIn,而主控控制器继续管理设备的转发状态。

## 模式

<!-- illustrative: trimmed adaptation of examples/network-monitor/src/main/java/io/github/zhh2001/jp4/examples/monitor/NetworkMonitor.java#secondaryObserver, edited for didactic clarity -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.ElectionId;
import io.github.zhh2001.jp4.entity.PacketIn;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

try (P4Switch monitor = P4Switch.connect("127.0.0.1:50051")
        .electionId(ElectionId.of(1))   // low id — primary holds something higher
        .asSecondary()) {

    // Secondaries can't bindPipeline (it's a write), but loadPipeline is a
    // read-only RPC that fetches the device's currently-installed P4Info
    // into the local switch. PacketIn parsing requires this schema.
    monitor.loadPipeline();

    AtomicInteger counter = new AtomicInteger();

    monitor.packetInStream().subscribe(new Flow.Subscriber<PacketIn>() {
        @Override public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }
        @Override public void onNext(PacketIn p) {
            int port = p.metadataInt("ingress_port");
            int bytes = p.payload().toByteArray().length;
            System.out.printf("[#%d] port=%d bytes=%d%n",
                    counter.incrementAndGet(), port, bytes);
        }
        @Override public void onError(Throwable t) { /* log + reconnect logic */ }
        @Override public void onComplete()         { /* device closed stream */ }
    });

    // ... block until shutdown signal ...
}
```

*实际使用: [`network-monitor`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/).*

## 走读

1. **用 `asSecondary()` 加低 election id 连接。** id 必须低于现有主控的。如果设备回复 `Acquired`(你成了主控),检查运维侧的 election id 协调 —— `asSecondary()` 是请求,不是保证。
2. **`loadPipeline()` 是 `bindPipeline` 的读侧对应物。** 它发出 `GetForwardingPipelineConfig`、填充本地 `P4Info`、然后返回。没有它,`packetInStream()` 抛 `P4PipelineException("no pipeline bound; ...")`,因为入站解析没有元数据 schema 可以解码。
3. **`packetInStream()` 返回 `Flow.Publisher<PacketIn>`**(JDK 9+,无额外依赖)。每个订阅者看到每个报文。要做背压,请求 `Long.MAX_VALUE` 以下的数;要接入响应式栈,用 `JdkFlowAdapter.flowPublisherToFlux(...)`(Reactor)或 `FlowAdapters.toPublisher(...)`(Reactive Streams)适配。
4. **`onNext` 跑在 jp4 回调执行器。** 与 `onPacketIn` 同样的 FIFO 契约 —— 慢消费者拖慢后续分发,但永远不阻塞 gRPC 入站线程。

## 重点: BMv2 PacketIn 投递的实现细节

P4Runtime 规范 §16.1 规定 PacketIn **必须** 投递给主控客户端,**应当** 投递给从属。BMv2 只实现了 MUST。BMv2 上的从属连接会成功注册并加载流水线,但 **不会** 通过 `packetInStream()` 收到 PacketIn —— 设备只把它们扇出给主控。

广播给从属的目标设备(部分 Tofino / Stratum 构建)上,同样的订阅者代码原样挂上去即可。Java 代码与目标无关。

如果你在 BMv2 上测试这个 recipe 并发现收不到报文,这是规范 MUST/SHOULD 的差距,不是 jp4 的 bug —— 见 [故障排查: PacketIn 处理器从未触发](/zh/troubleshooting/packet-in-not-firing)。

## 参见

- [连接与仲裁](/zh/guides/connection-and-arbitration) —— 主控 vs 从属、election id、主控权语义。
- [报文 I/O](/zh/guides/packet-io) —— `packetInStream()` 机制与三种消费风格。
- [线程模型](/zh/concepts/threading-model) —— `Flow.Subscriber.onNext` 跑在哪个执行器。
- [`network-monitor` 示例](/zh/examples/network-monitor) —— 本 recipe 提取自该示例。
