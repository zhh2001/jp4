---
title: API reference
description: Categorized navigation of the jp4 v1.5 public surface — client lifecycle, pipeline, entity records, match types, read queries, stream messages, batch operations, exceptions, and value types — with one-line descriptions and direct links to the standard Javadoc for each class.
keywords: [jp4, API reference, Javadoc, P4Switch, P4Runtime, Java, public surface, classes, records]
---

# API reference

The complete jp4 public surface as of v1.5, organized into categories. Each entry links to the standard Javadoc HTML for the class. The Javadoc itself lives at [/api/javadoc/](/api/javadoc/) and is generated from the source by the standard `javadoc` tool — the authoritative source of truth for every method signature, return type, exception list, and `@since` tag.

For task-oriented guidance see [Cookbook](/cookbook/l2-learning) and [Guides](/guides/getting-started). For deeper background on cross-cutting topics see [In-depth concepts](/concepts/p4runtime-spec-mapping).

## Client lifecycle

| Class | What it does |
|---|---|
| [`P4Switch`](/api/javadoc/io/github/zhh2001/jp4/P4Switch.html) | Single client connection to one P4Runtime device. The main entry class for every jp4 program. |
| [`Connector`](/api/javadoc/io/github/zhh2001/jp4/Connector.html) | Fluent builder for opening a `P4Switch` connection: address, deviceId, electionId, reconnectPolicy, primary or secondary role. |
| [`ReconnectPolicy`](/api/javadoc/io/github/zhh2001/jp4/ReconnectPolicy.html) | Auto-reconnect configuration — `noRetry()` (default) or `exponentialBackoff(initial, max, retries)`. |
| [`ElectionId`](/api/javadoc/io/github/zhh2001/jp4/types/ElectionId.html) | Unsigned 128-bit P4Runtime election id (low + high pair). `of(long)` shorthand for low-only ids. |
| [`MastershipStatus`](/api/javadoc/io/github/zhh2001/jp4/MastershipStatus.html) | Sealed parent for the `Acquired` / `Lost` events delivered to `onMastershipChange` listeners. |
| [`MastershipStatus.Acquired`](/api/javadoc/io/github/zhh2001/jp4/MastershipStatus.Acquired.html) | Record signaling this client became primary; carries `ourElectionId`. |
| [`MastershipStatus.Lost`](/api/javadoc/io/github/zhh2001/jp4/MastershipStatus.Lost.html) | Record signaling this client is not primary; carries `previousElectionId` (nullable) and `currentPrimaryElectionId` (nullable). |

## Pipeline

| Class | What it does |
|---|---|
| [`Pipeline`](/api/javadoc/io/github/zhh2001/jp4/Pipeline.html) | Bound pipeline view exposing the parsed `P4Info` plus the device-config descriptor. |
| [`P4Info`](/api/javadoc/io/github/zhh2001/jp4/pipeline/P4Info.html) | Schema wrapper over `p4.config.v1.P4Info` — name index for tables, actions, fields, entities, controller-metadata. |
| [`DeviceConfig`](/api/javadoc/io/github/zhh2001/jp4/pipeline/DeviceConfig.html) | Sealed parent for the two device-config variants. |
| [`DeviceConfig.Bmv2`](/api/javadoc/io/github/zhh2001/jp4/pipeline/DeviceConfig.Bmv2.html) | BMv2-flavoured JSON device config; `fromFile(Path)` and `fromBytes(byte[])` factories. |
| [`DeviceConfig.Raw`](/api/javadoc/io/github/zhh2001/jp4/pipeline/DeviceConfig.Raw.html) | Bytes-only escape hatch for non-BMv2 targets. |
| [`PipelineAction`](/api/javadoc/io/github/zhh2001/jp4/pipeline/PipelineAction.html) | Verb selector for `SetForwardingPipelineConfig` (default `VERIFY_AND_COMMIT`). |

### Schema accessors (sub-types of `P4Info`)

