---
title: port_kind oneof 习惯
description: 为什么 jp4 把 Replica.port 和 WeightedMember.watchPort 暴露为可空 Bytes —— P4Runtime port_kind / watch_kind oneof 未设置和已废弃的 egress_port / watch int32 这两种状态的折叠。
keywords: [jp4, P4Runtime, Replica, BackupReplica, WeightedMember, port_kind, watch_kind, oneof, 可空]
---

# `port_kind` oneof 习惯

两个 jp4 记录 —— `Replica` 和 `WeightedMember` —— 携带一个可空的
`Bytes port`(分别是 `Bytes watchPort`)字段,对应到一个有"一个有类型
变体 + 一个未设置状态 + 一个已废弃 int32 替代"的 proto `oneof`。jp4 把
两种非典型状态都折叠成 Java `null`,让控制器在需要时通过线上 proto 自行
区分。本页解释为什么这么做。

## proto 形状

`Replica`(被 `MulticastGroupEntry` 和 `CloneSessionEntry` 使用):

```proto
message Replica {
  // ... 其它字段 ...
  oneof port_kind {
    bytes port = 4;       // 有类型,canonical-bytestring 形式的端口 id
  }
  // 已废弃:
  int32 egress_port = 1;  // 1.4 之前规范的编码
  // ... 其它字段 ...
}
```

`WeightedMember`(嵌套在 `ActionProfileGroup.Member` 里):

```proto
message Member {
  // ... 其它字段 ...
  oneof watch_kind {
    bytes watch_port = 4;
  }
  int32 watch = 2;        // 1.4 起已废弃
}
```

两条 proto 消息里,`oneof` 都只有一个变体(那个 `bytes` 字段)。这是
设计使然 —— 只有一个分支的 `oneof` 给字段引入了"存在(presence)"位,
而朴素的 `bytes` 字段不具备。控制器可以通过检查 oneof 选择了哪个分支,
来区分"port 未设置"与"port 设置为空字节"。

## 三种可观察状态

把 oneof 的两种状态({未设置, port 已设置})与已废弃字段的两种状态
({未设置, 已设置})组合,可得到四种可观察情形:

| oneof   | int32      | jp4 暴露               | 含义                                              |
|---------|------------|------------------------|---------------------------------------------------|
| 已设置  | 未设置     | 非空 `Bytes`           | 有类型端口 id,1.4+ 的标准情形                    |
| 未设置  | 未设置     | `null`                 | 完全没有指定端口                                  |
| 未设置  | 已设置     | `null`                 | 端口以已废弃方式编码(1.4 之前的设备)            |
| 已设置  | 已设置     | (规范禁止)            | 合规设备不得同时发出两者                          |

jp4 把第二和第三行折成同一个 `null` —— 两者都表示"有类型 port 不是描述
该 replica 的字段"。需要区分第三种情形(解析已废弃 int32)的控制器可以
通过生成的类直接解析线上 `Replica` proto。

## 为什么折成可空

两态 Java 接口比三态在 proto 演化下幸存得更好。如果未来某个 P4Runtime
规范新增第三个有类型 `port_kind` 变体 —— 比如说一个用于嵌套扇出的
`MulticastGroupId` —— jp4 可以把这个新变体提升为一个新有类型访问器,
而不破坏现有 `port`-为-`null` 习惯:今天的 null 仍然意味着"有类型 `port`
未设置",而新访问器以自己的形状暴露新变体。

一个三态枚举 `{TYPED_PORT, UNSET, DEPRECATED_INT32}` 会强迫每个调用方在
规范演化时更新自己的 `switch`;两态 `Bytes`-或-null 把罕见的废弃路径
下推到线上 proto 后备方案,这恰好是规范在内部对废弃标记所做的事情。

## 两个记录,同一个习惯

`Replica.port`(组播 / 克隆 replica 目的端口)和
`WeightedMember.watchPort`(action-profile group 成员的存活探测端口)
出于同一原因采用此习惯 —— 两者都在 P4Runtime 1.4 中获得了 `bytes` 形
有类型变体,而两者都曾有一个 1.4 之前规范使用的 int32 字段,如今被
废弃。jp4 把它们暴露得完全一致:

<!-- illustrative: concept fragment -->

```java
for (Replica r : group.replicas()) {
    if (r.port() == null) {
        // 要么 oneof 未设置,要么已废弃 egress_port 被设置。
        // 两种都罕见;消费者若要区分,得解析线上 proto。
        continue;
    }
    int egressPort = new BigInteger(1, r.port().toByteArray()).intValueExact();
    // ...
}
```

<!-- illustrative: concept fragment -->

```java
for (WeightedMember wm : group.members()) {
    if (wm.watchPort() == null) {
        // 有类型 watch_port 未设置(或已废弃 watch 字段被设置)。
        continue;
    }
    // ...
}
```

处理形状一致,因为 proto 形状一致。

## `BackupReplica` 不一样

`BackupReplica`(P4Runtime 1.5 新增)携带一个朴素的 `bytes port` 字段 ——
没有 `oneof`,也没有已废弃 int32 替代。jp4 把它暴露为 **非空** 的
`Bytes port`,因为在 1.5+ 设备上 proto 形状不允许该字段未设置:

<!-- illustrative: concept fragment -->

```java
for (Replica r : multicastGroup.replicas()) {
    for (BackupReplica b : r.backupReplicas()) {
        // b.port() 永远不会为 null —— BackupReplica proto 要求它存在。
        int backupEgressPort = new BigInteger(1, b.port().toByteArray()).intValueExact();
    }
}
```

这是所有带端口的记录中唯一一个 jp4 暴露非空形状的;差别仅来自 proto 形状
本身 —— `BackupReplica` 是 P4Runtime 在引入 int32 路径的废弃机制之后才
落地的第一个规范级类型,所以它从未携带过那条已废弃路径。

## 参见

- [v1.4 → v1.5 迁移指南](/migrations/migration-1.4-to-1.5) —— PRE 读
  能力的落地把 `Replica` / `BackupReplica` 首次带入 jp4 接口。
- [Canonical bytestring 编码](/zh/concepts/canonical-bytestring) ——
  一旦 `port()` 非空,字节本身就受前导零剥除规则约束。
- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— 产生这些
  记录的实体读族。
