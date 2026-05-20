---
title: P4Runtime 规范映射
description: jp4 Java 接口与 P4Runtime 1.5 protobuf 之间的交叉对照 —— 哪个 Java 类型包装哪条 proto 消息、哪个方法发起哪个 RPC、哪个监听器投递哪个 StreamMessageResponse oneof 分支。
keywords: [jp4, P4Runtime, 规范, proto, 映射, RPC, StreamMessageResponse, p4lang]
---

# P4Runtime 规范映射

jp4 是 P4Runtime gRPC 服务上的一层薄而符合 Java 语言习惯的包装。jp4 类型
的每一个公开方法,最终都会发起一个 P4Runtime RPC,或者投递一条 P4Runtime
流消息。本页是两个层面之间的交叉对照:jp4 类型或方法、对应的 P4Runtime
proto 消息或字段、以及把两边连起来的 RPC 或流消息分支。

下面的映射追随 P4Runtime 规范 v1.5 —— 即 jp4 仓库 `protos/` 目录中原样
引入的 `p4lang/p4runtime` 版本。

## 客户端生命周期

| jp4                                            | P4Runtime                                                       |
|---|---|
| `P4Switch.connect(addr)`                       | 通往设备 `P4Runtime` 服务的 gRPC `ManagedChannel`               |
| `.asPrimary()` / `.asSecondary()`              | `StreamMessageRequest.arbitration`(`MasterArbitrationUpdate`)   |
| `ElectionId.of(low, high)`                     | `MasterArbitrationUpdate.election_id`(`Uint128` proto)          |
| `sw.close()`                                   | StreamChannel `onCompleted`;`ManagedChannel.shutdown`           |

`P4Switch.asPrimary()` 阻塞直到设备的 `MasterArbitrationUpdate` 回复到达;
如果设备拒绝主控(另一个客户端持有更高的 election id),抛
`P4ArbitrationLost`,异常对象同时携带两边的 election id。

## 流水线操作

| jp4                                            | P4Runtime                                                       |
|---|---|
| `sw.bindPipeline(p4info, deviceConfig)`        | `SetForwardingPipelineConfig`,动作 `VERIFY_AND_COMMIT`           |
| `sw.loadPipeline()`                            | `GetForwardingPipelineConfig`(只读;从属可用)                  |
| `P4Info.fromFile(path)`                        | 解析 `p4.config.v1.P4Info`(二进制或文本 protobuf)              |
| `DeviceConfig.Bmv2.fromBytes(bytes)`           | 包装 `ForwardingPipelineConfig.p4_device_config`(BMv2 JSON)    |
| `DeviceConfig.Raw.fromBytes(bytes)`            | 包装 `ForwardingPipelineConfig.p4_device_config`(任意目标)    |

P4Info 名称索引在流水线绑定时一次解析建立;后续按名调用走内存索引,不
再重新解析。

## 表操作

| jp4                                              | P4Runtime                                                          |
|---|---|
| `sw.insert(entry)`                               | `Write`,`Update.type = INSERT`,`entity.table_entry = entry`        |
| `sw.modify(entry)`                               | `Write`,`Update.type = MODIFY`                                     |
| `sw.delete(entry)`                               | `Write`,`Update.type = DELETE`                                     |
| `sw.read("…").all()`                             | `Read`,一个 `Entity.table_entry` 过滤,收集行                       |
| `sw.read("…").stream()`                          | `Read`;条目随 gRPC 服务端流到达逐条投递                            |
| `sw.batch().insert(...).insert(...).execute()`   | 一次 `Write` 携带多个 `Update`                                     |
| `TableEntry.in(name)` 构建器                     | `p4.v1.TableEntry` proto(table_id、match 列表、动作、优先级 …)    |
| `Match.lpm(cidr)` / `Match.Exact(...)` / 等      | `FieldMatch` proto oneof(`exact` / `lpm` / `ternary` / `range` / `optional`) |

## 实体读取(按实体类型族)

