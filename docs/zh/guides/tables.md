---
title: 表
description: jp4 完整的表 API 面 —— TableEntry 构建器、P4Runtime 五种匹配类型(exact / LPM / ternary / range / optional)、单条 insert / modify / delete、批量写入、同步与异步读取、服务端与客户端过滤,以及外出执行器的并发契约。
keywords: [jp4, P4Runtime, TableEntry, Match, LPM, ternary, range, optional, batch, ReadQuery, WriteResult]
---

# 表

表操作占据了 P4Runtime 控制器绝大多数日常工作。jp4 暴露 `insert` /
`modify` / `delete` 用于单次更新,`batch()` 用于多更新 RPC,`read(...)` 用于
查询。匹配键通过一个流式构建器构造;P4Runtime 的五种匹配类型(exact、
LPM、ternary、range、optional)是 sealed 类型,因而对它们的 `switch` 在
编译期就是穷尽的。

本指南覆盖完整的表 API 面。所有代码片段都取自集成测试和 `examples/`
模块,能够实际编译和运行。

## 构造 `TableEntry`

<!-- illustrative -->

```java
TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
        .match("hdr.ipv4.dstAddr", Match.lpm("10.0.1.0/24"))
        .action("MyIngress.forward").param("port", 1)
        .build();
```

*实际使用: [`simple-loadbalancer`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/).*

构建器全部按名 —— 表名、匹配字段名、动作名、参数名都是字符串,必须与
绑定的 P4Info 一致。拼错的名字在 `switch.insert(e)` 时刻失败,并带有
known-list 错误消息;构建器本身不做校验。

`TableEntry` 不可变。同一个实例可以安全地插入到多个共享同一流水线的
交换机,也可以作为 `delete` 的清理模板复用:

<!-- illustrative: concept fragment -->

```java
sw.insert(e);
// ...
sw.delete(e);   // 同一构建器结果,复用安全
```

对 `delete` 而言,只有匹配键有意义;线上传输时动作部分会被静默忽略。

控制器里常见的模式是用一个静态小辅助方法把构建器包起来,这样调用点
保持单行清爽。`simple-loadbalancer` 示例就是这么做的,用于 IPv4 LPM
路由:

<!-- snippet: examples/simple-loadbalancer/src/main/java/io/github/zhh2001/jp4/examples/loadbalancer/SimpleLoadbalancer.java#routeEntry -->

```java
private static TableEntry routeEntry(String cidr, int port) {
    return TableEntry.in("MyIngress.backend_lookup")
            .match("hdr.ipv4.dstAddr", Match.lpm(cidr))
            .action("MyIngress.forward").param("port", port)
            .build();
}
```

调用点写作 `routeEntry("10.0.1.0/24", 1)` —— 表名、匹配字段名、动作名都
被收纳在辅助方法里。

## 匹配类型

`Match` sealed 在五个变体。多数控制器只构造两到三种:

<!-- illustrative: concept fragment -->

```java
new Match.Exact(value)                                // 精确匹配
new Match.Lpm(prefix, prefixLen)                      // 最长前缀匹配
new Match.Ternary(value, mask)                        // ternary
new Match.Range(low, high)                            // 范围
new Match.Optional(value)                             // optional(null-safe 通配)
```

构建器的 `match(name, value)` 重载接受 `Bytes`、`Mac`、`Ip4`、`Ip6`、`byte[]`、
`int`、`long`,并自动包成 `Match.Exact`。要选用其它类型,显式传入对应的
`Match.Lpm` / `Ternary` / `Range` / `Optional`。负数 `int`/`long` 会被
`IllegalArgumentException` 拒绝,以捕获符号位混淆;若需要明确的位模式,
传 `byte[]` 或 `Bytes`。

当 **消费** 一条从设备读回来的条目时,穷尽 `switch` 给你编译期的覆盖
保证:

<!-- illustrative: concept fragment -->

```java
Match m = entry.match("hdr.ipv4.dstAddr");
String description = switch (m) {
    case Match.Exact e    -> "exact " + e.value();
    case Match.Lpm l      -> "lpm "   + l.value() + "/" + l.prefixLen();
    case Match.Ternary t  -> "ternary " + t.value() + "&" + t.mask();
    case Match.Range r    -> "range " + r.low() + ".." + r.high();
    case Match.Optional o -> "optional " + o.value();
};
```

字段不存在时返回 `null` 的契约:`entry.match("not_in_this_entry")` 返回
`null`,而不是空 Optional —— 字段集合因表而异,而大多数针对条目的匹配
查询都以"这个字段是不是属于这个键?"开始。

## 单次写入

`insert` / `modify` / `delete` 都是阻塞的,成功返回 `void`,失败抛异常:

<!-- illustrative: concept fragment -->

