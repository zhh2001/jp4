---
title: 流水线
description: 通过 bindPipeline 把 P4 流水线推送到 P4Runtime 设备,用 loadPipeline 拉取设备当前已安装的流水线,以及 P4Info 名称索引如何驱动所有按名调用,并说明 jp4 如何把客户端与设备之间的流水线漂移以显式异常暴露出来。
keywords: [jp4, P4Runtime, P4Info, DeviceConfig, BMv2, bindPipeline, loadPipeline, SetForwardingPipelineConfig, 流水线漂移]
---

# 流水线

P4Runtime 设备运行一个 P4 程序;jp4 把该程序的安装称为 **流水线**
(pipeline)。推送流水线给设备赋予了转发行为,也让 jp4 拿到了把按名 API
调用翻译为线上数字 id 所需的 schema(P4Info)。

## jp4 需要什么

两个由 P4 编译器(`p4c`)产出的工件:

- **P4Info** —— schema。列出每个表、动作、匹配字段、以及
  `controller_packet_metadata` 声明的名称、数字 id 和位宽。P4Runtime
  把它定义为 `p4.config.v1.P4Info` protobuf;jp4 把它包成 `P4Info` 类。
- **设备配置(device config)** —— 目标特定的二进制可执行体。对于 BMv2
  就是 `p4c-bm2-ss` 产出的 `.json`。jp4 把它包成
  `DeviceConfig.Bmv2`(BMv2 JSON)或 `DeviceConfig.Raw`(其它目标的逃生口)。

两者一般在编译阶段产出,作为资源随控制器一起发布。加载方式:

<!-- illustrative -->

```java
P4Info p4info = P4Info.fromFile(Path.of("…/myprog.p4info.txtpb"));
DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("…/myprog.json"));
```

*实际使用: [`simple-loadbalancer`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/).*

`P4Info.fromFile(...)` 自动识别文件是二进制 protobuf 还是 P4Runtime 文本
格式。`P4Info.fromBytes(byte[])` 接受同样的两种格式,源可以是任何字节流
(资源、网络、生成器)。

## bindPipeline 与 loadPipeline 的差别

两个操作,与设备的关系截然不同:

**`bindPipeline(p4info, dc)`** —— *推送(push)*。主控客户端告诉设备要运行
哪条流水线。线上 RPC 是 `SetForwardingPipelineConfig(VERIFY_AND_COMMIT)`;
该调用阻塞直到设备确认。如果交换机不是主控就抛 `P4ConnectionException`;
如果设备拒绝该流水线就抛 `P4PipelineException`。

<!-- illustrative -->

```java
sw.bindPipeline(p4info, dc);   // 仅主控可用
```

*实际使用: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

**`loadPipeline()`** —— *拉取(pull)*。客户端获取设备当前已安装的流水线、
填充本地 P4Info 引用,然后返回。不写入。从属用它来填充自己的 schema,
而不必占据主控身份。

<!-- illustrative -->

```java
sw.loadPipeline();   // 主控或从属皆可
```

*实际使用: [`network-monitor`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/).*

两个方法都返回 `P4Switch`,因而能与连接链式拼接:

<!-- illustrative -->

```java
try (P4Switch sw = P4Switch.connectAsPrimary(addr).bindPipeline(p4info, dc)) {
    // ...
}

try (P4Switch monitor = P4Switch.connect(addr).electionId(...).asSecondary()) {
    monitor.loadPipeline();
    // ...
}
```

*实际使用: [`simple-l2-switch`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/).*

## P4Info 作为名称索引

流水线绑定后,jp4 API 余下的全部按名工作 —— 不需要 id:

<!-- illustrative -->

```java
sw.read("MyIngress.ipv4_lpm").all();             // 表名
sw.insert(TableEntry.in("MyIngress.ipv4_lpm")    // 表名
        .match("hdr.ipv4.dstAddr",               // 匹配字段名
               new Match.Lpm(...))
        .action("MyIngress.forward")             // 动作名
        .param("port", 1)                        // 参数名
        .build());
```

*实际使用: [`simple-loadbalancer`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/).*

拼错的名字在调用点立即失败,并附上 known-list 错误消息,因而手误永远
不会到达设备:

```
P4PipelineException: Field 'hdr.ipv4.bogus' not found in table
'MyIngress.ipv4_lpm'. Known fields: [hdr.ipv4.dstAddr, …]
```

如果你需要遍历 schema(例如构建工具层),P4Info 也把它直接暴露出来:

<!-- illustrative: concept fragment -->

```java
for (TableInfo t : p4info.tableNames().stream()
        .map(p4info::table).toList()) {
    System.out.println(t.name() + " (" + t.id() + ")");
    for (MatchFieldInfo mf : t.matchFields()) {
        System.out.println("  " + mf.name()
                + " " + mf.matchKind() + " " + mf.bitWidth() + " bits");
    }
}
```

## DeviceConfig 变体

`DeviceConfig` 是 sealed 类型:

- **`DeviceConfig.Bmv2`** 包装一段 BMv2 JSON 字节数组。任何 BMv2 风味的
  目标(`simple_switch_grpc`、`simple_switch`、衍生版本)都用它。
- **`DeviceConfig.Raw`** 是任何其它目标的逃生口。设备端如何解析是设备的
  问题;jp4 只是把字节送出去。

两者都有 `fromFile(Path)` 工厂方法。`DeviceConfig` 目前 sealed 在 `Bmv2`
和 `Raw` 两个变体;面向非 BMv2 设备的控制器,要么使用 `DeviceConfig.Raw`
(只承载字节的逃生口),要么用别处构造好的目标专属字节负载喂进来。
为其它目标新增一个具名变体走的是项目 GitHub 仓库的外部贡献路径,不
代表项目内部承诺。

## 客户端与设备之间的流水线漂移

如果客户端绑定的 P4Info 与设备实际安装的不一致(例如有人在你眼皮底下
热换了流水线),进来的 PacketIn 可能携带客户端无法解码的元数据 id。jp4
在读侧反向解析时把这种情况大声暴露:

```
P4PipelineException: device returned table id 12345678 which is not in
the bound P4Info; pipeline may have drifted since bindPipeline (known
table ids: [33554497, 33554498, …])
```

逐报文层面,分发循环以 SLF4J warn 级别记日志并丢弃这条报文,而不是污染
整条流。生产中若看到这条日志,说明客户端和设备的流水线已经失同步 ——
重新 `loadPipeline()` 或重新 `bindPipeline(...)` 即可恢复。

## 参见

- [表](tables) —— 每次按名查找都通过绑定的 P4Info 解析。
- [报文 I/O](packet-io) —— `controller_packet_metadata` 声明喂给 PacketIn /
  PacketOut 编解码器。
- [`examples/simple-loadbalancer/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/)
  端到端展示了 `bindPipeline` 与表操作。
- [`examples/network-monitor/`](https://github.com/zhh2001/jp4/tree/main/examples/network-monitor/)
  展示了从属控制器使用 `loadPipeline()` 的模式。
