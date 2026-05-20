---
title: simple-loadbalancer —— LPM 路由批量
description: simple-loadbalancer 示例走读 —— 一次批量 Write RPC 安装三个 /24 前缀,读回验证,运行时修改一条路由,再读回。展示 batch 生命周期与逐条失败处理。
keywords: [jp4, 示例, simple-loadbalancer, LPM, 批量, WriteResult, IPv4 路由]
---

# `simple-loadbalancer`

LPM 负载均衡:三个 /24 前缀映射到三个后端端口。示例用一次批量 Write 安装初始路由表,读回验证设备视图,运行时修改一条路由,再读回确认。

**GitHub 源码**: [`examples/simple-loadbalancer/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/)(Java + P4 + Gradle 构建)

## 示例展示了什么

- 推送流水线 + 一次 RPC 批量 insert 多条条目(`sw.batch().insert(...).insert(...).execute()`)。
- LPM 匹配构造,通过 `new Match.Lpm(Bytes, prefixLen)` 或 `Match.lpm(cidr)` 简写。
- 通过 `sw.read("…").all()` 读条目并遍历结果。
- 用 `sw.modify(...)` 运行时改路由。
- IPv4 类型人体工学: `Ip4.of("10.0.1.0").toBytes()`。

## 本地运行

```bash
cd examples
./gradlew :simple-loadbalancer:run
```

可选: `--args="my-bmv2-host:50051"` 覆盖设备地址。BMv2 启动行见 [示例的 README](https://github.com/zhh2001/jp4/blob/main/examples/simple-loadbalancer/README.md)。

## 期望输出

```
[LB] connected as primary on 127.0.0.1:50051, pipeline pushed
[LB] installed 3 routes (allSucceeded=true)
[LB] backend_lookup after install: 3 entries
[LB]   10.0.1.0/24 → port 1
[LB]   10.0.2.0/24 → port 2
[LB]   10.0.3.0/24 → port 3
[LB] moved 10.0.2.0/24 to port 4
[LB] backend_lookup after modify: 3 entries
[LB]   10.0.1.0/24 → port 1
[LB]   10.0.2.0/24 → port 4
[LB]   10.0.3.0/24 → port 3
[LB] cleaned up; goodbye
```

`after modify` 块确认 "modify-then-readback" 回路解析到了新端口。

## 可以尝试

- 插入一条 *更具体* 的前缀(例如 `10.0.1.5/32 → port 9`),观察 BMv2 的 LPM 表在 `/24` 之前选择它。
- 用 `sw.read("…").match("hdr.ipv4.dstAddr", new Match.Lpm(...)).all()` 服务端过滤,与未过滤的 `.all()` 比较(BMv2 严格实现服务端 LPM 过滤语义)。
- 把清理循环包到 try/finally 里,这样运行中崩溃也仍然清理设备状态。
- 把一次 `.insert(...)` 换成故意错误的(未知表名),用 `WriteResult.failures()` 看逐条拒绝如何浮现而不中断整个 batch。

## 参见

- [LPM 路由表批量安装](/zh/cookbook/lpm-routes) —— 从本示例抽取的 recipe。
- [表](/zh/guides/tables) —— 完整 `Match` 构建器(exact / LPM / ternary / range / optional)与 `batch()` API。
- [Canonical bytestring 编码](/zh/concepts/canonical-bytestring) —— 读回的 IP 地址字节数为何可能少于写入时。
- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— `sw.batch().execute()` 如何翻译为单次 `Write` RPC + 多个 `Update`。
