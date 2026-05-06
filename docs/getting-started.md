# Getting started

This guide takes you from nothing installed to writing your first jp4
controller. Budget 15 minutes; the longest step is the initial Gradle
download. If you already have JDK 21+, Docker, and a checked-out copy of
jp4, skip to [Run your first example](#run-your-first-example).

## Prerequisites

- **JDK 21 or newer.** `java -version` should print 21+. The project's CI
  matrix tests JDK 21 and JDK 25; both are supported.
- **Docker.** The examples and the test suite use a containerised BMv2
  device. No native BMv2 install is required.

That's it. jp4 ships its own Gradle wrapper; you do not need to install
Gradle separately. The project depends on a small set of grpc-java +
protobuf-java jars, which Gradle pulls on the first build.

## Add jp4 to your project

jp4 is published as `io.github.zhh2001:jp4` on Maven Central from v0.1.0.
Add it to your build:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.zhh2001:jp4:0.1.0")
}
```

Or if you prefer to consume jp4 from a checked-out clone (e.g. while
contributing or tracking `main`), use a Gradle composite build — see how
the [`examples/`](../examples/) directory wires this up via
`includeBuild("..")`.

## Run your first example

Three commands and the BMv2 logs scroll past:

```bash
# 1. Get jp4 and start a BMv2 instance.
git clone https://github.com/zhh2001/jp4.git
cd jp4
docker run --rm -d --name jp4-bmv2 -p 50051:50051 \
    p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247 \
    simple_switch_grpc \
        --no-p4 --device-id 0 --log-console -L info \
        -i 0@lo \
        -- --grpc-server-addr 0.0.0.0:50051 --cpu-port 255

# 2. Run the L2 learning switch example.
cd examples
./gradlew :simple-l2-switch:run

# 3. Cleanup.
docker rm -f jp4-bmv2
```

The example's output (verbatim from a real run):

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

If you see the two `LEARN` lines and the final `learned table:` summary
with two MAC entries, jp4 is talking to the device correctly.

## What just happened

The `simple-l2-switch` example connects to BMv2 as primary, pushes the
`simple_l2.p4` pipeline, registers a PacketIn handler, and injects two
synthetic Ethernet frames. The data-plane table starts empty, so each
frame misses, BMv2 forwards it to the controller, and the handler
writes a forwarding entry — that's the `LEARN` line. Read
[`examples/simple-l2-switch/`](../examples/simple-l2-switch/) for the
annotated source.

## Write your own controller — minimum viable

The smallest jp4 program that does useful work:

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

The shape applies to every jp4 controller:

1. **Load P4Info + device config** from disk (or any byte source via
   `P4Info.fromBytes(...)`).
2. **Connect** with `P4Switch.connectAsPrimary(address)` (or the more
   flexible `P4Switch.connect(...).asPrimary()` / `.asSecondary()`).
3. **Push the pipeline** with `bindPipeline(p4info, dc)`. The chain
   returns `P4Switch`, so it composes left-to-right.
4. **Operate** — insert, modify, delete, read, send, receive.
5. **Close.** `try-with-resources` handles it; `close()` is idempotent.

## Where to next

- [Connection and arbitration](connection-and-arbitration.md) — what
  primary / secondary actually mean, and how to handle mastership loss.
- [Tables](tables.md) — the full `TableEntry` / `Match` builder surface,
  including LPM / ternary / range / optional and the read query.
- [Packet I/O](packet-io.md) — three styles for receiving PacketIn,
  sending PacketOut, and the `controller_packet_metadata` round trip.
- [Error handling](error-handling.md) — the four exception types and
  what each one signals.
