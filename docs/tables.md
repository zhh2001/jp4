# Tables

Table operations are most of what a P4Runtime controller does. jp4
exposes `insert` / `modify` / `delete` for single updates, `batch()` for
multi-update RPCs, and `read(...)` for queries. Match keys are
constructed via a fluent builder; the five P4Runtime match kinds (exact,
LPM, ternary, range, optional) are a sealed type, so a `switch` over
them is exhaustive at compile time.

This guide covers the full table API surface. Snippets are taken from
the integration tests and `examples/` modules; everything compiles and
runs.

## Building a `TableEntry`

<!-- illustrative -->

```java
TableEntry e = TableEntry.in("MyIngress.ipv4_lpm")
        .match("hdr.ipv4.dstAddr", new Match.Lpm(Ip4.of("10.0.1.0").toBytes(), 24))
        .action("MyIngress.forward").param("port", 1)
        .build();
```

*Real usage: [`simple-loadbalancer`](../examples/simple-loadbalancer/).*

The builder is name-based — table, match field, action, and param all
take strings that must match the bound P4Info. Misspellings fail at
`switch.insert(e)` time with a known-list message; the builder itself
performs no validation.

`TableEntry` is immutable. The same instance is safe to insert into
multiple switches that share a pipeline, or to re-use as a cleanup
template for `delete`:

<!-- illustrative: concept fragment -->

```java
sw.insert(e);
// ...
sw.delete(e);   // same builder result, safe to reuse
```

For `delete`, only the match key matters; any action half is silently
ignored on the wire.

A common controller pattern is to wrap the builder in a small static
helper so the construction site stays one line. The
`simple-loadbalancer` example does this for IPv4 LPM routes:

<!-- snippet: examples/simple-loadbalancer/src/main/java/io/github/zhh2001/jp4/examples/loadbalancer/SimpleLoadbalancer.java#routeEntry -->

```java
private static TableEntry routeEntry(String cidr, int port) {
    String[] parts = cidr.split("/");
    Ip4 prefix = Ip4.of(parts[0]);
    int prefixLen = Integer.parseInt(parts[1]);
    return TableEntry.in("MyIngress.backend_lookup")
            .match("hdr.ipv4.dstAddr", new Match.Lpm(prefix.toBytes(), prefixLen))
            .action("MyIngress.forward").param("port", port)
            .build();
}
```

Call sites read as `routeEntry("10.0.1.0/24", 1)` — the table name,
match-field name, and action name stay confined to the helper.

## Match kinds

`Match` is sealed with five variants. Most controllers only construct
two or three of them:

<!-- illustrative: concept fragment -->

```java
new Match.Exact(value)                                // exact match
new Match.Lpm(prefix, prefixLen)                      // longest-prefix match
new Match.Ternary(value, mask)                        // ternary
new Match.Range(low, high)                            // range
new Match.Optional(value)                             // optional (null-safe wildcard)
```

The builder's `match(name, value)` overload accepts any of `Bytes`, `Mac`,
`Ip4`, `Ip6`, `byte[]`, `int`, `long` and wraps it as `Match.Exact`
automatically. Pass an explicit `Match.Lpm/Ternary/Range/Optional` to
choose a different kind. Negative `int`/`long` is rejected with
`IllegalArgumentException` to catch sign-bit confusion; pass `byte[]`
or `Bytes` for an explicit bit pattern.

When *consuming* an entry read back from the device, exhaustive `switch`
gives you compile-time coverage:

<!-- illustrative: concept fragment -->

```java
Match m = entry.match("hdr.ipv4.dstAddr");
String description = switch (m) {
    case Match.Exact e    -> "exact " + e.value();
    case Match.Lpm l      -> "lpm "   + l.value() + "/" + l.prefixLen();
    case Match.Ternary t  -> "ternary " + t.value() + "&" + t.mask();
    case Match.Range r    -> "range " + r.low() + ".." + r.high();
    case Match.Optional o -> "optional " + o.value();
};
```

The `null`-on-absent contract: `entry.match("not_in_this_entry")` returns
`null`, not an empty Optional — the field-set varies per table, and most
matches against an entry start with "is this field part of the key?".

## Single writes

`insert` / `modify` / `delete` are blocking. Each returns `void` on
success and throws on failure:

<!-- illustrative: concept fragment -->

```java
sw.insert(e);    // throws P4OperationException with ALREADY_EXISTS if the key exists
sw.modify(e);    // throws if the key does NOT exist
sw.delete(e);    // throws if the key does NOT exist
```

The `*Async` variants return `CompletableFuture<Void>`:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(e).thenRun(() -> log("ok"))
                 .exceptionally(t -> { logError(t); return null; });