| jp4                                                              | P4Runtime 实体                          |
|---|---|
| `sw.readCounter(name).all()`                                     | `Entity.counter_entry`                 |
| `sw.readMeter(name).all()`                                       | `Entity.meter_entry`                   |
| `sw.readRegister(name).all()`                                    | `Entity.register_entry`                |
| `sw.readActionProfileMember(name).all()`                         | `Entity.action_profile_member`         |
| `sw.readActionProfileGroup(name).all()`                          | `Entity.action_profile_group`          |
| `sw.readMulticastGroup().all()`                                  | `Entity.packet_replication_engine_entry.multicast_group_entry` |
| `sw.readCloneSession().all()`                                    | `Entity.packet_replication_engine_entry.clone_session_entry`   |

每个读查询都暴露同样的五个终端方法 —— `all()` / `one()` / `stream()` /
`allAsync()` / `oneAsync()` —— 外加一个非默认的 `where(Predicate)` 客户端
过滤。服务端过滤接收实体的自然键(name + index、member id、group id 等),
也是非默认。

## 流消息

P4Runtime StreamChannel 是一条双向 gRPC 流。设备到控制器方向携带
`StreamMessageResponse`,该消息是一个 `oneof update`,有四个分支。jp4 对
每个分支暴露一个监听器:

| jp4                                                       | StreamMessageResponse oneof 分支                  |
|---|---|
| `sw.onMastershipChange(Consumer<MastershipStatus>)`       | `arbitration`(`MasterArbitrationUpdate` 回复)    |
| `sw.onPacketIn(Consumer<PacketIn>)`                       | `packet`(`PacketIn` proto 带 payload + 元数据)   |
| `sw.packetInStream().subscribe(subscriber)`               | 同一个 `packet` 分支,多订阅者扇出                |
| `sw.pollPacketIn(timeout)`                                | 同一个 `packet` 分支,阻塞拉取                    |
| `sw.onDigest(Consumer<DigestEvent>)`                      | `digest`(一个或多个 entries 的 `DigestList`)     |
| `sw.onIdleTimeout(Consumer<IdleTimeoutEvent>)`            | `idle_timeout_notification`(老化掉的条目)        |
| `sw.onPacketDropped(Consumer<DropEvent>)`                 | (jp4 内部)分发路径检测到的 drop                  |

`onPacketDropped` 是唯一一个 **不** 直接对应 proto 分支的监听器 —— 它观察
jp4 分发内部检测到的丢弃(`SUBSCRIBER_LAG`、`QUEUE_FULL`、`FILTERED`)。
完整分发模型见 [报文 I/O](/zh/guides/packet-io)。

## 控制面启用界面

| jp4                                                                  | P4Runtime 实体        |
|---|---|
| `sw.enableDigest(name, DigestConfig)`                                | `DigestEntry` 写入    |
| `TableEntryBuilder.idleTimeoutNs(long)`                              | `TableEntry.idle_timeout_ns`(条目字段)             |
| `Connector.packetInFilter(Predicate<? super PacketIn>)`              | (jp4 内部)预扇出过滤器                            |

digest 触发是 opt-in:不调用 `enableDigest` 设备就不会发出 `DigestList`,
即使 `onDigest` 已注册。idle timeout 也是 opt-in,按条目通过构建器开启。
PacketIn 过滤完全在客户端侧 —— 在任何 sink 看到解析出的 PacketIn 之前
做拒绝,对那些"应用接收后立刻丢弃"的流量很有用。

## proto 源在哪里

`protos/p4runtime/`(原样取自 `p4lang/p4runtime`)是权威线协议格式。
Java 生成的类位于 `p4.v1` / `p4.config.v1` 包,与 proto 声明完全一致。
jp4 永远不修改这些源 —— 升到更新的 P4Runtime 版本是上游目录的整树
verbatim 替换。

## 参见

- [Canonical bytestring 编码](/zh/concepts/canonical-bytestring) ——
  P4Runtime 1.3+ 的编码规则,影响读侧的每一个 `Bytes` 字段。
- [port_kind 习惯](/zh/concepts/port-kind-idiom) —— jp4 如何暴露
  `Replica.port_kind` oneof 和 `WeightedMember.watch_kind` oneof。
- [线程模型](/zh/concepts/threading-model) —— 每个监听器跑在哪个执行器,
  哪个线程对哪种调用是安全的。
