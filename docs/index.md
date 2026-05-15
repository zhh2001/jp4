---
layout: home
title: jp4 — Java client library for P4Runtime
description: jp4 is a native Java client library for P4Runtime. Connect to a P4Runtime-enabled device, push pipelines, read and write table and PRE entries, and send and receive packets through the StreamChannel.

hero:
  name: jp4
  text: Java client library for P4Runtime
  tagline: Native, dependency-free, target-agnostic. Built for Java 21+ controllers running against BMv2 today and P4Runtime-compliant hardware on the same API.
  actions:
    - theme: brand
      text: Get started
      link: /getting-started
    - theme: alt
      text: View on GitHub
      link: https://github.com/zhh2001/jp4

features:
  - title: Typed, fluent API
    details: P4Switch, TableEntry, ReadQuery and one query-builder per entity type — counter, meter, register, action-profile member and group, multicast group, clone session. Name-based where the P4 program declares names; numeric-id where the packet replication engine is program-agnostic.
  - title: Built for P4Runtime 1.5
    details: Tracks the P4Runtime specification through 1.5; spec-level types like BackupReplica surface as first-class jp4 records. Proto sources vendored unmodified from p4lang/p4runtime.
  - title: BMv2-validated end to end
    details: CI runs against a digest-pinned p4lang/behavioral-model image on every push. Local Path B verification catches device-side behavior the gRPC fake cannot — UNIMPLEMENTED RPCs, canonical-bytestring encoding, port-kind oneof handling.
---