```

Validation failures (unknown field name, value too wide, action not in
table's action set) surface through the future just like RPC failures —
async methods never throw on the calling thread for a problem they
detect after returning. Sync wrappers unwrap the future and rethrow.

## Batches

Multiple updates in one Write RPC:

<!-- illustrative -->

```java
WriteResult r = sw.batch()
        .insert(routeEntry("10.0.1.0/24", 1))
        .insert(routeEntry("10.0.2.0/24", 2))
        .insert(routeEntry("10.0.3.0/24", 3))
        .execute();

System.out.printf("installed %d routes (allSucceeded=%s)%n",
        r.submitted(), r.allSucceeded());
```

*Real usage: [`simple-loadbalancer`](../examples/simple-loadbalancer/).*

The full simple-loadbalancer run (verbatim from a real run) shows the
`batch().execute()` outcome plus a read-back-modify-readback cycle:

```
[LB] connected as primary on 127.0.0.1:50051, pipeline pushed
[LB] installed 3 routes (allSucceeded=true)
[LB] backend_lookup after install: 3 entries
[LB]   10.0.1.0/24 → port 1
[LB]   10.0.2.0/24 → port 2
[LB]   10.0.3.0/24 → port 3
[LB] moved 10.0.2.0/24 to port 4
[LB] backend_lookup after modify: 3 entries
[LB]   10.0.1.0/24 → port 1
[LB]   10.0.2.0/24 → port 4
[LB]   10.0.3.0/24 → port 3
[LB] cleaned up; goodbye
```

`execute()` always returns a `WriteResult`. If any update was rejected by
the device, `WriteResult.failures()` lists per-update `UpdateFailure`
records with the original batch index, the gRPC `ErrorCode`, and the
device's message. `WriteResult.allSucceeded()` is true iff `failures()`
is empty.

<!-- illustrative: idiomatic post-execute inspection -->

```java
if (!r.allSucceeded()) {
    for (UpdateFailure f : r.failures()) {
        log("update[" + f.index() + "] failed: " + f.code() + " " + f.message());
    }
}
```

P4Runtime does **not** mandate atomic batches. Updates are applied in
order, and a failure does not roll back the preceding successes — the
loadbalancer example explicitly cleans up afterwards via a delete batch
because of this.

## Reads

`read(tableName)` returns a `ReadQuery` with three terminals:

<!-- illustrative: concept fragment -->

```java
List<TableEntry> all       = sw.read("MyIngress.ipv4_lpm").all();
Optional<TableEntry> one   = sw.read("MyIngress.ipv4_lpm").one();
try (Stream<TableEntry> s = sw.read("MyIngress.ipv4_lpm").stream()) {
    s.forEach(e -> handle(e));
}
```

- `.all()` collects everything into a list. Fine for tables with at most
  a few thousand entries.
- `.one()` collapses to `Optional.empty()` (zero rows) or `Optional.of(e)`
  (exactly one row); throws `P4OperationException` if the device returns
  more than one row. Useful when you expect a unique key.
- `.stream()` returns a `Stream<TableEntry>` that closes the underlying
  gRPC iterator on `close()`. Always use it inside try-with-resources;
  closing the stream early cancels the read on the device.

Server-side filtering uses `.match(...)` on the query, mirroring the
write-side builder:

<!-- illustrative: concept fragment -->

```java
List<TableEntry> hits = sw.read("MyIngress.ipv4_lpm")
        .match("hdr.ipv4.dstAddr", new Match.Lpm(Ip4.of("10.0.1.0").toBytes(), 24))
        .all();
```

The device interprets the match-field set as a per-update filter (spec
§6.4); BMv2 implements this strictly. Empty match list = "every entry
in the table".

## Async reads

`allAsync()` / `oneAsync()` mirror the sync forms:

<!-- illustrative: concept fragment -->

```java
CompletableFuture<List<TableEntry>> f = sw.read("MyIngress.ipv4_lpm").allAsync();
f.thenAccept(rows -> log(rows.size() + " rows"));
```

There is no `streamAsync()` — `stream()` is already non-blocking on
production until you start consuming, so wrapping it in a future would
not buy anything.

## Concurrency

All write and read terminals serialise through the switch's outbound
single-threaded executor. Concurrent `sw.insert(...)` from multiple user
threads is safe and produces a deterministic order on the wire. There
is no inter-switch ordering guarantee: two `P4Switch` instances against
the same device race normally.

`stream()` is the exception — it initiates on the outbound thread but
*consumes* on the calling thread. Multiple stream consumers are
independent.

## See also

- [Pipelines](pipeline.md) — every name-based call resolves through the
  bound P4Info.
- [Error handling](error-handling.md) — `P4OperationException`,
  `P4PipelineException`, `P4ConnectionException`.
- [`examples/simple-loadbalancer/`](../examples/simple-loadbalancer/)
  for batch + read + modify end-to-end.
