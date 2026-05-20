---
title: 快速开始 —— 60 秒上手 jp4
description: 从零到第一个 jp4 控制器的最短路径。从 Maven Central 引入 jp4、Docker 启动 BMv2、运行 L2 学习交换机示例,看到设备学会两个 MAC 地址。
keywords: [jp4, P4Runtime, 快速开始, 60 秒, BMv2, Docker, simple-l2-switch, Maven Central]
---

# 快速开始

本页是 jp4 的 60 秒入口。若要 15 分钟的详细走读,包含前置条件、最小可
用控制器代码、以及后续指引,请直接看 [入门](/zh/guides/getting-started)。

## 你需要什么

- JDK 21 或更高(`java -version`)。
- Docker(`docker info`)。

仅此两项。jp4 自带 Gradle wrapper。

## 三条命令

```bash
# 1. 克隆 jp4 并启动 BMv2 设备。
git clone https://github.com/zhh2001/jp4.git && cd jp4
docker run --rm -d --name jp4-bmv2 -p 50051:50051 \
    p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247 \
    simple_switch_grpc \
        --no-p4 --device-id 0 --log-console -L info \
        -i 0@lo \
        -- --grpc-server-addr 0.0.0.0:50051 --cpu-port 255

# 2. 运行 L2 学习交换机示例。
cd examples && ./gradlew :simple-l2-switch:run

# 3. 清理。
docker rm -f jp4-bmv2
```

## 你应该看到什么

运行成功时,示例输出(来自一次真实运行,逐字摘录):

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

两条 `LEARN` 行表示 jp4 与 BMv2 通信正常:控制器收到两个合成
PacketIn,查询源 MAC,发现表中没有,然后写入一条转发条目。这是一次
完整的 [PacketIn](/zh/guides/packet-io) →
[TableEntry insert](/zh/guides/tables) 来回。

## 在你自己的项目里使用 jp4

加入 Maven Central 坐标:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.zhh2001:jp4:1.5.0")
}
```

完整走读 —— 含最小可用控制器骨架以及 connect / bindPipeline / operate /
close 生命周期 —— 在 [入门](/zh/guides/getting-started) 里。然后
[指南](/zh/guides/getting-started) 章节覆盖连接与仲裁、流水线、表、报文
I/O 和错误处理的深入内容。