| Class | What it does |
|---|---|
| [`TableInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/TableInfo.html) | Resolved table metadata: id, match fields, action set, size. |
| [`MatchFieldInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MatchFieldInfo.html) | Match field metadata: name, kind, bit width. |
| [`MatchFieldInfo.Kind`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MatchFieldInfo.Kind.html) | Enum: `EXACT` / `LPM` / `TERNARY` / `RANGE` / `OPTIONAL`. |
| [`ActionInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/ActionInfo.html) | Resolved action metadata: id, parameter list. |
| [`ActionParamInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/ActionParamInfo.html) | Action parameter metadata: name, bit width. |
| [`CounterInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/CounterInfo.html) / [`Unit`](/api/javadoc/io/github/zhh2001/jp4/pipeline/CounterInfo.Unit.html) | Counter declaration with unit (`BYTES` / `PACKETS` / `BOTH` / `UNSPECIFIED`). |
| [`MeterInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MeterInfo.html) / [`Unit`](/api/javadoc/io/github/zhh2001/jp4/pipeline/MeterInfo.Unit.html) | Meter declaration with unit (`BYTES` / `PACKETS` / `UNSPECIFIED`). |
| [`RegisterInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/RegisterInfo.html) | Register declaration: id, name, size. |
| [`ActionProfileInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/ActionProfileInfo.html) | Action profile declaration with table-id set the profile is referenced from. |
| [`PacketMetadataInfo`](/api/javadoc/io/github/zhh2001/jp4/pipeline/PacketMetadataInfo.html) | `controller_packet_metadata` field declaration. |

## Match types

| Class | What it does |
|---|---|
| [`Match`](/api/javadoc/io/github/zhh2001/jp4/match/Match.html) | Sealed parent; static `lpm(cidr)` and `exact(value)` shorthands. |
| [`Match.Exact`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Exact.html) | Exact-match key (`value`). |
| [`Match.Lpm`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Lpm.html) | Longest-prefix-match key (`value`, `prefixLen`). |
| [`Match.Ternary`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Ternary.html) | Ternary key (`value`, `mask`). |
| [`Match.Range`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Range.html) | Range key (`low`, `high`). |
| [`Match.Optional`](/api/javadoc/io/github/zhh2001/jp4/match/Match.Optional.html) | Optional key (`value`; null-safe wildcard). |

## Entity records

Records carrying typed data for each P4Runtime entity type.