```java
sw.insert(e);    // 键已存在则抛 P4OperationException(ALREADY_EXISTS)
sw.modify(e);    // 键不存在则抛
sw.delete(e);    // 键不存在则抛
```

`*Async` 变体返回 `CompletableFuture<Void>`:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(e).thenRun(() -> log("ok"))
                 .exceptionally(t -> { logError(t); return null; });
```

校验失败(字段名未知、值过宽、动作不在表的动作集合内)与 RPC 失败一样
通过 future 反馈 —— 异步方法对返回后才发现的问题,绝不会在调用线程上
抛出。同步方法负责 unwrap future 并重新抛出。

## 批量写入

多个更新打包到一次 Write RPC:

<!-- illustrative -->

```java
WriteResult r = sw.batch()
        .insert(routeEntry("10.0.1.0/24", 1))
        .insert(routeEntry("10.0.2.0/24", 2))
        .insert(routeEntry("10.0.3.0/24", 3))
        .execute();

System.out.printf("installed %d routes (allSucceeded=%s)%n",
        r.submitted(), r.allSucceeded());
```

*实际使用: [`simple-loadbalancer`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/).*

`simple-loadbalancer` 的完整运行输出(来自一次真实运行,逐字摘录)展示
了 `batch().execute()` 的结果以及一次"读 - 改 - 再读"的循环:

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

`execute()` 总是返回一个 `WriteResult`。若任何一条更新被设备拒绝,
`WriteResult.failures()` 列出逐条 `UpdateFailure` 记录,带原始 batch 下标、
gRPC `ErrorCode`,以及设备返回的消息。`WriteResult.allSucceeded()` 当且
仅当 `failures()` 为空时为真。

<!-- illustrative: idiomatic post-execute inspection -->

```java
if (!r.allSucceeded()) {
    for (UpdateFailure f : r.failures()) {
        log("update[" + f.index() + "] failed: " + f.code() + " " + f.message());
    }
}
```

P4Runtime **不** 要求批量操作必须原子。更新按顺序应用,某次失败 **不会**
回滚前面已经成功的更新 —— 出于这一点,loadbalancer 示例显式用 delete
批量做收尾清理。

## 读取

`read(tableName)` 返回一个 `ReadQuery`,三个终端方法:

<!-- illustrative: concept fragment -->

```java
List<TableEntry> all       = sw.read("MyIngress.ipv4_lpm").all();
Optional<TableEntry> one   = sw.read("MyIngress.ipv4_lpm").one();
try (Stream<TableEntry> s = sw.read("MyIngress.ipv4_lpm").stream()) {
    s.forEach(e -> handle(e));
}
```

- `.all()` 把所有行收成一个列表。对最多几千条目的表来说够用。
- `.one()` 折叠成 `Optional.empty()`(零行)或 `Optional.of(e)`(恰好一
  行);若设备返回多于一行就抛 `P4OperationException`。在期望唯一键的
  场景下好用。
- `.stream()` 返回一个 `Stream<TableEntry>`,流关闭时会关掉底层的 gRPC
  迭代器。永远要 try-with-resources;提前关流会取消设备上的读取。

服务端过滤通过 `ReadQuery` 上的 `.match(...)` 给出,与写侧构建器对称:

<!-- illustrative: concept fragment -->

```java
List<TableEntry> hits = sw.read("MyIngress.ipv4_lpm")
        .match("hdr.ipv4.dstAddr", Match.lpm("10.0.1.0/24"))
        .all();
```

设备把匹配字段集合解释为逐更新的过滤(规范 §6.4);BMv2 严格按此实现。
空 match 列表 = "该表的每一行"。

## 异步读取

`allAsync()` / `oneAsync()` 是同步形式的镜像:

<!-- illustrative: concept fragment -->

```java
CompletableFuture<List<TableEntry>> f = sw.read("MyIngress.ipv4_lpm").allAsync();
f.thenAccept(rows -> log(rows.size() + " rows"));
```

没有 `streamAsync()` —— `stream()` 本身在生产消费前是非阻塞的,再裹一层
future 没什么意义。

## 并发

所有写终端和读终端都通过该 switch 的外出单线程执行器串行化。从多个用户
线程并发 `sw.insert(...)` 是安全的,并且在线上产出确定的顺序。switch 与
switch 之间没有跨实例的顺序保证;两个 `P4Switch` 对同一台设备的写入正常
赛跑。

`stream()` 是例外 —— 启动发生在外出线程,**消费** 发生在调用方线程。
多个流消费者彼此独立。

## 参见

- [流水线](pipeline) —— 每次按名调用都经过绑定的 P4Info。
- [错误处理](error-handling) —— `P4OperationException`、
  `P4PipelineException`、`P4ConnectionException`。
- [`examples/simple-loadbalancer/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/)
  端到端展示 batch + read + modify。
