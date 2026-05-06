# jp4 examples

Three runnable examples that exercise the jp4 v0.1 API against a real BMv2
device. Each example is its own Gradle module under this directory; they
consume the in-tree jp4 main artifact via a [composite
build](https://docs.gradle.org/current/userguide/composite_builds.html), so
running an example always reflects the live source you have checked out.

## At a glance

| Example | Difficulty | API surface used | What you'll see |
|---|---|---|---|
| [simple-l2-switch](simple-l2-switch/) | beginner | connect + bindPipeline + insert + onPacketIn + send | A learning L2 switch — the controller learns MAC ↔ port from PacketIn and writes forwarding entries; missed packets get flooded via PacketOut. |
| [simple-loadbalancer](simple-loadbalancer/) | beginner | bindPipeline + batch + read + modify + LPM match | A static LPM-based router: install three /24 routes in a single batch, read them back, modify one at runtime. |
| [network-monitor](network-monitor/) | intermediate | primary + secondary + Flow.Publisher + loadPipeline | Two-tier observer: a primary owns the device, a secondary subscribes to `packetInStream()` and tallies per-port stats. Demonstrates the read-without-primary contract from spec §6.4. |

Pick **simple-l2-switch** if this is your first jp4 example. It uses the
single-controller callback pattern that 90% of starter P4Runtime apps use.

Pick **simple-loadbalancer** if you want to see batch / read / modify and
how to read entries back from the device.

Pick **network-monitor** if your real use case is observation rather than
control — multi-controller HA topology is a production reality the secondary
pattern speaks to directly.

## Prerequisites (for any example)

- **Java 21+** (the project's main build uses 21; the examples follow).
- **Docker** with the official `p4lang/behavioral-model` image reachable.
  No mininet, tcpreplay, or external traffic generators required — every
  example uses jp4's own `sw.send(...)` to drive its data plane for
  self-contained reproducibility.

### Starting BMv2

A single `docker run` is enough. The example controllers connect to whatever
address you tell them (default `127.0.0.1:50051`):

```bash
docker run --rm -d --name jp4-bmv2 \
    -p 50051:50051 \
    p4lang/behavioral-model@sha256:7f28ab029368a1749a100c37ca4eaa6861322abb89885cfebb5c316326a45247 \
    simple_switch_grpc \
        --no-p4 --device-id 0 --log-console -L info \
        -i 0@lo \
        -- --grpc-server-addr 0.0.0.0:50051 --cpu-port 255
```

The image is pinned to the same digest jp4's CI runs against — see
`NOTES.md` "Docker BMv2 image tag pinning" in the main project for why
digest pinning is the safer choice over the moving `:latest` tag.

The `--cpu-port 255` flag is required by all three example pipelines:
each P4 program sends packets to the controller via `std.egress_spec = 255`,
and BMv2 wraps that as a StreamChannel `PacketIn` only when the cpu-port
flag points it at port 255.

When you're done:

```bash
docker rm -f jp4-bmv2
```

## Running an example

From this directory:

```bash
./gradlew :simple-l2-switch:run
./gradlew :simple-loadbalancer:run
./gradlew :network-monitor:run
```

To override the BMv2 address (e.g. for a remote BMv2):

```bash
./gradlew :simple-l2-switch:run --args="bmv2.example.com:50051"
```

The first run pulls the example's transitive jp4 dependency tree (gRPC,
Netty, protobuf) — typically a few hundred MB into the local Gradle cache.
Subsequent runs are fast.

## Repository layout

```
examples/
├── README.md                 # this file
├── settings.gradle.kts       # composite build root; includeBuild("..")
├── build.gradle.kts          # common application-plugin config
├── simple-l2-switch/
│   ├── README.md
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/io/github/zhh2001/jp4/examples/l2switch/SimpleL2Switch.java
│       └── resources/p4/{simple_l2.p4, simple_l2.json, simple_l2.p4info.txtpb}
├── simple-loadbalancer/      # same layout
└── network-monitor/          # same layout
```

The examples directory is **not** part of the main project's build —
running `../gradlew build` at the repository root never touches anything
under `examples/`. The composite build's `includeBuild("..")` substitution
ensures examples consume the live jp4 source without going through Maven
Local.
