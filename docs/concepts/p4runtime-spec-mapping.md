---
title: P4Runtime spec mapping
description: A cross-reference between jp4's Java surface and the P4Runtime 1.5 protobuf — which Java type wraps which proto message, which method issues which RPC, and which listener delivers which StreamMessageResponse oneof arm.
keywords: [jp4, P4Runtime, spec, proto, mapping, RPC, StreamMessageResponse, p4lang]
---

# P4Runtime spec mapping

jp4 is a thin idiomatic wrapper over the P4Runtime gRPC service. Every
public method on a jp4 type ultimately issues a P4Runtime RPC or
delivers a P4Runtime stream message. This page is the cross-reference
between the two surfaces: the jp4 type or method, the P4Runtime proto
message or field, and the RPC or stream-message arm that connects them.

The mapping below tracks the P4Runtime specification at v1.5 — the
version of `p4lang/p4runtime` vendored unmodified into jp4's `protos/`
directory.

## Client lifecycle

| jp4                                            | P4Runtime                                                       |
|---|---|
| `P4Switch.connect(addr)`                       | gRPC `ManagedChannel` to the device's `P4Runtime` service       |
| `.asPrimary()` / `.asSecondary()`              | `StreamMessageRequest.arbitration` (a `MasterArbitrationUpdate`) |
| `ElectionId.of(low, high)`                     | `MasterArbitrationUpdate.election_id` (`Uint128` proto)         |
| `sw.close()`                                   | `onCompleted` on the StreamChannel; `ManagedChannel.shutdown`   |

`P4Switch.asPrimary()` blocks until the device's
`MasterArbitrationUpdate` reply lands; if the device denies primary
(another client holds a higher election id) the method throws
`P4ArbitrationLost` carrying both election ids.

## Pipeline operations

| jp4                                            | P4Runtime                                                       |
|---|---|
| `sw.bindPipeline(p4info, deviceConfig)`        | `SetForwardingPipelineConfig` with action `VERIFY_AND_COMMIT`   |
| `sw.loadPipeline()`                            | `GetForwardingPipelineConfig` (read-only; works for secondaries)|
| `P4Info.fromFile(path)`                        | parses `p4.config.v1.P4Info` (binary or text protobuf)          |
| `DeviceConfig.Bmv2.fromBytes(bytes)`           | wraps `ForwardingPipelineConfig.p4_device_config` (BMv2 JSON)   |
| `DeviceConfig.Raw.fromBytes(bytes)`            | wraps `ForwardingPipelineConfig.p4_device_config` (any target)  |

The P4Info name index is populated in a single parse pass when the
pipeline binds; subsequent name-based calls resolve through the
in-memory index, not by re-parsing.

## Table operations

| jp4                                              | P4Runtime                                                          |
|---|---|
| `sw.insert(entry)`                               | `Write` with `Update.type = INSERT`, `entity.table_entry = entry`  |
| `sw.modify(entry)`                               | `Write` with `Update.type = MODIFY`                                |
| `sw.delete(entry)`                               | `Write` with `Update.type = DELETE`                                |
| `sw.read("…").all()`                             | `Read` with one `Entity.table_entry` filter; collects rows         |
| `sw.read("…").stream()`                          | `Read`; entries delivered as the gRPC server-stream arrives        |
| `sw.batch().insert(...).insert(...).execute()`   | one `Write` carrying multiple `Update`s                            |
| `TableEntry.in(name)` builder                    | `p4.v1.TableEntry` proto (table_id, match list, action, priority…) |
| `Match.lpm(cidr)` / `Match.Exact(...)` / etc.    | `FieldMatch` proto oneof (`exact` / `lpm` / `ternary` / `range` / `optional`) |

## Entity reads (per-entity-type families)

