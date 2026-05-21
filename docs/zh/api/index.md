---
title: API 参考
description: jp4 v1.5 公开接口的分类导航 —— 客户端生命周期、流水线、实体记录、匹配类型、读查询、流消息、批量操作、异常、值类型 —— 每个类附一行简介并直链标准 Javadoc。
keywords: [jp4, API 参考, Javadoc, P4Switch, P4Runtime, Java, 公开接口, 类, 记录]
---

# API 参考

jp4 v1.5 完整公开接口,按类别组织。每个条目链接到该类的标准 Javadoc HTML 页面。Javadoc 本身位于 [/api/javadoc/](/api/javadoc/),由 `javadoc` 工具从源码生成 —— 是每个方法签名、返回类型、异常列表、`@since` 标记的权威来源。

> **说明**: 详细 API 文档采用标准 Javadoc 格式 (英文)。本页面提供分类导航和每个类的中文简介,便于按需查阅。

任务导向指引请见 [Cookbook](/zh/cookbook/l2-learning) 与 [指南](/zh/guides/getting-started)。跨主题深入背景请见 [深入概念](/zh/concepts/p4runtime-spec-mapping)。

## 客户端生命周期

| 类 | 作用 |
|---|---|
| [`P4Switch`](/api/javadoc/io/github/zhh2001/jp4/P4Switch.html) | 客户端到单个 P4Runtime 设备的连接。每个 jp4 程序的主入口类。 |
| [`Connector`](/api/javadoc/io/github/zhh2001/jp4/Connector.html) | 流式连接构建器:地址、deviceId、electionId、reconnectPolicy、主控或从属角色。 |
| [`ReconnectPolicy`](/api/javadoc/io/github/zhh2001/jp4/ReconnectPolicy.html) | 自动重连配置 —— `noRetry()`(默认)或 `exponentialBackoff(initial, max, retries)`。 |
| [`ElectionId`](/api/javadoc/io/github/zhh2001/jp4/types/ElectionId.html) | P4Runtime 无符号 128 位 election id (low + high 对)。`of(long)` 是只填 low 的快捷形式。 |
| [`MastershipStatus`](/api/javadoc/io/github/zhh2001/jp4/MastershipStatus.html) | sealed 父类,投递给 `onMastershipChange` 监听器的 `Acquired` / `Lost` 事件。 |
| [`MastershipStatus.Acquired`](/api/javadoc/io/github/zhh2001/jp4/MastershipStatus.Acquired.html) | "本客户端成为主控" record;携带 `ourElectionId`。 |
| [`MastershipStatus.Lost`](/api/javadoc/io/github/zhh2001/jp4/MastershipStatus.Lost.html) | "本客户端不是主控" record;携带 `previousElectionId`(可空)和 `currentPrimaryElectionId`(可空)。 |

## 流水线

| 类 | 作用 |
|---|---|
| [`Pipeline`](/api/javadoc/io/github/zhh2001/jp4/Pipeline.html) | 已绑定的流水线视图,暴露解析后的 `P4Info` 加设备配置描述符。 |
| [`P4Info`](/api/javadoc/io/github/zhh2001/jp4/pipeline/P4Info.html) | 对 `p4.config.v1.P4Info` 的 schema 包装 —— table、action、字段、实体、controller-metadata 的名称索引。 |
| [`DeviceConfig`](/api/javadoc/io/github/zhh2001/jp4/pipeline/DeviceConfig.html) | sealed 父类,涵盖两个设备配置变体。 |
| [`DeviceConfig.Bmv2`](/api/javadoc/io/github/zhh2001/jp4/pipeline/DeviceConfig.Bmv2.html) | BMv2 风味 JSON 设备配置;`fromFile(Path)` 和 `fromBytes(byte[])` 工厂方法。 |
| [`DeviceConfig.Raw`](/api/javadoc/io/github/zhh2001/jp4/pipeline/DeviceConfig.Raw.html) | 面向非 BMv2 目标的"只字节"逃生口。 |
| [`PipelineAction`](/api/javadoc/io/github/zhh2001/jp4/pipeline/PipelineAction.html) | `SetForwardingPipelineConfig` 的动词选择器(默认 `VERIFY_AND_COMMIT`)。 |