| Class | What it does |
|---|---|
| [`TableEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/TableEntry.html) | Table entry record; `in(name)` builder entry point. |
| [`TableEntryBuilder`](/api/javadoc/io/github/zhh2001/jp4/entity/TableEntryBuilder.html) | Fluent builder for `TableEntry` — `match`, `action`, `priority`, `idleTimeoutNs`. |
| [`ActionInstance`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionInstance.html) | Named action with parameter map; reachable from `TableEntry.action()`, `ActionProfileMember.action()`. |
| [`ActionBuilder`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionBuilder.html) | Fluent builder for the `.action(name).param(name, value)` chain. |
| [`CounterEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/CounterEntry.html) | One counter cell — name, index, packetCount, byteCount. |
| [`CounterData`](/api/javadoc/io/github/zhh2001/jp4/entity/CounterData.html) | Per-color packet/byte counter (used by `MeterCounterData`). |
| [`MeterEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/MeterEntry.html) | One meter cell — name, index, config, per-color counter data. |
| [`MeterConfig`](/api/javadoc/io/github/zhh2001/jp4/entity/MeterConfig.html) | Meter rate config — cir, cburst, pir, pburst, eburst. |
| [`MeterCounterData`](/api/javadoc/io/github/zhh2001/jp4/entity/MeterCounterData.html) | Per-color cumulative counters (green, yellow, red). |
| [`RegisterEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/RegisterEntry.html) | One register cell — name, index, serialised `P4Data` bytes. |
| [`ActionProfileMember`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionProfileMember.html) | Action-profile member — profile name, member id, action. |
| [`ActionProfileGroup`](/api/javadoc/io/github/zhh2001/jp4/entity/ActionProfileGroup.html) | Action-profile group — profile name, group id, max size, weighted members. |
| [`WeightedMember`](/api/javadoc/io/github/zhh2001/jp4/entity/WeightedMember.html) | Group member — id, weight, nullable watch port. |
| [`MulticastGroupEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/MulticastGroupEntry.html) | Multicast group — id, replicas, metadata. |
| [`CloneSessionEntry`](/api/javadoc/io/github/zhh2001/jp4/entity/CloneSessionEntry.html) | Clone session — id, replicas, classOfService, packetLengthBytes. |
| [`Replica`](/api/javadoc/io/github/zhh2001/jp4/entity/Replica.html) | Per-port fan-out slot — nullable port, instance, backup-replica list. |
| [`BackupReplica`](/api/javadoc/io/github/zhh2001/jp4/entity/BackupReplica.html) | Backup replica — non-null port, instance. |

## Read queries

Typed query builders for each entity-read family. All seven share the same shape: a server-side filter, a `where(Predicate)` client-side filter, and five terminals (`all` / `one` / `stream` / `allAsync` / `oneAsync`).

| Class | Server-side filter |
|---|---|
| [`ReadQuery`](/api/javadoc/io/github/zhh2001/jp4/ReadQuery.html) | Table reads: `match(field, MatchKind)` |
| [`CounterReadQuery`](/api/javadoc/io/github/zhh2001/jp4/CounterReadQuery.html) | `index(long)` |
| [`MeterReadQuery`](/api/javadoc/io/github/zhh2001/jp4/MeterReadQuery.html) | `index(long)` |
| [`RegisterReadQuery`](/api/javadoc/io/github/zhh2001/jp4/RegisterReadQuery.html) | `index(long)` |
| [`ActionProfileMemberReadQuery`](/api/javadoc/io/github/zhh2001/jp4/ActionProfileMemberReadQuery.html) | `memberId(long)` |
| [`ActionProfileGroupReadQuery`](/api/javadoc/io/github/zhh2001/jp4/ActionProfileGroupReadQuery.html) | `groupId(long)` |
| [`MulticastGroupReadQuery`](/api/javadoc/io/github/zhh2001/jp4/MulticastGroupReadQuery.html) | `groupId(long)` |
| [`CloneSessionReadQuery`](/api/javadoc/io/github/zhh2001/jp4/CloneSessionReadQuery.html) | `sessionId(long)` |

## Stream messages

Records delivered to `onPacketIn` / `onDigest` / `onIdleTimeout` / `onPacketDropped` listeners, plus the outbound `PacketOut` builder.

| Class | What it does |
|---|---|
| [`PacketIn`](/api/javadoc/io/github/zhh2001/jp4/entity/PacketIn.html) | Inbound packet — payload, controller-metadata field accessors (`metadata(name)`, `metadataInt(name)`, `metadataLong(name)`). |
| [`PacketOut`](/api/javadoc/io/github/zhh2001/jp4/entity/PacketOut.html) | Outbound packet record. |
| [`PacketOut.Builder`](/api/javadoc/io/github/zhh2001/jp4/entity/PacketOut.Builder.html) | Fluent builder — `payload(...)`, `metadata(name, value)`. |
| [`DigestEvent`](/api/javadoc/io/github/zhh2001/jp4/entity/DigestEvent.html) | Inbound `DigestList` — name, list id, per-entry payloads, timestamp, digest id. |
| [`DigestConfig`](/api/javadoc/io/github/zhh2001/jp4/entity/DigestConfig.html) | Outbound `DigestEntry.Config` — maxTimeout, maxListSize, ackTimeout. |
| [`IdleTimeoutEvent`](/api/javadoc/io/github/zhh2001/jp4/entity/IdleTimeoutEvent.html) | Inbound `IdleTimeoutNotification` — list of expired `TableEntry`s, timestamp. |
| [`DropEvent`](/api/javadoc/io/github/zhh2001/jp4/entity/DropEvent.html) | Internal drop event — reason, timestamp, parsed PacketIn (if any), message. |
| [`DropEvent.Reason`](/api/javadoc/io/github/zhh2001/jp4/entity/DropEvent.Reason.html) | Enum: `SUBSCRIBER_LAG` / `QUEUE_FULL` / `FILTERED`. |

## Batch operations

| Class | What it does |
|---|---|
| [`BatchBuilder`](/api/javadoc/io/github/zhh2001/jp4/BatchBuilder.html) | Multi-update batch — `insert(e)` / `modify(e)` / `delete(e)` / `execute()`. |
| [`WriteResult`](/api/javadoc/io/github/zhh2001/jp4/WriteResult.html) | Result of `BatchBuilder.execute()` — submitted count, per-update failures. |
| [`UpdateFailure`](/api/javadoc/io/github/zhh2001/jp4/entity/UpdateFailure.html) | One per rejected update — batch index, error code, message. |

## Exceptions

All extend `P4RuntimeException`; the hierarchy maps to where the failure happened.

| Class | What it signals |
|---|---|
| [`P4RuntimeException`](/api/javadoc/io/github/zhh2001/jp4/error/P4RuntimeException.html) | Sealed parent. |
| [`P4ConnectionException`](/api/javadoc/io/github/zhh2001/jp4/error/P4ConnectionException.html) | Transport / mastership / closed-switch failures. |
| [`P4ArbitrationLost`](/api/javadoc/io/github/zhh2001/jp4/error/P4ArbitrationLost.html) | Specialised — primary denied (connect or re-claim). |
| [`P4PipelineException`](/api/javadoc/io/github/zhh2001/jp4/error/P4PipelineException.html) | P4Info / device-config / schema problems. |
| [`P4OperationException`](/api/javadoc/io/github/zhh2001/jp4/error/P4OperationException.html) | Device-side rejection of a Write or Read RPC. |
| [`ErrorCode`](/api/javadoc/io/github/zhh2001/jp4/error/ErrorCode.html) | Enum mirroring the gRPC canonical codes (`OK` / `INVALID_ARGUMENT` / `NOT_FOUND` / `ALREADY_EXISTS` / `UNIMPLEMENTED` / ...). |
| [`OperationType`](/api/javadoc/io/github/zhh2001/jp4/error/OperationType.html) | Enum on `P4OperationException` — `INSERT` / `MODIFY` / `DELETE` / `READ`. |

## Value types

Small primitive wrappers used across the API.

| Class | What it does |
|---|---|
| [`Bytes`](/api/javadoc/io/github/zhh2001/jp4/types/Bytes.html) | Canonical-bytestring container; `of(byte[])` constructor, `equals`/`hashCode` aware of the leading-zero convention. |
| [`Mac`](/api/javadoc/io/github/zhh2001/jp4/types/Mac.html) | 48-bit MAC address; `fromBytes(byte[])` and `ZERO` constant. |
| [`Ip4`](/api/javadoc/io/github/zhh2001/jp4/types/Ip4.html) | IPv4 address; `of(String)` parses dotted-quad, `toBytes()` returns 4-byte. |
| [`Ip6`](/api/javadoc/io/github/zhh2001/jp4/types/Ip6.html) | IPv6 address; `of(String)` parses colon-hex, `toBytes()` returns 16-byte. |
| [`ElectionId`](/api/javadoc/io/github/zhh2001/jp4/types/ElectionId.html) | Unsigned 128-bit id; see *Client lifecycle*. |

## Authoritative reference

Everything above links to the standard Javadoc, which is generated from the source by the `javadoc` tool with the project's standard tag conventions (`@since 1.0.0` / `@since 1.5.0` markers indicate when each class or method was added; `@implNote` for non-normative implementation details). For the Maven Central jar coordinate see the [Quick start](/quickstart).
