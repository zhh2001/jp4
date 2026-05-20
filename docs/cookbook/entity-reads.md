---
title: Reading counters, meters, registers, and action-profile entries
description: Recipe for the v1.4 entity-read families — readCounter / readMeter / readRegister / readActionProfileMember / readActionProfileGroup — with the common query-builder shape, server-side and client-side filters, and the per-entity record fields.
keywords: [jp4, cookbook, counter, meter, register, action-profile, entity read, ReadQuery]
---

# Reading counters, meters, registers, and action-profile entries

**I want to:** read the per-cell values of a counter, meter, or register array on the device, or read the members and groups of an action profile — using the same fluent query-builder shape jp4 uses for table reads.

## The query-builder shape

Every entity-read family on `P4Switch` (v1.4+) follows the same shape:

| Method                          | Returns                          | Server-side filter | Client-side filter | Terminals |
|---------------------------------|----------------------------------|---|---|---|
| `sw.readCounter("name")`        | `CounterReadQuery`               | `.index(long)`        | `.where(Predicate)` | `.all()` / `.one()` / `.stream()` / `.allAsync()` / `.oneAsync()` |
| `sw.readMeter("name")`          | `MeterReadQuery`                 | `.index(long)`        | `.where(Predicate)` | same |
| `sw.readRegister("name")`       | `RegisterReadQuery`              | `.index(long)`        | `.where(Predicate)` | same |
| `sw.readActionProfileMember(n)` | `ActionProfileMemberReadQuery`   | `.memberId(long)`     | `.where(Predicate)` | same |
| `sw.readActionProfileGroup(n)`  | `ActionProfileGroupReadQuery`    | `.groupId(long)`      | `.where(Predicate)` | same |

Learn one shape, use all five.

## Reading counters

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.CounterEntry;

import java.util.List;
import java.util.Optional;

List<CounterEntry> all = sw.readCounter("MyIngress.pkt_counter").all();
for (CounterEntry e : all) {
    System.out.printf("cell %d: packets=%d bytes=%d%n",
            e.index(), e.packetCount(), e.byteCount());
}

// Read one specific cell.
Optional<CounterEntry> cell0 = sw.readCounter("MyIngress.pkt_counter")
        .index(0L)
        .one();

// Only cells that have seen traffic.
List<CounterEntry> nonZero = sw.readCounter("MyIngress.pkt_counter")
        .where(e -> e.packetCount() > 0L)
        .all();
```

`CounterEntry` carries the resolved counter name, cell index, and both `packetCount` and `byteCount` as primitive `long`s. Which value is meaningful is determined by the counter's unit in P4Info (`BYTES` / `PACKETS` / `BOTH`).

## Reading meters

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.entity.MeterEntry;
import io.github.zhh2001.jp4.entity.MeterConfig;
import io.github.zhh2001.jp4.entity.MeterCounterData;

for (MeterEntry e : sw.readMeter("MyIngress.rate_meter").all()) {
    MeterConfig cfg = e.config();
    MeterCounterData cd = e.counterData();
    System.out.printf("cell %d cir=%d cburst=%d green=%d red=%d%n",
            e.index(), cfg.cir(), cfg.cburst(),
            cd.green().packetCount(), cd.red().packetCount());
}
```

`MeterEntry` is a nested record: meter name + index + `MeterConfig` (cir, cburst, pir, pburst, eburst) + `MeterCounterData` (green, yellow, red per-color counter data). The `eburst` field is only used by srTCM meters; trTCM meters surface it as zero.

## Reading registers

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.entity.RegisterEntry;
import p4.v1.P4DataOuterClass.P4Data;

for (RegisterEntry e : sw.readRegister("MyIngress.flow_counters").all()) {
    P4Data datum = P4Data.parseFrom(e.data().toByteArray());
    byte[] value = datum.getBitstring().toByteArray();
    System.out.printf("cell %d value-bytes=%d%n", e.index(), value.length);
}
```

`RegisterEntry.data` carries the **serialised bytes of the wire `p4.v1.P4Data` proto** — that is, what `proto.getData().toByteArray()` returns. The `bit<W>` / `int<W>` case is overwhelmingly common in practice (extract via `P4Data.parseFrom(...).getBitstring()`), but the full P4Data oneof — struct, header, header_stack, enum, error, varbit, bool — is reachable.

## Reading action profile members and groups

<!-- illustrative: concept fragment -->

```java
import io.github.zhh2001.jp4.entity.ActionProfileMember;
import io.github.zhh2001.jp4.entity.ActionProfileGroup;
import io.github.zhh2001.jp4.entity.WeightedMember;

// Members of an action profile.
for (ActionProfileMember m : sw.readActionProfileMember("MyIngress.lb_ap").all()) {
    System.out.printf("member %d → action %s%n",
            m.memberId(), m.action().name());
}

// Groups of an action profile.
for (ActionProfileGroup g : sw.readActionProfileGroup("MyIngress.lb_ap").all()) {
    System.out.printf("group %d maxSize=%d members=%d%n",
            g.groupId(), g.maxSize(), g.members().size());
    for (WeightedMember wm : g.members()) {
        String watch = wm.watchPort() == null
                ? "<unset>"
                : "(" + wm.watchPort().toByteArray().length + " bytes)";
        System.out.printf("  weighted member=%d weight=%d watchPort=%s%n",
                wm.memberId(), wm.weight(), watch);
    }
}
```

`ActionProfileMember.action` is an `ActionInstance` — the same value type used by `TableEntry.action()`, so an action-profile member round-trips into the same shape a direct-action table entry does.

## BMv2 caveat: register reads return UNIMPLEMENTED

BMv2 `simple_switch_grpc` 1.15.1 (the version jp4 tests against) returns `Status{code=UNIMPLEMENTED, description=Register reads are not supported yet}` for the `RegisterEntry` Read RPC. Counter, meter, action-profile, multicast, and clone reads work normally; only register reads hit this. See [Troubleshooting: BMv2 returns UNIMPLEMENTED on register read](/troubleshooting/bmv2-register-unimplemented).

## See also

- [Tables](/guides/tables) — the original entity-read query-builder shape that v1.4 extends.
- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — how each `read*` method maps to its proto entity.
- [Canonical bytestring](/concepts/canonical-bytestring) — every `Bytes`-shaped field in these records obeys the leading-zero stripping rule.
- [v1.3 → v1.4 migration guide](/migrations/migration-1.3-to-1.4) — the surface introduction.