### Schema 访问器(`P4Info` 子类型)

| 类 | 作用 |
|---|---|
| [`TableInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/TableInfo.html) | 解析后的表元数据:id、匹配字段、动作集合、size。 |
| [`MatchFieldInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MatchFieldInfo.html) | 匹配字段元数据:名称、kind、位宽。 |
| [`MatchFieldInfo.Kind`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MatchFieldInfo.Kind.html) | 枚举:`EXACT` / `LPM` / `TERNARY` / `RANGE` / `OPTIONAL`。 |
| [`ActionInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/ActionInfo.html) | 解析后的动作元数据:id、参数列表。 |
| [`ActionParamInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/ActionParamInfo.html) | 动作参数元数据:名称、位宽。 |
| [`CounterInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/CounterInfo.html) / [`Unit`](/api/javadoc/io/github/zhh2001/jp4/pipeline/CounterInfo.Unit.html) | counter 声明及单位(`BYTES` / `PACKETS` / `BOTH` / `UNSPECIFIED`)。 |
| [`MeterInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MeterInfo.html) / [`Unit`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MeterInfo.Unit.html) | meter 声明及单位(`BYTES` / `PACKETS` / `UNSPECIFIED`)。 |
| [`RegisterInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/RegisterInfo.html) | register 声明:id、名称、size。 |
| [`ActionProfileInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/ActionProfileInfo.html) | action profile 声明,带引用该 profile 的表 id 集合。 |
| [`PacketMetadataInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/PacketMetadataInfo.html) | `controller_packet_metadata` 字段声明。 |

## 匹配类型

| 类 | 作用 |
|---|---|
| [`Match`](/api/javadoc/io/github/zhh2001/jp4/match/Match.html) | sealed 父类;静态 `lpm(cidr)` 和 `exact(value)` 快捷工厂。 |
| [`Match.Exact`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Exact.html) | 精确匹配键(`value`)。 |
| [`Match.Lpm`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Lpm.html) | 最长前缀匹配键(`value`、`prefixLen`)。 |
| [`Match.Ternary`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Ternary.html) | ternary 键(`value`、`mask`)。 |
| [`Match.Range`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Range.html) | range 键(`low`、`high`)。 |
| [`Match.Optional`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Optional.html) | optional 键(`value`;null 安全通配)。 |

## 实体记录

每种 P4Runtime 实体类型的有类型数据 record。

| 类 | 作用 |
|---|---|
| [`TableEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/TableEntry.html) | 表条目 record;`in(name)` 构建器入口。 |
| [`TableEntryBuilder`](/api/javadoc/io/github/zhh2001/jp4/entity/TableEntryBuilder.html) | `TableEntry` 流式构建器 —— `match`、`action`、`priority`、`idleTimeoutNs`。 |
| [`ActionInstance`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionInstance.html) | 带参数 map 的具名动作;从 `TableEntry.action()`、`ActionProfileMember.action()` 可达。 |
| [`ActionBuilder`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionBuilder.html) | `.action(name).param(name, value)` 链的流式构建器。 |
| [`CounterEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/CounterEntry.html) | 一个 counter cell —— 名称、index、packetCount、byteCount。 |
| [`CounterData`](/api/javadoc/io/github/zhh2001/jp4/entity/CounterData.html) | 逐色的 packet/byte counter(被 `MeterCounterData` 使用)。 |
| [`MeterEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/MeterEntry.html) | 一个 meter cell —— 名称、index、config、逐色计数数据。 |
| [`MeterConfig`](/api/javadoc/io/github/zhh2001/jp4/entity/MeterConfig.html) | meter 速率配置 —— cir、cburst、pir、pburst、eburst。 |
| [`MeterCounterData`](/api/javadoc/io/github/zhh2001/jp4/entity/MeterCounterData.html) | 三色累积计数(green、yellow、red)。 |
| [`RegisterEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/RegisterEntry.html) | 一个 register cell —— 名称、index、序列化后的 `P4Data` 字节。 |
| [`ActionProfileMember`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionProfileMember.html) | action profile 成员 —— profile 名、member id、action。 |
| [`ActionProfileGroup`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionProfileGroup.html) | action profile 组 —— profile 名、group id、最大 size、加权成员。 |
| [`WeightedMember`](/api/javadoc/io/github/zhh2001/jp4/entity/WeightedMember.html) | 组成员 —— id、weight、可空 watch port。 |
| [`MulticastGroupEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/MulticastGroupEntry.html) | 组播组 —— id、replicas、metadata。 |
| [`CloneSessionEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/CloneSessionEntry.html) | 克隆会话 —— id、replicas、classOfService、packetLengthBytes。 |
| [`Replica`](/api/javadoc/io/github/zhh2001/jp4/entity/Replica.html) | 每端口扇出 slot —— 可空 port、instance、backup replica 列表。 |
| [`BackupReplica`](/api/javadoc/io/github/zhh2001/jp4/entity/BackupReplica.html) | 备份 replica —— 非空 port、instance。 |

## 读查询

每个实体读族的有类型查询构建器。7 个共享同一形状:服务端过滤、`where(Predicate)` 客户端过滤、5 个终端方法(`all` / `one` / `stream` / `allAsync` / `oneAsync`)。

| 类 | 服务端过滤 |
|---|---|
| [`ReadQuery`](/api/javadoc/io/github/zhh2001/jp4/ReadQuery.html) | 表读:`match(field, MatchKind)` |
| [`CounterReadQuery`](/api/javadoc/io/github/zhh2001/jp4/CounterReadQuery.html) | `index(long)` |
| [`MeterReadQuery`](/api/javadoc/io/github/zhh2001/jp4/MeterReadQuery.html) | `index(long)` |
| [`RegisterReadQuery`](/api/javadoc/io/github/zhh2001/jp4/RegisterReadQuery.html) | `index(long)` |
| [`ActionProfileMemberReadQuery`](/api/javadoc/io/github/zhh2001/jp4/ActionProfileMemberReadQuery.html) | `memberId(long)` |
| [`ActionProfileGroupReadQuery`](/api/javadoc/io/github/zhh2001/jp4/ActionProfileGroupReadQuery.html) | `groupId(long)` |
| [`MulticastGroupReadQuery`](/api/javadoc/io/github/zhh2001/jp4/MulticastGroupReadQuery.html) | `groupId(long)` |
| [`CloneSessionReadQuery`](/api/javadoc/io/github/zhh2001/jp4/CloneSessionReadQuery.html) | `sessionId(long)` |

## 流消息

投递给 `onPacketIn` / `onDigest` / `onIdleTimeout` / `onPacketDropped` 监听器的记录,以及外出的 `PacketOut` 构建器。

| 类 | 作用 |
|---|---|
| [`PacketIn`](/api/javadoc/io/github/zhh2001/jp4/entity/PacketIn.html) | 入站报文 —— payload、controller-metadata 字段访问器(`metadata(name)`、`metadataInt(name)`、`metadataLong(name)`)。 |
| [`PacketOut`](/api/javadoc/io/github/zhh2001/jp4/entity/PacketOut.html) | 外出报文 record。 |
| [`PacketOut.Builder`](/api/javadoc/io/github/zhh2001/jp4/entity/PacketOut.Builder.html) | 流式构建器 —— `payload(...)`、`metadata(name, value)`。 |
| [`DigestEvent`](/api/javadoc/io/github/zhh2001/jp4/entity/DigestEvent.html) | 入站 `DigestList` —— 名称、list id、逐条 payload、时间戳、digest id。 |
| [`DigestConfig`](/api/javadoc/io/github/zhh2001/jp4/entity/DigestConfig.html) | 外出 `DigestEntry.Config` —— maxTimeout、maxListSize、ackTimeout。 |
| [`IdleTimeoutEvent`](/api/javadoc/io/github/zhh2001/jp4/entity/IdleTimeoutEvent.html) | 入站 `IdleTimeoutNotification` —— 老化的 `TableEntry` 列表、时间戳。 |
| [`DropEvent`](/api/javadoc/io/github/zhh2001/jp4/entity/DropEvent.html) | 内部丢弃事件 —— reason、时间戳、解析后的 PacketIn(如有)、消息。 |
| [`DropEvent.Reason`](/api/javadoc/io/github/zhh2001/jp4/entity/DropEvent.Reason.html) | 枚举:`SUBSCRIBER_LAG` / `QUEUE_FULL` / `FILTERED`。 |

## 批量操作

| 类 | 作用 |
|---|---|
| [`BatchBuilder`](/api/javadoc/io/github/zhh2001/jp4/BatchBuilder.html) | 多更新批量 —— `insert(e)` / `modify(e)` / `delete(e)` / `execute()`。 |
| [`WriteResult`](/api/javadoc/io/github/zhh2001/jp4/WriteResult.html) | `BatchBuilder.execute()` 结果 —— submitted 计数、逐条失败。 |
| [`UpdateFailure`](/api/javadoc/io/github/zhh2001/jp4/entity/UpdateFailure.html) | 每条被拒更新一条 —— batch 下标、错误码、消息。 |

## 异常

全部继承自 `P4RuntimeException`;层次结构对应故障发生的位置。

| 类 | 含义 |
|---|---|
| [`P4RuntimeException`](/api/javadoc/io/github/zhh2001/jp4/error/P4RuntimeException.html) | sealed 父类。 |
| [`P4ConnectionException`](/api/javadoc/io/github/zhh2001/jp4/error/P4ConnectionException.html) | 传输 / 主控权 / 已关闭交换机故障。 |
| [`P4ArbitrationLost`](/api/javadoc/io/github/zhh2001/jp4/error/P4ArbitrationLost.html) | 特化 —— 主控被拒(连接或重夺)。 |
| [`P4PipelineException`](/api/javadoc/io/github/zhh2001/jp4/error/P4PipelineException.html) | P4Info / 设备配置 / schema 问题。 |
| [`P4OperationException`](/api/javadoc/io/github/zhh2001/jp4/error/P4OperationException.html) | 设备端拒绝了 Write 或 Read RPC。 |
| [`ErrorCode`](/api/javadoc/io/github/zhh2001/jp4/error/ErrorCode.html) | 枚举,镜像 gRPC 规范错误码(`OK` / `INVALID_ARGUMENT` / `NOT_FOUND` / `ALREADY_EXISTS` / `UNIMPLEMENTED` / ...)。 |
| [`OperationType`](/api/javadoc/io/github/zhh2001/jp4/error/OperationType.html) | `P4OperationException` 上的枚举 —— `INSERT` / `MODIFY` / `DELETE` / `READ`。 |

## 值类型

API 中使用的小型原生包装。

| 类 | 作用 |
|---|---|
| [`Bytes`](/api/javadoc/io/github/zhh2001/jp4/types/Bytes.html) | canonical-bytestring 容器;`of(byte[])` 构造,`equals`/`hashCode` 遵从前导零约定。 |
| [`Mac`](/api/javadoc/io/github/zhh2001/jp4/types/Mac.html) | 48 位 MAC 地址;`fromBytes(byte[])` 和 `ZERO` 常量。 |
| [`Ip4`](/api/javadoc/io/github/zhh2001/jp4/types/Ip4.html) | IPv4 地址;`of(String)` 解析点分四段,`toBytes()` 返回 4 字节。 |
| [`Ip6`](/api/javadoc/io/github/zhh2001/jp4/types/Ip6.html) | IPv6 地址;`of(String)` 解析冒号十六进制,`toBytes()` 返回 16 字节。 |
| [`ElectionId`](/api/javadoc/io/github/zhh2001/jp4/types/ElectionId.html) | 无符号 128 位 id;见 *客户端生命周期*。 |

## 权威参考

上面所有链接指向标准 Javadoc,由 `javadoc` 工具按项目标准 tag 约定生成(`@since 1.0.0` / `@since 1.5.0` 等标记标明每个类或方法的引入版本;`@implNote` 标记非规范性实现细节)。Maven Central 的 jar 坐标见 [快速开始](/zh/quickstart)。
