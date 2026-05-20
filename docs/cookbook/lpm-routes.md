---
title: LPM route table batch installation
description: Recipe for installing many LPM routes in a single P4Runtime Write RPC — build TableEntry instances with Match.lpm, batch them through sw.batch().insert().execute(), and inspect the per-update WriteResult for partial-failure handling.
keywords: [jp4, cookbook, LPM, batch, WriteResult, IPv4 routes, TableEntry, Match.lpm]
---

# LPM route table batch installation

**I want to:** install a routing table of LPM prefixes (e.g. `10.0.x.0/24 → port N`) in a single P4Runtime Write RPC, then read it back to verify, and handle the case where one or more updates were rejected by the device.

## The pattern

<!-- illustrative: trimmed adaptation of examples/simple-loadbalancer/src/main/java/io/github/zhh2001/jp4/examples/loadbalancer/SimpleLoadbalancer.java#installRoutes, edited for didactic clarity -->

```java
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.batch.WriteResult;
import io.github.zhh2001.jp4.batch.UpdateFailure;

try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, deviceConfig)) {

    WriteResult r = sw.batch()
            .insert(routeEntry("10.0.1.0/24", 1))
            .insert(routeEntry("10.0.2.0/24", 2))
            .insert(routeEntry("10.0.3.0/24", 3))
            .execute();

    System.out.printf("installed %d routes (allSucceeded=%s)%n",
            r.submitted(), r.allSucceeded());

    if (!r.allSucceeded()) {
        for (UpdateFailure f : r.failures()) {
            System.err.printf("update[%d] failed: %s %s%n",
                    f.index(), f.code(), f.message());
        }
    }

    // Read-back verification.
    for (TableEntry e : sw.read("MyIngress.backend_lookup").all()) {
        Match m = e.match("hdr.ipv4.dstAddr");
        int port = e.action().paramInt("port");
        System.out.printf("  %s → port %d%n", m, port);
    }
}

private static TableEntry routeEntry(String cidr, int port) {
    return TableEntry.in("MyIngress.backend_lookup")
            .match("hdr.ipv4.dstAddr", Match.lpm(cidr))
            .action("MyIngress.forward").param("port", port)
            .build();
}
```

*Real usage: [`simple-loadbalancer`](https://github.com/zhh2001/jp4/tree/main/examples/simple-loadbalancer/).*

## Walkthrough

1. **Build entries through a helper.** The `routeEntry(cidr, port)` helper keeps the table name, match field name, and action name confined to one site — call sites read as `routeEntry("10.0.1.0/24", 1)`. Misspellings fail at `sw.insert(...)` time with a known-list error message, never reach the device.
2. **`Match.lpm(cidr)` parses CIDR notation.** Equivalent to `new Match.Lpm(Ip4.of("10.0.1.0").toBytes(), 24)` but reads more naturally for IPv4 routes.
3. **`sw.batch().insert(...).insert(...).execute()`** packs multiple `Update`s into one Write RPC. The device applies them in order, but P4Runtime does **not** mandate atomic batches — a failure does not roll back preceding successes.
4. **`WriteResult` carries per-update results.** `allSucceeded()` is true iff `failures()` is empty. Each `UpdateFailure` carries the original batch index, the gRPC `ErrorCode`, and the device's message — enough to log, retry, or modify the offending update.
5. **Read-back verification** through `sw.read("table_name").all()` returns the installed entries in whatever order the device reports them. The `Match` object's `toString()` renders the LPM as `lpm 10.0.1.0/24` for grep-friendly logs.

## Modify and delete patterns

To move a route to a different port:

<!-- illustrative: concept fragment -->

```java
sw.modify(routeEntry("10.0.2.0/24", 4));   // throws if key doesn't exist
```

To remove a route:

<!-- illustrative: concept fragment -->

```java
sw.delete(routeEntry("10.0.2.0/24", 4));   // only match key matters for delete
```

For `delete`, the action half is silently ignored on the wire; pass any value.

## See also

- [Tables](/guides/tables) — the full `Match` builder surface, including LPM / ternary / range / optional.
- [P4Runtime spec mapping](/concepts/p4runtime-spec-mapping) — how `sw.batch().execute()` translates to a single `Write` RPC with multiple `Update`s.
- [Canonical bytestring](/concepts/canonical-bytestring) — why a read-back IP address may have fewer bytes than the written form.
- [`simple-loadbalancer` example](/examples/simple-loadbalancer) — the full runnable program this recipe was extracted from.
