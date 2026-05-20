---
title: Quick start — jp4 in 60 seconds
description: The shortest path from zero to a running jp4 controller. Pull jp4 from Maven Central, launch a BMv2 device in Docker, run the L2 learning-switch example, and watch the device learn two MAC addresses.
keywords: [jp4, P4Runtime, quickstart, 60 seconds, BMv2, Docker, simple-l2-switch, Maven Central]
---

# Quick start

This page is the 60-second entry to jp4. If you want the 15-minute
walkthrough with prerequisites, MVP code, and where-to-next pointers,
go straight to [Getting started](/guides/getting-started).

## What you need

- JDK 21 or newer (`java -version`).
- Docker (`docker info`).

Nothing else. jp4 ships its own Gradle wrapper.

## Three commands

```bash
# 1. Clone jp4 and start a BMv2 device.
git clone https://github.com/zhh2001/jp4.git && cd jp4
docker run --rm -d --name jp4-bmv2 -p 50051:50051 \
    p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247 \
    simple_switch_grpc \
        --no-p4 --device-id 0 --log-console -L info \
        -i 0@lo \
        -- --grpc-server-addr 0.0.0.0:50051 --cpu-port 255

# 2. Run the L2 learning-switch example.
cd examples && ./gradlew :simple-l2-switch:run

# 3. Clean up.
docker rm -f jp4-bmv2
```

## What you should see

If the run succeeds, the example prints (verbatim from a real run):

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

The two `LEARN` lines mean jp4 is talking to BMv2: the controller
saw two synthetic PacketIns, looked up the source MAC, found it
missing, and pushed a forwarding entry. That's a full
[PacketIn](/guides/packet-io) →
[TableEntry insert](/guides/tables) round trip.

## Use jp4 from your own project

Add the Maven Central coordinate:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.zhh2001:jp4:1.5.0")
}
```

The full walkthrough — including the minimum-viable controller skeleton
and the connect / bindPipeline / operate / close lifecycle — is in
[Getting started](/guides/getting-started). From there, the [Guides](/guides/getting-started)
section covers connection and arbitration, pipelines, tables, packet I/O,
and error handling in depth.
