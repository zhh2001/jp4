# Internal engineering notes

This file is for decisions and observations that will eventually find their way into
a public README, but for now live close to the code so contributors don't lose them.

## JDK 24+ and `grpc-netty-shaded`

`grpc-netty-shaded` (Netty 4.x repackaged) touches two JVM features that JDK 24 and
later treat as restricted:

1. **`sun.misc.Unsafe.allocateMemory`** — used by Netty's `PlatformDependent0` for
   off-heap buffers. JDK 24 surfaces a "terminally deprecated" warning; some future
   release will block the call.
2. **`System.loadLibrary`** — used by Netty's `NativeLibraryUtil` to load the bundled
   epoll JNI library. JDK 24 surfaces a "restricted method called" warning.

### Classification

On the JDK 25 reference machine these are **banner warnings only**: the calls succeed,
the epoll library loads, and gRPC channels function normally. End-to-end smoke covered
by `JdkNettyCompatibilityTest` (a real `NettyChannelBuilder` channel issuing one RPC
to a closed port and getting `UNAVAILABLE`).

### Mitigation in this repo

`tasks.test` in `build.gradle.kts` passes `--enable-native-access=ALL-UNNAMED`, which
acknowledges the access at JVM startup and silences the second warning. The `Unsafe`
warning is upstream-Netty's to fix; until then it is benign.

### Recommendation for downstream users

Apps that depend on jp4 should add the same JVM flag to their own runtime when on
JDK 24+:

```sh
java --enable-native-access=ALL-UNNAMED -jar my-controller.jar
```

This will be folded into the official README in Phase 10.

## CI workflow uses Node 20 actions (deprecated)

`.github/workflows/ci.yml` references `actions/checkout@v4`, `actions/setup-java@v4`,
and `gradle/actions/setup-gradle@v4`. All three are still on Node.js 20 internally,
which GitHub has marked deprecated. The runner emits a non-blocking warning on every
run today.

### Timeline

- **2026-06-02** — GitHub will start enforcing Node 24 for actions that have a Node 24
  release available; the deprecated-warning-only path ends here.
- **2026-09-16** — GitHub removes Node 20 from runners entirely; any action still on
  Node 20 stops working.

### Mitigation (deferred to Phase 11)

When the upstream actions ship Node 24-compatible major versions, bump the pinned
versions in the matrix step. Likely candidates by then: `@v5` for one or more of these.
Track the action repos a few weeks before the 2026-06-02 cutoff. No code change here
yet — current pins are functional.

## BMv2 does not validate p4info / deviceConfig consistency

BMv2's PI library accepts a `SetForwardingPipelineConfig` request even when the
supplied {@code p4info} and {@code p4_device_config} describe different P4 programs
— it does not perform the cross-check the P4Runtime spec recommends (the spec
language is "should", not "must", on this point). Verified experimentally during
Phase 5 by pushing `basic.p4info.txtpb` together with `alt.json`: BMv2 returned
`OK` on the RPC.

### Why this matters

A controller that pushes a wrong combination can install a pipeline that "works"
at the device but produces incorrect runtime behaviour (table ids in entries
won't match the bytecode). The controller would only notice at first table-write
time, when the device rejects the entry as invalid for the current pipeline.

### jp4's position

The library does not perform a consistency check itself; it relays the device's
response. Any verification that needs to happen before pushing is the controller
author's responsibility. {@code P4Switch.bindPipeline}'s JavaDoc warns about this
explicitly so the contract is visible at the call site.

### What about other targets?

Tofino's reference SDE is reported to perform the cross-check. Stratum may or may
not, depending on which target it wraps. We deliberately do not encode any
target-specific assumption in jp4 — the user supplies p4info and deviceConfig,
the device decides.

## BMv2 Docker mastership transition behaviour

When two clients arbitrate for primary on the same device against the Docker
BMv2 image (`p4lang/behavioral-model`), the mastership transition occasionally
surfaces as a stream error on the new client's StreamChannel — typically observed
as a gRPC `StatusRuntimeException` on the second client's first
`MasterArbitrationUpdate` response path, even though the new client should have
become primary cleanly. Verified experimentally during Phase 5 CI runs against
`p4lang/behavioral-model:latest`: the failure rate is on the order of 1 in 3
repetitions of an A→B preemption test.

This behaviour does not appear with native BMv2 (locally compiled
`simple_switch_grpc`), which suggests a difference in the PI library's internal
mastership-change notification path between BMv2 build configurations. The
P4Runtime spec does not require that the new primary's first arbitration RPC
succeed without stream interruption, so the device behaviour is technically
spec-compliant.

### jp4's position

The connector layer does <b>not</b> work around this. The library relays the
device's response and reports `P4ConnectionException` on stream error, which is
what the spec describes.

Tests that exercise the "second client takes primary" pattern wrap the new
client's connect in `BMv2TestSupport.connectPrimaryWithRetry(addr, eid, 3)`
(3 attempts, 200 ms back-off). The retry helper is in `testsupport/` only;
no production code path uses it.

### Recommendation for downstream users

Production controllers running against Docker BMv2 should consider similar retry
logic around `P4Switch.connect`. Note that the existing connector-level
`reconnectPolicy` covers the <i>stream-level</i> reconnect path (broken stream
on an already-arbitrated switch) and does NOT apply to the initial connect; the
two retry concerns are independent. A future jp4 release may add an
initial-connect retry option if real-world demand surfaces.

## Match subclass construction: direct new vs factory method

`TableEntryBuilder` uses `new Match.Exact(...)` directly when wrapping bare match
values, rather than going through `Match.exact(...)` static factories. This avoids
one level of indirection but couples `TableEntryBuilder`'s source to all current
`Match` constructors directly.

If future versions add preprocessing logic to `Match` subclass construction (e.g.
canonical-form normalization, bitWidth-aware truncation), the call sites in
`TableEntryBuilder` need to be updated in lockstep. A safer alternative is to
refactor all internal callers to use the static factories `Match.exact(...)`,
`Match.lpm(...)`, etc. Recorded here so the trade-off is not silently lost.

