---
title: Canonical bytestring 编码
description: P4Runtime 1.3+ 如何在线协议层面对值形字段去除前导零字节、jp4 在读侧返回什么形态,以及控制器把读回的值和本地参考值做比较时应当如何处理。
keywords: [jp4, P4Runtime, canonical bytestring, 编码, 前导零, Bytes, 字节比较]
---

# Canonical bytestring 编码

P4Runtime 1.3 引入了一条针对值形字节字段的线协议编码规则 —— 设备放到
线上的字节是值的 *规范(canonical)* 形式,前导零字节被剥除。v1.4 延续
了这一约定,v1.5 继续延续。控制器若把读回的字节与本地构造的参考值做
比较,就必须了解这条规则,因为最直觉的逐字节 `equals` 在读侧并不成立。

本页解释这条编码做了什么、P4Runtime 为什么采纳它、以及 jp4 调用方应该
如何处理。

## 规则

宽度为 `W` 位的值,在线上携带 `ceil(W / 8)` 字节 —— *不,实际承载该数值
所需的最少字节数*。前导零字节被剥除。9 位的端口值 5 以 `{0x00, 0x05}`
送出,经过 P4Runtime 1.3+ 设备回来之后,变成单字节规范形式 `{0x05}`。

数值等价性是被保留的:`BigInteger(1, {0x00, 0x05})` 和
`BigInteger(1, {0x05})` 都求值为 5。带前导零的形态是控制器侧的习惯;规范
形态是规范在线上要求的形状。

规范引用为 P4Runtime §8.4 "Bytestrings" —— 该节描述合规的编码(只携带数值
量值字节、不含前导零)和合规的解码(接收方必须接受两种形态)。

## jp4 返回什么

jp4 返回设备返回的东西。在以规范形式发送的 P4Runtime 1.3+ 设备上,
`CounterEntry`、`TableEntry`、`ActionProfileMember`、`Replica` 以及任何
其它读侧带 `Bytes` 字段的记录,暴露的是 *规范* 字节 —— 在上面的例子里
是单字节 `{0x05}`。

写侧,jp4 接受任意一种形态。流式 `Match` 构建器接受 `int` / `long` /
`Bytes` / `byte[]` / `Mac` / `Ip4` / `Ip6`,并按调用方传入的形式序列化;
设备被要求接受两种。把已知参考值存成带前导零的形态的控制器,在写侧
不需要做规范化,只在比较侧需要。

## 如何把读回的值和参考值比较

按控制器手头已经有什么,有三个选项:

**1. 数值比较。** 如果字段是固定宽度整数,`BigInteger(1, ...)` 包装对两
种形态都成立:

<!-- illustrative: concept fragment -->

```java
byte[] readBack = entry.match("hdr.ipv4.dstAddr").asLpm().value().toByteArray();
byte[] referenceBytes = referenceIp.toBytes();   // 可能带前导零
if (new BigInteger(1, readBack).equals(new BigInteger(1, referenceBytes))) {
    // 数值等价 —— 不论前导零是否被剥都成立
}
```

**2. 规范化参考值。** 在按字节比较之前剥掉前导零:

<!-- illustrative: concept fragment -->

```java
static byte[] canonicalise(byte[] in) {
    int i = 0;
    while (i < in.length - 1 && in[i] == 0) i++;
    return java.util.Arrays.copyOfRange(in, i, in.length);
}

if (java.util.Arrays.equals(readBack, canonicalise(referenceBytes))) {
    // 规范形式按字节相等
}
```

单字节零(`{0x00}`)是零的规范形式 —— 保留一个字节,不要剥到空。循环
里的 `i < in.length - 1` 守护这一情形。

**3. 包成 `Bytes` 再 `equals`。** jp4 的 `Bytes` 值类型在构造时做规范化,
所以 `Bytes.of(referenceBytes).equals(readBackBytes)` 对任意数值等价的
输入对都成立。当控制器本来就在 `Bytes` 世界里、不想写命令式循环时,
这是最干净的写法。

## 宽度形字段不是值形字段

canonical-bytestring 规则适用于 *值形* 字段 —— 匹配键、动作参数、counter
/ meter / register 单元值、`Replica.port` 和 `BackupReplica.port` 等等。
它 **不** 适用于长度形或宽度形字段(它们以 proto `uint32` / `int32` 载荷,
没有逐字节表示;proto 编码本身负责)。

在 jp4 里,线上类型为 `bytes` 的每一个字段都受这条规则影响;线上类型为
`uint32` / `int32` / `int64` 的字段(前缀长度、计数、id、时间戳)不受影响。

## 这条规则为何存在

规范理由有两点:

- **线上体积。** 不做规范化时,IPv6 的 256 位地址即便实际值很小(回环、
  链路本地前缀),也会消耗 32 字节,即便 2 字节就够。在大规模场景下
  (百万级路由)这是有意义的成本。
- **线上位宽独立性。** 设备内部把某字段在软件升级中悄悄拓宽或收窄几位,
  也不会破坏按字节与历史读结果比较的控制器 —— 双方只看到数值量值。

代价是上面那条比较规则;收益是控制器和设备可以独立演化内部位宽。

## 参见

- [port_kind 习惯](/zh/concepts/port-kind-idiom) —— jp4 如何在
  `Replica.port` (一个 canonical-bytestring 字段)未设置 vs 为零时做
  区分。
- [表](/zh/guides/tables) —— 写侧 `Match` 构建器接受任意字节形输入,
  设备被要求接受。
- [v1.3 → v1.4 迁移指南](/migrations/migration-1.3-to-1.4) —— 在
  action-profile 读取里携带了同一论点。
