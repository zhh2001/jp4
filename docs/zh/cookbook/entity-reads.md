---
title: 读取 counter、meter、register、action-profile 条目
description: v1.4 实体读族的 recipe —— readCounter / readMeter / readRegister / readActionProfileMember / readActionProfileGroup —— 共用查询构建器形态、服务端与客户端过滤、各实体的 record 字段。
keywords: [jp4, cookbook, counter, meter, register, action-profile, 实体读, ReadQuery]
---

# 读取 counter、meter、register、action-profile 条目

**我想要做:** 读取设备上 counter、meter、register 数组的逐 cell 值,或读取 action-profile 的成员和组 —— 用与表读相同的流式查询构建器。

## 查询构建器形态

`P4Switch`(v1.4+)上每个实体读族都同样的形态:

| 方法                            | 返回                                 | 服务端过滤 | 客户端过滤 | 终端方法 |
|---------------------------------|----------------------------------|---|---|---|
| `sw.readCounter("name")`        | `CounterReadQuery`               | `.index(long)`        | `.where(Predicate)` | `.all()` / `.one()` / `.stream()` / `.allAsync()` / `.oneAsync()` |
| `sw.readMeter("name")`          | `MeterReadQuery`                 | `.index(long)`        | `.where(Predicate)` | 同上 |
| `sw.readRegister("name")`       | `RegisterReadQuery`              | `.index(long)`        | `.where(Predicate)` | 同上 |
| `sw.readActionProfileMember(n)` | `ActionProfileMemberReadQuery`   | `.memberId(long)`     | `.where(Predicate)` | 同上 |
| `sw.readActionProfileGroup(n)`  | `ActionProfileGroupReadQuery`    | `.groupId(long)`      | `.where(Predicate)` | 同上 |

学一种,用五种。

## 读取 counter

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.CounterEntry;

import java.util.List;
import java.util.Optional;

List<CounterEntry> all = sw.readCounter("MyIngress.pkt_counter").all();
for (CounterEntry e : all) {
    System.out.printf("cell %d: packets=%d bytes=%d%n",
            e.index(), e.packetCount(), e.byteCount());
}

// Read one specific cell.
Optional<CounterEntry> cell0 = sw.readCounter("MyIngress.pkt_counter")
        .index(0L)
        .one();

// Only cells that have seen traffic.
List<CounterEntry> nonZero = sw.readCounter("MyIngress.pkt_counter")
        .where(e -> e.packetCount() > 0L)
        .all();
```

`CounterEntry` 带解析后的 counter 名、cell 索引,以及 `packetCount` 和 `byteCount`(都是原生 `long`)。哪个值有意义由 P4Info 中 counter 的 unit 决定(`BYTES` / `PACKETS` / `BOTH`)。

## 读取 meter

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.entity.MeterEntry;
import io.github.zhh2001.jp4.entity.MeterConfig;
import io.github.zhh2001.jp4.entity.MeterCounterData;

for (MeterEntry e : sw.readMeter("MyIngress.rate_meter").all()) {
    MeterConfig cfg = e.config();
    MeterCounterData cd = e.counterData();
    System.out.printf("cell %d cir=%d cburst=%d green=%d red=%d%n",
            e.index(), cfg.cir(), cfg.cburst(),
            cd.green().packetCount(), cd.red().packetCount());
}
```

`MeterEntry` 是嵌套 record:meter 名 + 索引 + `MeterConfig`(cir、cburst、pir、pburst、eburst)+ `MeterCounterData`(三色累积计数 green / yellow / red)。`eburst` 仅 srTCM 使用;trTCM meter 上呈现为零。

## 读取 register

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.entity.RegisterEntry;
import p4.v1.P4DataOuterClass.P4Data;

for (RegisterEntry e : sw.readRegister("MyIngress.flow_counters").all()) {
    P4Data datum = P4Data.parseFrom(e.data().toByteArray());
    byte[] value = datum.getBitstring().toByteArray();
    System.out.printf("cell %d value-bytes=%d%n", e.index(), value.length);
}
```

`RegisterEntry.data` 携带 **线上 `p4.v1.P4Data` proto 的序列化字节** —— 即 `proto.getData().toByteArray()` 返回的内容。`bit<W>` / `int<W>` 情形最常见(经 `P4Data.parseFrom(...).getBitstring()` 抽取),但完整 P4Data oneof —— struct、header、header_stack、enum、error、varbit、bool —— 都能触及。

## 读取 action-profile 的成员与组

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.entity.ActionProfileMember;
import io.github.zhh2001.jp4.entity.ActionProfileGroup;
import io.github.zhh2001.jp4.entity.WeightedMember;

// Members of an action profile.
for (ActionProfileMember m : sw.readActionProfileMember("MyIngress.lb_ap").all()) {
    System.out.printf("member %d → action %s%n",
            m.memberId(), m.action().name());
}

// Groups of an action profile.
for (ActionProfileGroup g : sw.readActionProfileGroup("MyIngress.lb_ap").all()) {
    System.out.printf("group %d maxSize=%d members=%d%n",
            g.groupId(), g.maxSize(), g.members().size());
    for (WeightedMember wm : g.members()) {
        String watch = wm.watchPort() == null
                ? "<unset>"
                : "(" + wm.watchPort().toByteArray().length + " bytes)";
        System.out.printf("  weighted member=%d weight=%d watchPort=%s%n",
                wm.memberId(), wm.weight(), watch);
    }
}
```

`ActionProfileMember.action` 是 `ActionInstance` —— `TableEntry.action()` 返回的同一种值类型,所以 action-profile 成员能 round-trip 到直接动作表条目同一形状。

## BMv2 注意: register 读返回 UNIMPLEMENTED

BMv2 `simple_switch_grpc` 1.15.1(jp4 测试使用的版本)对 `RegisterEntry` 的 Read RPC 返回 `Status{code=UNIMPLEMENTED, description=Register reads are not supported yet}`。counter、meter、action-profile、multicast、clone 的读都正常工作;只有 register 读会触发。见 [故障排查: BMv2 对 register 读返回 UNIMPLEMENTED](/zh/troubleshooting/bmv2-register-unimplemented)。

## 参见

- [表](/zh/guides/tables) —— v1.4 扩展的原始实体读查询构建器形态。
- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— 每个 `read*` 方法映射到哪个 proto 实体。
- [Canonical bytestring 编码](/zh/concepts/canonical-bytestring) —— 这些 record 里每个 `Bytes` 形字段都遵从前导零剥除规则。
- [v1.3 → v1.4 迁移指南](/migrations/migration-1.3-to-1.4) —— 接口引入。
