---
title: 读回字节与写入时不一致
description: 为何写入设备的值与读回的值做字节比较失败 —— P4Runtime canonical-bytestring 编码会剥除前导零字节。
keywords: [jp4, 故障排查, canonical bytestring, 前导零, Bytes, 比较, 读回]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# 读回字节与写入时不一致

## 症状

你向设备写了一个值 —— 比如 9 位端口号 `5` 编码为 `{0x00, 0x05}` —— 后续 Read 返回同样的字段为 `{0x05}`(单字节而非双字节)。逐字节比较失败:

```java
byte[] written = { 0x00, 0x05 };
sw.insert(entry);                                   // writes {0x00, 0x05}
TableEntry e = sw.read("...").one().orElseThrow();
byte[] readBack = e.match("port").asExact().value().toByteArray();
// readBack is {0x05}, not {0x00, 0x05}
java.util.Arrays.equals(written, readBack);         // false
```

两个值数值上等价(都解码为 5),但字节数组长度不同。

## 原因

P4Runtime 1.3+ 规定:对值形字段,设备放到线上的字节是 **规范(canonical)** 形式 —— 表示数值量值的最少字节数。前导零字节被剥除。规范引用为 P4Runtime §8.4 *"Bytestrings"*。规则适用于读侧每个值形字段:匹配键、动作参数、counter / meter / register 单元值、`Replica.port`、`BackupReplica.port`,等等。

写侧 jp4 接受两种形态 —— 设备被要求接受两种。非对称性只在读侧浮现。

## 处理

按控制器手头已经有的内容选三种之一:

**1. 数值比较** —— 把两个字节数组都包到 `BigInteger(1, ...)` 里:

```java
new BigInteger(1, written).equals(new BigInteger(1, readBack));   // true
```

**2. 规范化参考值后再比较:**

```java
static byte[] canonicalise(byte[] in) {
    int i = 0;
    while (i < in.length - 1 && in[i] == 0) i++;
    return java.util.Arrays.copyOfRange(in, i, in.length);
}

Arrays.equals(canonicalise(written), readBack);   // true
```

单字节零(`{0x00}`)是零的规范形式 —— `i < in.length - 1` 边界保留它。

**3. 包成 `Bytes`** —— jp4 的 `Bytes` 值类型在构造时做规范化:

```java
Bytes.of(written).equals(Bytes.of(readBack));   // true
```

## 背景

规范选择有两个理由:
- **线上体积**: 不做规范化的话,IPv6 的 256 位地址若数值很小,也要占 32 字节;规范形式只需 2 字节。
- **线上位宽独立性**: 设备在软件升级中内部把字段拓宽或收窄几位,不会破坏按字节与历史读结果比较的控制器 —— 双方只看到数值量值。

代价是上面的比较规则;收益是线上与存储表示解耦。

## 参见

- [Canonical bytestring 编码](/zh/concepts/canonical-bytestring) —— 完整原理、worked examples、宽度形字段为何不属于值形。
- [表](/zh/guides/tables) —— 写侧 `Match` 构建器接受任意字节形输入;设备被要求接受。
- [v1.3 → v1.4 迁移指南](/migrations/migration-1.3-to-1.4) —— action-profile 读同样涉及此点。
