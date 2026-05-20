---
title: L2 学习条目安装
description: 控制器侧 L2 学习交换机的 recipe —— 从 PacketIn 读取源 MAC,通过 TableEntry.in().match().action().build() + sw.insert 安装转发条目,后续数据面自然命中、无需再走控制器。
keywords: [jp4, cookbook, L2 学习, PacketIn, TableEntry, 回调, 控制器侧学习]
---

# L2 学习条目安装

**我想要做:** 把"miss 时 flood"的数据面变成学习型交换机 —— 由控制器在收到 PacketIn 时写入转发条目。

## 模式

<!-- illustrative: trimmed adaptation of examples/simple-l2-switch/src/main/java/io/github/zhh2001/jp4/examples/l2switch/SimpleL2Switch.java#learnHandler, edited for didactic clarity -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.types.Mac;

import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    Map<Mac, Integer> learned = new ConcurrentHashMap<>();

    sw.onPacketIn(packet -> {
        int ingressPort = packet.metadataInt("ingress_port");
        byte[] frame = packet.payload().toByteArray();
        if (frame.length < 12) return;            // too short to carry src/dst MAC

        byte[] srcBytes = java.util.Arrays.copyOfRange(frame, 6, 12);
        Mac src = Mac.fromBytes(srcBytes);

        if (learned.putIfAbsent(src, ingressPort) != null) return;   // already known

        sw.insert(TableEntry.in("MyIngress.l2_forward")
                .match("hdr.ethernet.dstAddr", new Match.Exact(srcBytes))
                .action("MyIngress.forward").param("port", ingressPort)
                .build());
    });

    // ... drive traffic and observe learning ...
}
```

*实际使用: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

## 走读

1. **以主控身份连接 + 绑定流水线。** 没绑定流水线就没法解析 PacketIn 元数据,所以处理器无法工作。本 recipe 中控制器是唯一客户端;`connectAsPrimary` 是快捷形式。
2. **用 `sw.onPacketIn` 注册 PacketIn 处理器。** 处理器跑在 jp4 单线程回调执行器上 —— 慢处理器拖慢后续分发,但永远不阻塞 gRPC 入站线程。
3. **从帧中读取源 MAC。** L2 源是以太网负载的第 6-11 字节。用 `Mac.fromBytes` 让比较键有类型化的 equals/hashCode。
4. **`learned.putIfAbsent` 幂等守卫。** 同一个源 MAC 的多个 PacketIn 可能竞争;`putIfAbsent` 确保每个源只 insert 一次。
5. **安装转发条目。** `TableEntry.in(name).match(field, MatchKind).action(name).param(name, value).build()` 是流式链;`sw.insert(entry)` 阻塞调用,键已存在时抛 `P4OperationException` 带 `ALREADY_EXISTS` —— 守卫避免这种情形,但若数据面状态与控制器 `learned` map 不同步,捕获异常后要么继续,要么改用 `sw.modify`。

## 为什么生效

数据面(`MyIngress.l2_forward`)以 `hdr.ethernet.dstAddr` 为精确匹配键查表;命中则转发,miss 则把帧打给控制器。控制器为 `srcAddr` 安装条目后,后续发往该 MAC 的帧在数据面短路通过,不再走控制器。

## 参见

- [报文 I/O](/zh/guides/packet-io) —— 三种 PacketIn 消费风格(回调 / Flow.Publisher / poll)以及 PacketOut。
- [表](/zh/guides/tables) —— 完整 `TableEntry` 构建器和五种匹配类型。
- [线程模型](/zh/concepts/threading-model) —— 为什么 PacketIn 处理器内调用 `sw.insert` 不会死锁。
- [`simple-l2-switch` 示例](/zh/examples/simple-l2-switch) —— 本 recipe 提取自该示例。
