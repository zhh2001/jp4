---
title: jp4 入门
description: 从零开始安装 jp4 —— 从 Maven Central 引入依赖、启动 BMv2 设备、运行 L2 学习交换机示例,并编写第一个最小可用的 jp4 控制器。15 分钟的完整走读,从空白环境到可工作的 P4Runtime 客户端。
keywords: [jp4, P4Runtime, Java, 入门, BMv2, 控制器, Maven Central, simple-l2-switch]
---

# 入门

本指南将带你从一无所有走到第一个 jp4 控制器。预计 15 分钟,其中最长的
单步是首次 Gradle 下载。如果你已经装好 JDK 21+、Docker,并 clone 了 jp4
仓库,可以直接跳到 [运行第一个示例](#运行第一个示例)。

## 前置条件

- **JDK 21 或更高版本。** `java -version` 应输出 21+。项目 CI 同时验证
  JDK 21 和 JDK 25,两者都受支持。
- **Docker。** 示例与测试套件都使用容器化的 BMv2 设备,不需要本机安装
  BMv2。

仅此两项。jp4 自带 Gradle wrapper,无需单独安装 Gradle。项目依赖一小组
grpc-java + protobuf-java jar 包,首次构建时 Gradle 会自动拉取。

## 把 jp4 加入到你的项目

jp4 以 `io.github.zhh2001:jp4` 坐标发布在 Maven Central。在构建脚本中
引入即可:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.zhh2001:jp4:1.0.0")
}
```

如果你倾向于从源码消费 jp4(例如贡献代码或跟踪 `main`),可以用 Gradle
composite build —— 参见
[`examples/`](https://github.com/zhh2001/jp4/tree/main/examples/) 目录,
其中用 `includeBuild("..")` 把示例和 jp4 主仓粘合在一起。

## 运行第一个示例

三条命令,然后 BMv2 的日志就开始滚动:

```bash
# 1. 获取 jp4 并启动 BMv2 实例。
git clone https://github.com/zhh2001/jp4.git
cd jp4
docker run --rm -d --name jp4-bmv2 -p 50051:50051 \
    p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247 \
    simple_switch_grpc \
        --no-p4 --device-id 0 --log-console -L info \
        -i 0@lo \
        -- --grpc-server-addr 0.0.0.0:50051 --cpu-port 255

# 2. 运行 L2 学习交换机示例。
cd examples
./gradlew :simple-l2-switch:run

# 3. 收尾清理。
docker rm -f jp4-bmv2
```

示例输出(来自一次真实运行,逐字摘录):

```
[L2] connected as primary on 127.0.0.1:50051, pipeline pushed
[L2] inject    src=aa:00:00:00:00:01 dst=ff:ff:ff:ff:ff:ff via simulated ingress 1
[L2] PacketIn  src=AA:00:00:00:00:01 dst=FF:FF:FF:FF:FF:FF ingress=1
[L2] LEARN     AA:00:00:00:00:01 → port 1 (entry installed)
[L2] inject    src=bb:00:00:00:00:02 dst=ff:ff:ff:ff:ff:ff via simulated ingress 2
[L2] PacketIn  src=BB:00:00:00:00:02 dst=FF:FF:FF:FF:FF:FF ingress=2
[L2] LEARN     BB:00:00:00:00:02 → port 2 (entry installed)
[L2] learned table: {AA:00:00:00:00:01=1, BB:00:00:00:00:02=2}
```

如果你看到两条 `LEARN` 行和末尾 `learned table:` 摘要中带两个 MAC 条目,
说明 jp4 与设备的通信已经走通。

## 刚才发生了什么

`simple-l2-switch` 示例以主控身份连接 BMv2,推送 `simple_l2.p4` 流水线,
注册 PacketIn 处理器,然后注入两个合成以太网帧。数据面的转发表初始为空,
所以每一帧都会 miss,BMv2 把它转发给控制器,处理器写入一条转发条目 ——
这正是 `LEARN` 那两行。源码请阅读
[`examples/simple-l2-switch/`](https://github.com/zhh2001/jp4/tree/main/examples/simple-l2-switch/)
的带注释版本。

## 编写你自己的控制器 —— 最小可用版

下面是一个能做实际工作的最小 jp4 程序:

<!-- illustrative: trimmed adaptation of examples/simple-loadbalancer/SimpleLoadbalancer.java#main, edited for didactic clarity (does not compile as shown — uses placeholder paths) -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.Ip4;

import java.nio.file.Path;

public final class HelloJp4 {
    public static void main(String[] args) throws Exception {
        P4Info p4info = P4Info.fromFile(Path.of("src/main/resources/p4/myprog.p4info.txtpb"));
        DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("src/main/resources/p4/myprog.json"));

        try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
                .bindPipeline(p4info, dc)) {

            sw.insert(TableEntry.in("MyIngress.ipv4_lpm")
                    .match("hdr.ipv4.dstAddr", new Match.Lpm(Ip4.of("10.0.0.0").toBytes(), 8))
                    .action("MyIngress.forward").param("port", 1)
                    .build());

            System.out.println("inserted route 10.0.0.0/8 → port 1");
        }
    }
}
```

这个骨架对每个 jp4 控制器都适用:

1. **加载 P4Info 与设备配置** —— 从磁盘读取,或通过 `P4Info.fromBytes(...)`
   从任意字节源加载。
2. **建立连接** —— `P4Switch.connectAsPrimary(address)`,或更灵活的
   `P4Switch.connect(...).asPrimary()` / `.asSecondary()` 链式调用。
3. **推送流水线** —— `bindPipeline(p4info, dc)`。该方法返回 `P4Switch`,
   所以可以从左到右流式串联。
4. **执行操作** —— insert、modify、delete、read、send、receive。
5. **关闭。** `try-with-resources` 会自动处理;`close()` 幂等。

## 下一步

- [连接与仲裁](connection-and-arbitration) —— 主控 / 从属到底是什么,
  以及如何处理主控权丢失。
- [表](tables) —— 完整的 `TableEntry` / `Match` 构建器面 (LPM / ternary /
  range / optional 五种匹配),以及读查询。
- [报文 I/O](packet-io) —— 三种 PacketIn 接收风格、PacketOut 发送、以及
  `controller_packet_metadata` 的来回。
- [错误处理](error-handling) —— 四种异常类型与各自的语义。
