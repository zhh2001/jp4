---
title: 错误处理
description: jp4 的四种异常类型 —— P4ConnectionException、P4ArbitrationLost、P4PipelineException、P4OperationException —— 各自的语义、schema 问题的 known-list 消息、批量写入失败的 WriteResult 结构,以及异步路径如何通过 CompletableFuture 反馈失败。
keywords: [jp4, P4Runtime, 异常, 错误处理, P4ConnectionException, P4ArbitrationLost, P4PipelineException, P4OperationException, WriteResult]
---

# 错误处理

jp4 的异常体系小且层次清晰,直接对应到 **哪里** 出了问题。一共四种运行
时异常类型,都继承自 `P4RuntimeException`:

```
P4RuntimeException                       (父类,抽象意味)
├── P4ConnectionException                传输 / 主控权 / 已关闭的交换机
│       └── P4ArbitrationLost            主控被拒(连接或重夺时)
├── P4PipelineException                  P4Info / 设备配置 / schema 问题
└── P4OperationException                 设备拒绝了 RPC
```

前三种对应 **故障发生的位置**:连接层、schema 层、应用层。第四个
(`P4ArbitrationLost`)是连接故障的特化,出于 HA 原因,控制器通常希望
单独处理它。

## P4ConnectionException

应用层之下任何出错的情形。来源:

- gRPC 通道错误(对端不可达、TLS 问题、连接中断)。
- 交换机已关闭(已经调用过 `sw.close()`),后续操作仍尝试使用它。
- 流被打破并且没有配置自动重连(或已耗尽重试次数)。
- 主控权丢失(别人成了主控;后续写操作会拿到这个异常)。
- 操作超时(默认 30 秒)等待设备响应。

<!-- illustrative: concept fragment -->

```java
try {
    sw.insert(entry);
} catch (P4ConnectionException e) {
    // 此刻无法继续操作这台交换机。打日志;考虑重连;若控制器有 HA 伙伴,
    // 切换过去。
}
```

`P4ConnectionException.getMessage()` 短且对人友好。底层的 gRPC `Status`
(若存在)在 cause 链里 —— 用来区分 UNAVAILABLE(过会儿再试)和
PERMISSION_DENIED(同样凭据不必再试)。

### P4ArbitrationLost

`P4ConnectionException` 的子类,当一次 connect 或 `asPrimary()` 重夺被
设备拒绝(因为另一个客户端已持主控)时浮现:

<!-- illustrative: concept fragment -->

```java
try {
    sw.asPrimary();
} catch (P4ArbitrationLost e) {
    log("primary denied; current is " + e.currentPrimary()
        + ", ours was " + e.ourElectionId());
    // 也许用更高的 election id 重试,或退避后再来。
}
```

如果你的控制器需要在"我们丢了主控"与"网络挂了"两种情形下走不同分支,
就单独捕获这一异常 —— 二者都浮现为连接层问题,但补救动作不同。

## P4PipelineException

P4Info / 设备配置 / schema 不一致。来源:

- 加载错误的 P4Info 或设备配置字节。
- 在没有绑定流水线(没调用 `bindPipeline` 或 `loadPipeline`)的交换机上
  执行需要 schema 的操作。
- 拼错的表 / 动作 / 匹配字段 / 参数 / 元数据名称 —— 带 known-list 消息
  指明候选集合。
- 匹配类型不匹配(对精确匹配字段用 `Match.Lpm`)。
- 值超出字段声明位宽。
- 动作不在表的允许动作集合里。
- 入站条目反向解析时引用了绑定 P4Info 里没声明的 id(客户端 / 设备之间
  的流水线漂移)。

<!-- illustrative: concept fragment -->

```java
try {
    sw.insert(entry);
} catch (P4PipelineException e) {
    // schema 问题。几乎总是代码 bug,或者 P4Info 版本错位。要么修
    // 调用点,要么重新 bindPipeline(...) 刷新 schema。
    log.error("pipeline error: {}", e.getMessage());
}
```

Known-list 错误消息是 jp4 对 schema 驱动 API 的主要 UX 投入:

```
P4PipelineException: Field 'hdr.ipv4.bogus' not found in table
'MyIngress.ipv4_lpm'. Known fields: [hdr.ipv4.dstAddr]

P4PipelineException: Action 'do_nothing' not part of action set for
table 'MyIngress.ipv4_lpm'. Allowed actions: [MyIngress.forward,
MyIngress.drop]

P4PipelineException: value width 10 bits exceeds action 'forward' param
'port' bitWidth 9
```

读消息时大多数手误或版本错位会显而易见。

## P4OperationException

设备的 PI 库在 Write 或 Read RPC 上返回了显式拒绝。携带:

- `operationType()` —— `INSERT` / `MODIFY` / `DELETE` / `READ`。
- `errorCode()` —— gRPC 规范错误码(`ALREADY_EXISTS`、`NOT_FOUND`、
  `INVALID_ARGUMENT`、…)。
- `failures()` —— 批量写入时,每条被拒更新对应一条 `UpdateFailure`
  记录,带原始 batch 下标。读取时永远为空(读失败是整体失败)。

<!-- illustrative: concept fragment -->

```java
try {
    sw.insert(entry);
} catch (P4OperationException e) {
    switch (e.errorCode()) {
        case ALREADY_EXISTS -> sw.modify(entry);    // 良性 —— 与另一个 writer 抢
        case NOT_FOUND      -> log("table or scope was removed; investigate");
        case INVALID_ARGUMENT -> log("device-side validation rejected this entry: " + e.getMessage());
        default             -> log("unexpected device error: " + e.errorCode() + " " + e.getMessage());
    }
}
```

对批量写入,`BatchBuilder.execute()` 不会因为逐条失败而抛 —— 它返回
`WriteResult`,其中的 `failures()` 列表带每条被拒更新。详见
[tables.md](tables#批量写入)。

## 异步路径

`*Async` 方法(`insertAsync`、`modifyAsync`、`deleteAsync`、`sendAsync`、
`allAsync`、`oneAsync`)对返回后才发现的问题,绝不会在调用线程上抛出。
校验错误、schema 问题、RPC 失败都通过返回的 `CompletableFuture` 以异常
方式完成,异常类型与同步方法本会抛出的一致:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(entry).whenComplete((v, t) -> {
    if (t instanceof P4OperationException op)        rejected(op);
    else if (t instanceof P4PipelineException pp)    schemaError(pp);
    else if (t instanceof P4ConnectionException ce)  cantTalkToDevice(ce);
    else if (t == null)                              ok();
    else                                             unknownTrouble(t);
});
```

这对应 "返回 future 的方法只通过 future 反馈失败,绝不在调用线程抛"
这条设计规则。同步方法负责 unwrap future 并重新抛出。

## 参见

- [连接与仲裁](connection-and-arbitration) —— `P4ConnectionException` /
  `P4ArbitrationLost` 浮现细节。
- [流水线](pipeline) —— PacketIn 分发触发 `P4PipelineException` 的
  schema 漂移场景。
- [表](tables) —— `WriteResult` 以及批量失败如何按条目出现。
