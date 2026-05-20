---
title: P4ArbitrationLost 在连接或重夺时抛出
description: 为何 P4Switch.asPrimary 抛出 P4ArbitrationLost —— 另一个客户端在设备上持有更高的 election id。如何读取携带的 election id 并用更高的 id 重试。
keywords: [jp4, 故障排查, P4ArbitrationLost, 主控, 从属, election id, 主控权, MasterArbitrationUpdate]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# `P4ArbitrationLost` 在连接或重夺时抛出

## 症状

调用 `P4Switch.connectAsPrimary(...)`、`P4Switch.connect(...).asPrimary()`、或 `sw.asPrimary()`(`Lost` 主控变更后重夺)抛出:

```
io.github.zhh2001.jp4.exceptions.P4ArbitrationLost:
    primary denied; our election_id=ElectionId(low=10), current primary election_id=ElectionId(low=100)
```

异常携带 `ourElectionId()` 和 `currentPrimaryElectionId()` —— 都是 `ElectionId` 值。

## 原因

P4Runtime 允许多个客户端同时连接到同一台设备。持有最高 `election_id` 的客户端是主控;其余是从属。jp4 发出 `MasterArbitrationUpdate` 请求以 election id X 成为主控,而设备已经有一个客户端持有 election id Y > X,设备会拒绝授予主控。jp4 把这个拒绝浮现为 `P4ArbitrationLost`。

`P4ArbitrationLost` 是 `P4ConnectionException` 的子类,所以 `catch (P4ConnectionException)` 兜底捕获仍然覆盖它 —— 但子类化让控制器能在 "我们丢了主控" 和通用连接故障之间分支处理。

## 处理

按"丢了仲裁该如何"分三种模式:

**1. 用更高的 election id 重试:**

```java
try {
    sw.connectAsPrimary(address);
} catch (P4ArbitrationLost e) {
    ElectionId higher = ElectionId.of(e.currentPrimaryElectionId().low() + 1);
    sw.connect(address).electionId(higher).asPrimary();   // try again
}
```

**2. 降级到从属角色**(只读 / 可观察用例):

```java
try {
    P4Switch.connectAsPrimary(address);
} catch (P4ArbitrationLost e) {
    return P4Switch.connect(address)
            .electionId(ElectionId.of(1))      // any low id
            .asSecondary();
}
```

**3. 主控强制类的失败-退出 + 运维报警**: HA 对(被拒就意味着对端已经在跑)的控制器应当向上传播异常、不要重试 —— 把 `currentPrimaryElectionId()` 记日志给运维并退出。

针对 **重夺情形**(`Lost` 回调后的 `sw.asPrimary()`),`Connector` 上的 `preserveRoleOnReconnect(true)` 是主控强制应用的推荐模式 —— 它在降级时自动关闭交换机,所以后续写操作抛 `P4ArbitrationLost` 而不是静默以从属身份成功。

## 背景

election id 是无符号 128 位(`Uint128` proto,jp4 中 `ElectionId.of(low, high)`)。"最高获胜" 在整个 128 位空间是全序的;没有平局。在大型部署中协调主控控制器的运维团队通常给每个控制器分配 election id 范围(基于时间戳、按进程世代序列等)。

P4Runtime 规范 §6.4 覆盖完整的主控权语义。设备 **必须** 在主控集合变更时通知所有连接的客户端 —— 当新主控接管时,从属观察到自身 `Lost` 状态;原主控在被降级时也观察到 `Lost`。

## 参见

- [连接与仲裁](/zh/guides/connection-and-arbitration) —— 完整主控权生命周期、HA 重夺模式、`onMastershipChange` 监听器。
- [错误处理](/zh/guides/error-handling) —— `P4ArbitrationLost` 作为 `P4ConnectionException` 的子类。
- [P4Runtime 规范映射](/zh/concepts/p4runtime-spec-mapping) —— `asPrimary` 如何翻译为 `MasterArbitrationUpdate`。
