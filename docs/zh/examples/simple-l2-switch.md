---
title: simple-l2-switch —— 控制器侧 L2 学习
description: simple-l2-switch 示例走读 —— 控制器侧 L2 学习交换机,注入合成帧、观察 PacketIn、在回调里写入转发条目、打印学到的 MAC 表。
keywords: [jp4, 示例, simple-l2-switch, L2 学习, PacketIn, 控制器, BMv2]
---

# `simple-l2-switch`

控制器侧 L2 学习交换机。`l2_forward` 表命中时数据面按目的 MAC 转发;miss 时 BMv2 把帧上送给控制器,控制器 *学* `srcAddr → ingress_port`,并通过 `PacketOut` 向其它前面板端口 *flood* 该帧。

**GitHub 源码**: [`examples/simple-l2-switch/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/)(Java + P4 + Gradle 构建)

## 示例展示了什么

- 以主控连接并推送流水线(`P4Switch.connectAsPrimary` + `bindPipeline`)。
- 注册 `PacketIn` 回调(`sw.onPacketIn`)、读取 `controller_packet_metadata`(`packet.metadataInt("ingress_port")`)。
- 在回调里写入表条目(`TableEntry.in("…").match(…).action(…).build()` + `sw.insert`)。
- 发送 `PacketOut`(`PacketOut.builder().payload(…).metadata("egress_port", …).build()` + `sw.send`)。
- try-with-resources 生命周期(`P4Switch implements AutoCloseable`)。

## 本地运行

完整前置与 `docker run` 行见 [示例的 README](https://github.com/zhh2001/jp4/blob/main/examples/simple-l2-switch/README.md);[快速开始](/zh/quickstart) 端到端给出相同命令。简版:

```bash
# After starting BMv2 in another terminal (see the README):
cd examples
./gradlew :simple-l2-switch:run
```

可选首参数覆盖: `--args="my-bmv2-host:50051"`。

## 期望输出

```
[L2] connected as primary on 127.0.0.1:50051, pipeline pushed
[L2] inject    src=aa:00:00:00:00:01 dst=ff:ff:ff:ff:ff:ff via simulated ingress 1
[L2] PacketIn  src=AA:00:00:00:00:01 dst=FF:FF:FF:FF:FF:FF ingress=1
[L2] LEARN     AA:00:00:00:00:01 → port 1 (entry installed)
[L2] inject    src=bb:00:00:00:00:02 dst=ff:ff:ff:ff:ff:ff via simulated ingress 2
[L2] PacketIn  src=BB:00:00:00:00:02 dst=FF:FF:FF:FF:FF:FF ingress=2
[L2] LEARN     BB:00:00:00:00:02 → port 2 (entry installed)
[L2] learned table: {AA:00:00:00:00:01=1, BB:00:00:00:00:02=2}
```

两行 `LEARN` 确认 jp4 已写入转发条目;后续发往这两个 MAC 的流量会在数据面短路通过(无控制器跳)。两个 demo 帧都以广播 MAC `FF:FF:FF:FF:FF:FF` 为目的,所以都会 miss `l2_forward` 并触发学习路径。

## 可以尝试

- 把 `sw.insert(e)` 换成 `sw.modify(e)`,观察设备对"键已存在 vs 不存在"的响应差异。
- 订阅 `sw.packetInStream()` 而非 `sw.onPacketIn(...)`,用 `Flow.Subscriber` 消费报文 —— 两种风格从同一条底层流扇出。[network-monitor 示例](/zh/examples/network-monitor) 端到端展示 `Flow.Subscriber` 形态。
- 运行中用 `sw.delete(e)` 删一条学过的条目,观察下一报文重新触发学习循环。

## 自流量说明

示例用 `sw.send(...)` 注入 demo 帧,这样在单机上无需 `mininet` / `tcpreplay` / 真接口就能看到学习循环。`simple_l2.p4` 程序把控制器传入的 `packet_out.egress_port` 当作 **该回环 demo 的模拟入口端口**;生产控制器的 PacketOut 用该字段的常规出口含义,真实入口流量来自网络。Java 控制器代码不变。

## 参见

- [L2 学习条目安装](/zh/cookbook/l2-learning) —— 从本示例抽取的 recipe。
- [报文 I/O](/zh/guides/packet-io) —— 三种 PacketIn 消费风格。
- [表](/zh/guides/tables) —— "学完即写" 回调使用的 `TableEntry` 构建器接口。
- [线程模型](/zh/concepts/threading-model) —— PacketIn 处理器内调用 `sw.insert` 为何不会死锁。
