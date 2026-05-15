---
layout: home
title: jp4 —— 面向 P4Runtime 的 Java 客户端库
description: jp4 是一个面向 P4Runtime 的原生 Java 客户端库。连接 P4Runtime 设备、下发 pipeline、读写 table 与 PRE 条目、并通过 StreamChannel 收发数据包。

hero:
  name: jp4
  text: 面向 P4Runtime 的 Java 客户端库
  tagline: 原生、零依赖、目标无关。为 Java 21+ 控制器而生 —— 今天面向 BMv2,明天通过同一套 API 面向 P4Runtime 合规硬件。
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/getting-started
    - theme: alt
      text: 在 GitHub 上查看
      link: https://github.com/zhh2001/jp4

features:
  - title: 类型化的流式 API
    details: P4Switch、TableEntry、ReadQuery,加上每种实体类型对应的查询构建器 —— counter、meter、register、action-profile member/group、multicast group、clone session。P4 程序声明名称的实体按名访问;包复制引擎与 P4 程序无关的实体按数字 id 访问。
  - title: 紧跟 P4Runtime 1.5 规范
    details: 跟随 P4Runtime 规范至 1.5;BackupReplica 等规范级新增类型作为一等 jp4 record 暴露。proto 源文件原样取自 p4lang/p4runtime。
  - title: BMv2 端到端验证
    details: 每次提交在 digest 锁定的 p4lang/behavioral-model 镜像上执行 CI。本地 Path B 验证捕获 gRPC fake 无法暴露的设备侧行为 —— UNIMPLEMENTED RPC、canonical-bytestring 编码、port_kind oneof 处理。
---