| jp4                                                              | P4Runtime entity                       |
|---|---|
| `sw.readCounter(name).all()`                                     | `Entity.counter_entry`                 |
| `sw.readMeter(name).all()`                                       | `Entity.meter_entry`                   |
| `sw.readRegister(name).all()`                                    | `Entity.register_entry`                |
| `sw.readActionProfileMember(name).all()`                         | `Entity.action_profile_member`         |
| `sw.readActionProfileGroup(name).all()`                          | `Entity.action_profile_group`          |
| `sw.readMulticastGroup().all()`                                  | `Entity.packet_replication_engine_entry.multicast_group_entry` |
| `sw.readCloneSession().all()`                                    | `Entity.packet_replication_engine_entry.clone_session_entry`   |

Every read query exposes the same five terminals — `all()` / `one()` /
`stream()` / `allAsync()` / `oneAsync()` — plus a non-default `where(Predicate)`
client-side filter. Server-side filters take the entity's natural key
(name + index, member id, group id, etc.) and are also non-default.

## Stream messages

The P4Runtime StreamChannel is a single bidirectional gRPC stream. The
device-to-controller direction carries `StreamMessageResponse`, a
`oneof update` over four arms. jp4 exposes one listener per arm:

| jp4                                                       | StreamMessageResponse oneof arm                    |
|---|---|
| `sw.onMastershipChange(Consumer<MastershipStatus>)`       | `arbitration` (a `MasterArbitrationUpdate` reply)   |
| `sw.onPacketIn(Consumer<PacketIn>)`                       | `packet` (a `PacketIn` proto with payload + metadata)|
| `sw.packetInStream().subscribe(subscriber)`               | same `packet` arm, multi-subscriber fan-out         |
| `sw.pollPacketIn(timeout)`                                | same `packet` arm, blocking poll                    |
| `sw.onDigest(Consumer<DigestEvent>)`                      | `digest` (a `DigestList` collecting one or more entries) |
| `sw.onIdleTimeout(Consumer<IdleTimeoutEvent>)`            | `idle_timeout_notification` (entries that aged out) |
| `sw.onPacketDropped(Consumer<DropEvent>)`                 | (jp4-internal) drops the dispatch path detects      |

`onPacketDropped` is the one listener that does not correspond to a
proto arm — it observes drops that jp4's dispatch detects internally
(`SUBSCRIBER_LAG`, `QUEUE_FULL`, `FILTERED`). See
[Packet I/O](/guides/packet-io) for the full dispatch model.

## Control-plane enable surfaces

| jp4                                                                  | P4Runtime entity      |
|---|---|
| `sw.enableDigest(name, DigestConfig)`                                | `DigestEntry` write   |
| `TableEntryBuilder.idleTimeoutNs(long)`                              | `TableEntry.idle_timeout_ns` (field on the entry) |
| `Connector.packetInFilter(Predicate<? super PacketIn>)`              | (jp4-internal) pre-fan-out filter                  |

Digest emission is opt-in: without `enableDigest` the device emits no
`DigestList` even if `onDigest` is registered. Idle timeout is opt-in
per-entry through the builder. Packet-in filtering runs entirely on the
client side, before any sink sees the parsed PacketIn — useful for
rejecting traffic the application would otherwise observe-and-drop.

## Where the proto sources live

`protos/p4runtime/` (vendored from `p4lang/p4runtime`) carries the
authoritative wire format. The Java-generated classes live under
`p4.v1` / `p4.config.v1`, namespaces matching the proto declarations
exactly. jp4 never modifies those sources — bumps to a newer P4Runtime
version are a verbatim drop-in of the upstream tree.

## See also

- [Canonical bytestring](/concepts/canonical-bytestring) — the
  P4Runtime 1.3+ encoding rule that affects every `Bytes` field on
  the read side.
- [port_kind idiom](/concepts/port-kind-idiom) — how jp4 surfaces the
  `Replica.port_kind` oneof and the `WeightedMember.watch_kind` oneof.
- [Threading model](/concepts/threading-model) — which executor runs
  each listener and which thread is safe for which call.
