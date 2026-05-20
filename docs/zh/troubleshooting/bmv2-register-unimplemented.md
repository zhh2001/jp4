---
title: BMv2 对 register 读返回 UNIMPLEMENTED
description: 调用 sw.readRegister 时 P4OperationException 携带 UNIMPLEMENTED + 消息 "Register reads are not supported yet" —— 这是 BMv2 1.15.1 服务端实现的限制,不是 jp4 的 bug。
keywords: [jp4, 故障排查, BMv2, register, UNIMPLEMENTED, simple_switch_grpc, RegisterEntry]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# BMv2 对 register 读返回 `UNIMPLEMENTED`

## 症状

调用 `sw.readRegister("...")`(或 `readRegisterAsync`)抛出:

```
io.github.zhh2001.jp4.exceptions.P4OperationException:
    UNIMPLEMENTED: Register reads are not supported yet
    (operationType=READ)
```

针对同一台设备,counter、meter、table、action-profile、multicast、clone 读都正常。只有 register 读触发。

## 原因

P4Runtime 规范允许服务端在不实现某实体类型读时,以 gRPC `UNIMPLEMENTED` 状态码拒绝 Read RPC。BMv2 `simple_switch_grpc` 1.15.1(jp4 CI 矩阵中锁定的版本,也是大多数 BMv2 docker 镜像目前发布的版本)不实现 `RegisterEntry` 读;任何 `entity` oneof 选择 `register_entry` 的 `Read` 请求都会返回 `UNIMPLEMENTED`。

这是设备侧的局限,不是 jp4 的 bug。jp4 的 `readRegister` 接口由 `P4SwitchReadRegisterTest` 针对进程内 gRPC fake 做端到端验证,请求构造与响应解析路径都是正确的。

## 处理

- **针对 BMv2 的集成测试**: 捕获 `P4OperationException`、检查 `errorCode() == UNIMPLEMENTED`、用 JUnit 的 `Assumptions.assumeTrue(false, ...)` 跳过断言。jp4 自己的 `CountersMetersRegistersGroupsIntegrationTest` 就这么做 —— 见该文件里的 `Assumptions.assumeTrue` 块。
- **生产控制器**: 捕获 `P4OperationException`、检视 `errorCode()`、若为 `UNIMPLEMENTED`,对该 BMv2 部署改用另一种可观测机制(报文探针、与 register 写并行的 counter,等等)。
- **canonical-bytestring 兼容、且使用非 BMv2 服务端的控制器**: 任何实现了 register 读的 P4Runtime 服务端(合规的 Tofino / Stratum 构建)上,register 读都能正常工作。

## 背景

P4Runtime 规范对读 RPC 容忍 `UNIMPLEMENTED`,反映的是不是每个设备后端都支持同样的实体类型。jp4 接口与目标无关;设备侧运行时决定哪些实体读可以应答。

`Read` RPC 是少数几个 `UNIMPLEMENTED` 属于规范合规响应的 P4Runtime RPC 之一,另外两种是 `OK`(带行)和 `OK`(空)。它不是可重试的瞬态错误 —— 设备在告诉你它本身不支持该实体类型。

## 参见

- [读取 counter、meter、register、action-profile 条目](/zh/cookbook/entity-reads) —— recipe;同样带有这条 BMv2 提示。
- [v1.3 → v1.4 迁移指南](/migrations/migration-1.3-to-1.4) —— 接口引入,同样注明此 BMv2 限制。
- [错误处理](/zh/guides/error-handling) —— `P4OperationException` 如何携带 gRPC `errorCode()`。
