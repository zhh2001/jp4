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

Documented in `CHANGELOG.md`'s "Known issues" section for v0.1.0.

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

## BMv2 partial-failure response format

When a multi-update `WriteRequest` contains both successful and failing updates,
BMv2's PI library returns a gRPC `StatusRuntimeException` whose
`google.rpc.Status.details` carry **one `p4.v1.Error` per update**, including
the updates BMv2 accepted (`canonical_code = OK`, empty `message`). Empirics
recorded against `simple_switch_grpc` (Phase 6B): a 3-update batch with a
duplicate-key insert at index 0 returned `[ALREADY_EXISTS, OK, OK]`, with the
two OK updates actually applied to the table.

`P4Switch.mapWriteFailure` filters out the `OK` entries when building
`WriteResult.failures`, so per-update attribution stays accurate:
`failures.size()` reflects only the actually-rejected updates and each
`UpdateFailure.index()` points back to the original `Update` position.

Two consequences for downstream users:

1. **Partial-success is real**: BMv2 does not perform atomic batches.
   Updates that did not appear in `WriteResult.failures` were applied to the
   device. Production code should not assume "if any failed, none was applied"
   and should consult `failures` to know which entries to retry.
2. **Other targets may behave differently**: spec-compliant devices are
   permitted to return only a top-level status without per-update details, in
   which case `WriteResult.failures` would be empty even though the RPC
   failed. jp4 currently treats this case as "swallowed" (the
   `P4OperationException` is built with an empty failures list and
   `BatchBuilderImpl.execute()` returns a `WriteResult` whose
   `allSucceeded()` is `true`). If a real-world target surfaces this shape,
   add a top-level marker to `WriteResult` and tighten `allSucceeded()`.

## BMv2 read filter semantics

P4Runtime spec §6.4 describes the match-field set on a `ReadRequest`
`TableEntry` as a server-side filter: only entries that match the supplied
fields should be returned. BMv2's PI library implements this strictly.
Empirics recorded against `simple_switch_grpc` (Phase 6C): a table holding
two `MyIngress.ipv4_lpm` entries (`10.16.7.0/24` and `10.16.8.0/24`), read
with `.match("hdr.ipv4.dstAddr", new Match.Lpm(10.16.7.0, /24))`, returned
exactly **one** entry — the requested key only:

```
[read-filter-empirics] sent_lpm=10.16.7.0/24 table_size_after_writes=2 returned_count=1
[read-filter-empirics]   entry: dstAddr=Bytes(0x0a100700)/24
```

Empty match list = "all entries from the table" — also as spec describes.
jp4 always serialises the spec-correct ReadRequest; the device-side filter
behaviour determines what comes back. ReadRpcTest asserts the looser
invariant ("the asked-for entry is present in the result") so the test
remains correct against targets that do client-side filtering only.

## BMv2 read response chunking

A 100-entry table read via `.stream()` against BMv2 surfaces as one or more
`ReadResponse` chunks; the user-facing `Stream<TableEntry>` flattens them
transparently via `ReadQueryImpl.flatten`. `ReadStreamCloseTest` verifies
that closing the stream early via `try-with-resources` (after
`.limit(3).toList()`) propagates the cancel through `io.grpc.Context` to
the underlying `ClientCall`, leaves no in-flight state, and a follow-up
`.all()` on the same switch returns the full table — i.e. the channel is
not stuck after a mid-stream cancel.

## BMv2 PacketIn delivery is primary-only

P4Runtime spec §16.1 says PacketIn **MUST** be sent to the primary
controller client and **SHOULD** be sent to backups. BMv2's PI library
implements only the MUST: PacketIns are delivered to the primary
client's StreamChannel and not broadcast to secondaries.

Empirics recorded against `simple_switch_grpc` (Phase 10): a primary at
`election_id=10` and a secondary at `election_id=1` are both connected
to the same device; the primary injects 30 PacketOuts that loop back as
30 PacketIns. The primary's subscriber observes all 30; the secondary's
subscriber observes 0.

Implication for jp4 users: when designing an HA topology with a
secondary observer, do not assume PacketIn arrives on backup clients
without verifying the target's behaviour. BMv2 doesn't broadcast;
some Tofino / Stratum builds are expected to. The `network-monitor`
example places its `packetInStream()` subscriber on the primary
connection for this reason and explains the trade-off in its README.

This is target-side behaviour, not a jp4 limitation. jp4's
`packetInStream()` and `pollPacketIn(...)` work on any client (primary
or secondary) — whether they actually receive packets depends on what
the device sends.

## Docker BMv2 image tag pinning

`DockerBackend.CANDIDATE_IMAGES` pins `p4lang/behavioral-model` to a
specific manifest digest (the `:latest` content as of 2026-05-05) rather
than tracking the mutable `:latest` tag. p4lang does not publish dated
immutable tags, so digest pinning is the only stable reference Docker Hub
offers for this image. Pinning eliminates "image drift between CI runs"
as a variable; the residual non-overlapping flake observed on JDK 25 +
Docker before pinning was therefore more likely a JVM-scheduling /
gRPC-Netty interaction than an image-rebuild artefact, but the pin
should be in place before that hypothesis can be ruled in or out.

The mutable `:latest` tag remains in the candidate list as a fallback
in case the pinned digest is later pruned from Docker Hub. The
`opennetworkinglab/p4mn:latest` entry stays as a last-resort fallback
(different image, different lineage). The `:no-bmv2` entry was removed —
that tag has never existed on Docker Hub, so it had been dead
configuration since the multi-image fallback was introduced in Phase 4D.

To rotate the pin (e.g. when a known BMv2 fix lands or the digest is
pruned):

```bash
# Inspect the current :latest manifest list digest
docker manifest inspect p4lang/behavioral-model:latest \
  | grep -A1 '"mediaType":' | head

# Or via Docker Hub API (no docker CLI required)
curl -s 'https://hub.docker.com/v2/repositories/p4lang/behavioral-model/tags/latest' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["digest"])'
```

Replace the `@sha256:…` value in `DockerBackend.CANDIDATE_IMAGES` and
push to a branch to validate via CI before merging the rotation. Do not
rotate without a CI green run — the new digest may have a behavioural
difference that affects existing tests.

