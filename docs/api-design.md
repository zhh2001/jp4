# jp4 API Design — v3 (final)

Status: **Final design**. Implementation work proceeds against this document.
Scope: public API surface for jp4 v0.1, covering control-plane operations against a
single P4Runtime target. Multi-switch coordination, action profiles, counters, meters,
registers, and multicast groups are listed as **out of scope for v1** in §7.

---

## 1. Design Philosophy

1. **Optimise the common path.** A single-switch primary controller fits in one line.
   Customisation is available, but never required for the 99% case.
2. **P4Info-driven by name.** Once a pipeline is bound, every operation references
   tables / actions / match fields by their P4 source-level name. Numeric IDs never
   appear in user code.
3. **Strong types over byte strings, with escape hatches.** `Mac`, `Ip4`, `Ip6`,
   `Bytes`, `Match.Lpm`, `Match.Ternary`, `ElectionId` model what controllers actually
   work with. `byte[]` is accepted everywhere strong types are, so users can opt out.
4. **AutoCloseable everything.** Connections, streams, and read iterators close
   deterministically via try-with-resources.
5. **Sealed types for proto oneofs.** `Match`, `DeviceConfig`, and the
   per-update failure detail become exhaustive at compile time — what protobuf oneof
   can never give us.
6. **Blocking by default, async on demand.** Sync is what SDN controllers usually
   want; `xxxAsync()` returns `CompletableFuture` for callers that don't.
7. **Streams use `java.util.concurrent.Flow`.** Zero third-party reactive dependency;
   adapters to Reactor / RxJava are documented.
8. **Side-effecting methods return `this` when chaining is the obvious shape.**
   `bindPipeline`, `asPrimary`, `batch().insert(...)`. Same precedent as
   `StringBuilder.append`, `Stream.filter`. Honest naming carries the side-effect
   message; the return type carries the chaining message.

---

## 2. Core Design Decisions

These are the load-bearing choices the rest of the API hangs on.

### D1. Connection: one-line shortcut + fluent connector for the rest

```java
// Common case (one line, deviceId=0, electionId=1):
P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051");

// Customised:
P4Switch sw = P4Switch.connect("127.0.0.1:50051")
        .deviceId(2)
        .electionId(ElectionId.of(0xCAFE))
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(...))
        .asPrimary();

// Read-only client (joins arbitration but accepts secondary role):
P4Switch sw = P4Switch.connect("127.0.0.1:50051").deviceId(0).electionId(1).asSecondary();
```

| Option | Verdict |
| --- | --- |
| `new P4Switch(addr, deviceId, electionId)` | Rejected. Construction would have side effects (DNS, gRPC handshake, arbitration RPC). |
| `P4Switch.builder()...build()` | Rejected. `.build()` reads as "construct" and hides the fact that arbitration happens. |
| `connect(addr).deviceId(...).electionId(...).asPrimary()` | **Chosen** for the customised path. The intermediate stage is type-distinct (`Connector`); `electionId(...)` is required before `.asPrimary()`. |
| `P4Switch.connectAsPrimary(addr)` shortcut | **Chosen** as the front door. Defaults `deviceId=0`, `electionId=1`. There is no shortcut for the secondary role — it is rarer and its election-id intent varies. |

`asPrimary()` blocks until the arbitration response confirms primary; throws
`P4ConnectionException` (specifically the subclass `P4ArbitrationLost`) otherwise.
`asSecondary()` returns as soon as the arbitration response arrives regardless of
role; the returned `P4Switch` exposes `sw.isPrimary()` and rejects writes with
`P4ConnectionException` while not primary.

**Idempotency.** `P4Switch.asPrimary()` is also exposed as an instance method for
HA controllers that need to re-claim mastership after a `MastershipStatus.lost`
event:

- If the switch is currently primary and the gRPC stream is healthy: returns
  `this` immediately, no RPC sent.
- If the switch has internally observed mastership loss (mastership change event with
  status != OK from the server): re-sends `MasterArbitrationUpdate` with the same
  election id and blocks until primary.

The connector's `.asPrimary()` (the terminal that constructs the switch) and the
switch's `.asPrimary()` share this contract: calling either when already primary is
a cheap no-op.

### D2. Sync by default, async opt-in, streams via `Flow`

| Operation kind | Default API | Power-user alternative |
| --- | --- | --- |
| Write/Read/Pipeline RPCs | Blocking sync | `xxxAsync()` → `CompletableFuture<T>` |
| PacketIn, mastership change, idle timeout | `onXxx(Consumer)` callback | `xxxStream()` → `Flow.Publisher<T>` |
| Blocking pull of stream events | `pollPacketIn(Duration)` | — |

P4 control-plane RPCs typically complete in <10 ms against BMv2 and a few ms against
hardware targets; the synchronous shape matches typical SDN control loops. `Flow.Publisher`
is in the JDK since Java 9 — see §5 Scenario E for adapters to Reactor / RxJava.

### D3. P4Info bound at the switch, not passed per call

After `bindPipeline(p4info, deviceConfig)` or `loadPipeline()`, the `P4Switch` holds
the resolved P4Info and an internal name → ID index. Every subsequent call uses names.
`bindPipeline` lives **only** on `P4Switch`, not on the connector — connection and
pipeline binding are independent semantic actions, and a switch may swap pipelines at
runtime. `bindPipeline` returns `this` so it composes inside the try-with-resources
head (per philosophy point 8).

### D4. Errors: unchecked, three categories

```java
public class P4RuntimeException extends RuntimeException { ... }

public class P4ConnectionException extends P4RuntimeException {
    // gRPC transport faults, arbitration loss, mastership conflict, channel shutdown.
    // Subclass: P4ArbitrationLost (carries previous election id, current primary's id).
}

public class P4PipelineException extends P4RuntimeException {
    // SetForwardingPipelineConfig failed; P4Info parse error; name not found in p4info;
    // entry/match/action structurally invalid against the bound pipeline.
}

public class P4OperationException extends P4RuntimeException {
    // Per-RPC Write/Read failures.
    public OperationType operationType();             // INSERT / MODIFY / DELETE / READ
    public ErrorCode      errorCode();                // gRPC code, e.g. NOT_FOUND, ALREADY_EXISTS
    public List<UpdateFailure> failures();            // populated for batch writes; empty for reads
}
```

Three categories rather than five: real catch sites tend to group transport-and-mastership
together (both "the link is unusable") and write-and-read together (both "the RPC ran but
the result was rejected"). `P4PipelineException` stays separate because pipeline issues
are usually configuration bugs handled differently from operational errors.

### D5. Multi-switch: deferred to v0.2

v1 exposes only `P4Switch`. Apps that need multiple switches use `List<P4Switch>` (or
a small in-app helper) until v0.2 ships the proper Controller with deliberate semantics
around fan-out, parallelism, and error aggregation.

### D6. PacketIn: three styles, one stream

```java
sw.onPacketIn(packet -> ...);                            // 1. callback (common case)
sw.packetInStream().subscribe(subscriber);               // 2. Flow.Publisher (backpressure)
PacketIn p = sw.pollPacketIn(Duration.ofSeconds(1));     // 3. blocking pull
```

All three feed off the same underlying gRPC stream. The first registered consumer wins;
mixing styles is allowed but not recommended.

### D7. Entry construction: build first, execute via switch

There is exactly one way to construct a `TableEntry`:

```java
TableEntry e = TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
        .action("MyIngress.set_egress").param("port", 1)
        .build();

sw.insert(e);                                  // single
sw.batch().insert(e1).insert(e2).execute();    // batch
```

| Option | Verdict |
| --- | --- |
| Two terminal modes (`insert()` AND `build()`) on the same builder | Rejected. Confused early sketches. |
| Plan A: always `TableEntry.in(...).build()`, then `sw.insert(e)` | **Chosen.** One construction path; consistent for single and batch; entries are reusable values (handy for tests and config loading). |
| Plan B: `sw.insertEntry(t -> ...)` for single, `TableEntry.in(...)` for batch | Rejected. Two construction styles, same domain. |

Cost of Plan A: the single-entry case is two statements instead of one. We accept that
in exchange for one true way to construct entries. `TableEntry` is immutable; the same
instance may be re-inserted into different switches sharing a pipeline.

For deletes, the action half of the builder may be omitted — `delete()` ignores it and
matches by key only.

### D8. Reads via a query builder

`read(tableName)` returns a `ReadQuery` that the caller terminates with `all()`,
`one()`, or `stream()`. Match filters are added via the same `match(...)` overloads
used by `TableEntry.in(...)`:

```java
sw.read("MyIngress.dmac").all();                                    // List<TableEntry>
sw.read("MyIngress.dmac").stream();                                 // Stream<TableEntry>, AutoCloseable
sw.read("MyIngress.dmac").match("hdr.ethernet.dstAddr", mac).one(); // Optional<TableEntry>
sw.read("MyIngress.dmac").match("hdr.ipv4.protocol", 0x06).all();   // partial filter
```

This shape leaves room for v0.2 extensions (`.where(Predicate<TableEntry>)` for
client-side filtering, `.fields(...)` for projection, etc.) without method explosion
on `P4Switch` or breakage of v0.1 callers.

| Option | Verdict |
| --- | --- |
| `readAll(table)`, `readOne(entry)`, `readAllStream(table)` | Rejected. Three methods today, six tomorrow when filters land. |
| `read(table).all()/one()/stream()` query builder | **Chosen.** Single entry point on `P4Switch`; capability surface grows on `ReadQuery`, not on the switch. |

`one()` throws `P4OperationException` if the query matches more than one entry; it is
intended for full-key lookups (`Optional.empty()` for the not-found case). For partial
keys, callers use `all()` and inspect the list.

---

## 3. Type Glossary

Types live in subpackages so they don't collide with user code that already has classes
named `Mac`, `Ip4`, etc.

```text
io.github.zhh2001.jp4                  // P4Switch, Connector, ReconnectPolicy, ReadQuery, MastershipStatus, WriteResult
io.github.zhh2001.jp4.types            // Bytes, Mac, Ip4, Ip6, ElectionId
io.github.zhh2001.jp4.match            // Match (sealed), Match.Exact/Lpm/Ternary/Range/Optional
io.github.zhh2001.jp4.entity           // TableEntry, PacketIn, PacketOut, UpdateFailure
io.github.zhh2001.jp4.pipeline         // P4Info, DeviceConfig, PipelineAction
io.github.zhh2001.jp4.error            // P4RuntimeException + the three subclasses
```

### Value types

`Bytes` is a `final class` with a defensive copy on every accessor; the rest are
`record`s. The asymmetry is on purpose: records expose their components by reference,
which would let `Bytes`'s backing `byte[]` be mutated by callers — unacceptable for a
type that pretends to be a value. `Mac` / `Ip4` / `Ip6` also wrap arrays but their
constructors take a `String` (parsed and copied), so the array is freshly allocated per
instance and never re-exposed in raw form.

```java
public final class Bytes {
    public static Bytes of(byte... b);
    public static Bytes ofInt(int value);                 // canonical: no leading zero bytes, min 1 byte
    public static Bytes ofInt(int value, int bitWidth);   // non-canonical, fixed width (rare)
    public static Bytes ofLong(long value);
    public static Bytes ofHex(String hex);                // "deadbeef"
    public Bytes canonical();                             // strip leading zeros
    public byte[] toByteArray();                          // defensive copy
    public int length();
}

public record Mac(byte[] octets) {        // always 6 bytes
    public static Mac of(String dotted);  // "de:ad:be:ef:00:01" or "deadbeef0001"
    public static Mac of(long value);
    public Bytes toBytes();
}

public record Ip4(byte[] octets) { /* 4 bytes; of(String) accepts "10.0.0.1"; of(int) */ }
public record Ip6(byte[] octets) { /* 16 bytes; of(String) accepts "2001:db8::1" */ }

public record ElectionId(long high, long low) implements Comparable<ElectionId> {
    public static ElectionId of(long low);                 // high=0
    public static ElectionId of(long high, long low);
    public static ElectionId fromBigInteger(BigInteger bi);
    public static final ElectionId ZERO;
    public static final ElectionId MAX;
}
```

**Escape hatch.** Every method that accepts `Mac` / `Ip4` / `Ip6` / `Bytes` also has a
`byte[]` overload. Users with their own type system can stay in `byte[]` and ignore
`io.github.zhh2001.jp4.types` entirely.

### Match (sealed)

```java
public sealed interface Match permits Match.Exact, Match.Lpm, Match.Ternary, Match.Range, Match.Optional {
    record Exact(Bytes value)                       implements Match { }
    record Lpm(Bytes value, int prefixLen)          implements Match {
        public static Lpm of(String cidr);                  // "10.0.0.0/24"
        public static Lpm of(Ip4 addr, int prefixLen);
        public static Lpm of(Ip6 addr, int prefixLen);
    }
    record Ternary(Bytes value, Bytes mask)         implements Match {
        public static Ternary of(int value, int mask);
        public static Ternary of(Bytes value, Bytes mask);
    }
    record Range(Bytes low, Bytes high)             implements Match {
        public static Range of(int low, int high);
    }
    record Optional(Bytes value)                    implements Match { }      // P4Runtime "optional" match kind

    static Match exact(Bytes v);
    static Match exact(int v);
    static Match lpm(String cidr);                                            // delegates to Lpm.of
    static Match ternary(int value, int mask);                                // delegates to Ternary.of
}
```

The Style A vs Style B question (sealed `Match` types vs `matchExact` / `matchLpm` etc.
overloads): **Style A wins**, with one ergonomic compromise — exact match accepts the
value directly:

```java
// Exact (the common case): pass the value, match() infers exact.
.match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
.match("ipv4.protocol",        0x06)

// Non-exact: wrap in a Match subtype.
.match("hdr.ipv4.dstAddr",     Match.Lpm.of("10.0.0.0/24"))
.match("hdr.tcp.dstPort",      Match.Range.of(1024, 65535))
.match("hdr.ipv4.flags",       Match.Ternary.of(0b010, 0b110))
```

Reasoning. Style A preserves compile-time exhaustiveness when **reading entries back**
(`switch (entry.match("foo")) { case Lpm l -> ...; case Ternary t -> ...; ... }`), gives
us reusable `Match` values, and keeps the builder API to a single `match(String, ...)`
overload. The exact-match shortcut isn't Style B (no `matchExact` method); it's just
overloads of the same `match()` method that accept bare values.

### Pipeline

```java
public sealed interface DeviceConfig permits DeviceConfig.Bmv2, DeviceConfig.Raw {
    record Bmv2(byte[] json) implements DeviceConfig {
        public static Bmv2 fromFile(Path jsonPath);
    }
    record Raw(byte[] bytes) implements DeviceConfig {
        public static Raw fromFile(Path path);
    }

    static DeviceConfig empty();
}

public final class P4Info {
    public static P4Info fromFile(Path path);             // sniffs text vs binary
    public static P4Info fromText(Path path);
    public static P4Info fromBinary(Path path);
    public static P4Info fromBytes(byte[] bytes);

    public List<String> tableNames();
    public List<String> actionNames();
    public TableInfo  table(String name);                 // throws P4PipelineException if unknown
    public ActionInfo action(String name);
}

public enum PipelineAction { VERIFY_AND_COMMIT, RECONCILE_AND_COMMIT }
```

`DeviceConfig` only ships `Bmv2` and `Raw` in v1 — the latter is the escape hatch
for any target whose pre-encoded device config bytes can be supplied directly. Tofino
support, when added in v0.2, will be an additional sealed variant.

`PipelineAction` exposes only the two values in active use today: `VERIFY_AND_COMMIT`
(initial push, the default) and `RECONCILE_AND_COMMIT` (used internally by
auto-reconnect). The other three values from the P4Runtime spec (`VERIFY`,
`VERIFY_AND_SAVE`, `COMMIT`) will be added when a real use case needs them — easier
to add than to remove.

### Read query

```java
public interface ReadQuery {
    ReadQuery match(String fieldName, Bytes value);
    ReadQuery match(String fieldName, Mac value);
    ReadQuery match(String fieldName, Ip4 value);
    ReadQuery match(String fieldName, Ip6 value);
    ReadQuery match(String fieldName, int value);
    ReadQuery match(String fieldName, long value);
    ReadQuery match(String fieldName, byte[] value);       // escape hatch
    ReadQuery match(String fieldName, Match match);

    List<TableEntry>             all();
    Stream<TableEntry>           stream();                 // AutoCloseable; closes the gRPC iterator
    java.util.Optional<TableEntry> one();                  // throws P4OperationException if >1 result

    CompletableFuture<List<TableEntry>>             allAsync();
    CompletableFuture<java.util.Optional<TableEntry>> oneAsync();
}
```

---

## 4. Common imports (assumed by every example below)

```java
import io.github.zhh2001.jp4.*;
import io.github.zhh2001.jp4.types.*;
import io.github.zhh2001.jp4.match.*;
import io.github.zhh2001.jp4.entity.*;
import io.github.zhh2001.jp4.pipeline.*;
import io.github.zhh2001.jp4.error.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Stream;
```

---

## 5. Scenarios

### Scenario A — Connect, become primary, push pipeline, close

```java
P4Info       p4info = P4Info.fromFile(Path.of("basic.p4info.txt"));
DeviceConfig dc     = DeviceConfig.Bmv2.fromFile(Path.of("basic.json"));

// One-line common case.
try (P4Switch sw = P4Switch.connectAsPrimary("127.0.0.1:50051")
        .bindPipeline(p4info, dc)) {
    // sw is primary on device 0 with pipeline loaded.
}

// Customised path (different deviceId / electionId / reconnect policy).
try (P4Switch sw = P4Switch.connect("127.0.0.1:50051")
        .deviceId(7)
        .electionId(ElectionId.of(0xCAFE))
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(10), /*maxRetries*/ 5))
        .asPrimary()
        .bindPipeline(p4info, dc)) {
    // ...
}
```

`bindPipeline` returns `this`, so the connector chain reads as one expression.

**Behind the scenes.** The factory builds a `ManagedChannel`, opens StreamChannel,
sends `MasterArbitrationUpdate`, blocks until the response confirms primary.
`bindPipeline` sends `SetForwardingPipelineConfig(VERIFY_AND_COMMIT)` and caches the
P4Info index. `close()` ends the stream and shuts down the channel with a default 5 s
grace period.

**Pain killed.** 1, 2, 3, 11, 14.

### Scenario B — Loading P4Info

```java
// From local files (auto-sniff text vs binary):
P4Info p4info = P4Info.fromFile(Path.of("basic.p4info.txt"));

// No local file — fetch what the device already has:
try (P4Switch sw = P4Switch.connectAsPrimary(addr).loadPipeline()) {
    P4Info live = sw.pipeline().p4info();
}
```

`loadPipeline()` issues `GetForwardingPipelineConfig(ALL)` and binds the result.

**Pain killed.** 3, 4.

### Scenario C — Insert / Modify / Delete / Read across match kinds

```java
// Exact match (Mac value passed directly; match() infers exact).
TableEntry exact = TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
        .action("MyIngress.set_egress").param("port", 1)
        .build();
sw.insert(exact);

// LPM on an IPv4 prefix, with a two-key table requiring exact + lpm.
TableEntry lpm = TableEntry.in("MyIngress.ipv4_lpm")
        .match("hdr.ethernet.etherType", 0x0800)                           // exact (int)
        .match("hdr.ipv4.dstAddr",       Match.Lpm.of("10.0.0.0/24"))      // lpm
        .action("MyIngress.forward")
            .param("port",    2)
            .param("nextHop", Mac.of("aa:bb:cc:dd:ee:ff"))
        .build();
sw.insert(lpm);

// Ternary ACL with priority and a range key.
TableEntry acl = TableEntry.in("MyIngress.acl")
        .match("hdr.ipv4.protocol", Match.Ternary.of(0x06, 0xff))          // ternary
        .match("hdr.tcp.dstPort",   Match.Range.of(1024, 65535))           // range
        .action("MyIngress.allow")
        .priority(100)
        .build();
sw.insert(acl);

// Modify (same key, action changes).
sw.modify(TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
        .action("MyIngress.set_egress").param("port", 5)
        .build());

// Delete (key only — action omitted).
sw.delete(TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
        .build());

// Read all entries in a table.
List<TableEntry> all = sw.read("MyIngress.dmac").all();

// Read by full key.
Optional<TableEntry> hit = sw.read("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
        .one();

// Read by partial key.
List<TableEntry> tcp = sw.read("MyIngress.acl")
        .match("hdr.ipv4.protocol", Match.Ternary.of(0x06, 0xff))
        .all();

// Stream large tables (closes the gRPC iterator on stream close).
try (Stream<TableEntry> s = sw.read("MyIngress.dmac").stream()) {
    long drops = s.filter(e -> e.action().name().equals("MyIngress.drop_pkt")).count();
}

// Pattern-match on a returned entry (Match is sealed → switch is exhaustive).
TableEntry e = hit.orElseThrow();
String desc = switch (e.match("hdr.ipv4.dstAddr")) {
    case Match.Exact x    -> "exact:"   + x.value();
    case Match.Lpm   l    -> "lpm:"     + l.value() + "/" + l.prefixLen();
    case Match.Ternary t  -> "ternary:" + t.value() + "&" + t.mask();
    case Match.Range r    -> "range:"   + r.low() + ".." + r.high();
    case Match.Optional o -> "opt:"     + o.value();
};
```

**Behind the scenes.** Validation happens at `sw.insert/modify/delete` and
`ReadQuery.all/one/stream` time against the bound P4Info: required match fields present,
match kind matches the field's declared kind in the table, value width fits the field's
bit width, action belongs to the table's action set, all action params present and
width-correct. Validation failure throws `P4PipelineException` with the field name in
the message. Byte canonicalisation (no leading zero bytes, min 1 byte) happens here too.

**Pain killed.** 4, 5, 6, 7, 8, 9, 12, 13.

### Scenario D — Batch updates in one Write request

```java
TableEntry e1 = TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:01"))
        .action("MyIngress.set_egress").param("port", 1)
        .build();

TableEntry e2 = TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:02"))
        .action("MyIngress.set_egress").param("port", 2)
        .build();

TableEntry obsolete = TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:99"))
        .build();

WriteResult result = sw.batch()
        .insert(e1)
        .insert(e2)
        .delete(obsolete)
        .execute();

if (!result.allSucceeded()) {
    for (UpdateFailure f : result.failures()) {
        log.warn("update[{}] failed: {} {}", f.index(), f.code(), f.message());
    }
}
```

`WriteResult` is `record(int submitted, List<UpdateFailure> failures)`. If the entire
RPC fails (transport error), `P4ConnectionException` is thrown instead. Per-update
partial failures are reported in `failures()` matching the P4Runtime canonical error
format (one entry per failed update, with the original index).

**Pain killed.** 10, 11.

### Scenario E — PacketIn / PacketOut

```java
// Listen for packets — common case, callback style.
sw.onPacketIn(packet -> {
    int ingressPort = packet.metadataInt("ingress_port");
    handle(ingressPort, packet.payload().toByteArray());
});

// Send a packet out a specific port.
sw.send(PacketOut.builder()
        .payload(rawBytes)
        .metadata("egress_port", 1)
        .build());

// Power-user: Flow.Publisher with backpressure.
sw.packetInStream().subscribe(new Flow.Subscriber<PacketIn>() {
    @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    @Override public void onNext(PacketIn p)              { /* ... */ }
    @Override public void onError(Throwable t)            { /* ... */ }
    @Override public void onComplete()                    { }
});

// Power-user: blocking pull.
PacketIn p = sw.pollPacketIn(Duration.ofSeconds(1));
if (p != null) { /* ... */ }
```

**Bridging to Reactor / RxJava.** `Flow.Publisher` is the JDK-native shape; users on
reactive stacks bridge in one line, with no dependency added by jp4 itself:

```java
// Reactor (project-reactor):
Flux<PacketIn> flux = reactor.adapter.JdkFlowAdapter
        .flowPublisherToFlux(sw.packetInStream());

// RxJava 3 (using the Reactive Streams adapter):
Flowable<PacketIn> flow = Flowable.fromPublisher(
        org.reactivestreams.FlowAdapters.toPublisher(sw.packetInStream()));
```

**Packet metadata.** Names come from the `controller_packet_metadata` declarations in
P4Info. `metadataInt(name)` and `metadata(name) -> Bytes` are the accessors;
`PacketOut.builder().metadata(name, value)` accepts `int` / `long` / `Bytes` / `byte[]`.
Width is canonicalised against the P4Info-declared bit width.

**Behind the scenes.** PacketIn uses a small internal `LinkedBlockingDeque<PacketIn>`
(default capacity 1024, configurable on the connector via `.packetInQueueSize(n)`).
Drops on overflow surface via `onPacketDropped` (defaults to log-warn). `Flow.Publisher`
honours subscriber demand and never buffers beyond the configured capacity.

### Scenario F — Errors, reconnect, lifecycle

```java
try (P4Switch sw = P4Switch.connectAsPrimary(addr).bindPipeline(p4info, dc)) {

    sw.insert(TableEntry.in("MyIngress.dmac")
            .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:01"))
            .action("MyIngress.set_egress").param("port", 1)
            .build());

} catch (P4PipelineException e) {
    // P4Info / device config mismatch, malformed entry, unknown table or field name.
} catch (P4OperationException e) {
    // Per-RPC failure — code() distinguishes NOT_FOUND / ALREADY_EXISTS / INVALID_ARGUMENT etc.
    for (UpdateFailure f : e.failures()) { /* ... */ }
} catch (P4ConnectionException e) {
    // Transport faults, mastership lost, channel shutdown.
} catch (P4RuntimeException e) {
    // Catch-all (parent of all above).
}

// Auto-reconnect with retry policy on the connector:
P4Switch sw = P4Switch.connect(addr)
        .deviceId(0)
        .electionId(1)
        .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(10), /*maxRetries*/ 5))
        .asPrimary();

// Mastership change observer (e.g. for HA controllers):
sw.onMastershipChange(status -> {
    if (status.isLost()) {
        reportToOps();
        sw.asPrimary();   // idempotent re-claim; no-op if already recovered
    }
});

// Async variant for any blocking method:
CompletableFuture<Void> f = sw.insertAsync(TableEntry.in("MyIngress.dmac")
        .match("hdr.ethernet.dstAddr", Mac.of("..."))
        .action("MyIngress.set_egress").param("port", 1)
        .build());
```

**Reconnect semantics.** `ReconnectPolicy` is consulted on `P4ConnectionException` from
the gRPC layer. On reconnect, jp4 re-issues `MasterArbitrationUpdate` with the same
election id and re-pushes the bound pipeline (`RECONCILE_AND_COMMIT`). In-flight async
operations are not retried automatically — they fail with `P4ConnectionException` and
the caller decides.

**Pain killed.** 14, 15.

---

## 6. Pain-Point Coverage Matrix

| # | Pain | Resolution |
| --- | --- | --- |
| 1 | Master arbitration via StreamObserver+CountDownLatch | `connectAsPrimary(addr)` (or `.asPrimary()`) blocks until primary; observer hidden internally |
| 2 | `Uint128(high, low)` for election id | `ElectionId.of(long)` / `ElectionId.of(high, low)` / `fromBigInteger`; `electionId(long)` shortcut on the connector |
| 3 | `TextFormat.merge` has no `Path` overload | `P4Info.fromFile(Path)` auto-sniffs format |
| 4 | Manual `stream().filter().findFirst()` to look up IDs | P4Info index built on `bindPipeline` / `loadPipeline`; users only ever see names |
| 5 | Five-level nested `TableEntry` builder | `TableEntry.in(...).match(...).action(...).param(...).build()` flat chain |
| 6 | `FieldMatch` oneof with no compile-time check | Sealed `Match` interface; `switch` on returned matches is exhaustive; runtime validates against the declared kind |
| 7 | Two `Action` classes (definition vs runtime) collide on import | Both protobuf classes are internal; user code only sees `ActionInfo` (definition) and the entry builder API |
| 8 | bit\<N\> ↔ ByteString conversion + canonical form | `Bytes.ofInt`, `Mac.of`, `Ip4.of`; canonicalisation runs at the terminal switch call |
| 9 | No MAC/IP parser | `Mac.of("de:ad:be:ef:00:01")`, `Ip4.of("10.0.0.1")`, `Ip6.of("::1")` |
| 10 | Update wrapping `WriteRequest > Update > Entity > TableEntry` | `sw.insert(e)` for one; `sw.batch().insert(e1).insert(e2).execute()` for many |
| 11 | Re-pass deviceId+electionId on every Write/Read | Stored on `P4Switch`; injected internally |
| 12 | Read-all wildcard semantics hidden | `sw.read("table").all()` is the obvious call |
| 13 | `Iterator<ReadResponse>` with nested entities | `ReadQuery.all()` flattens to `List<TableEntry>`; `.stream()` to `Stream<TableEntry>` |
| 14 | Manual ManagedChannel + StreamObserver shutdown | `try (P4Switch sw = ...)` |
| 15 | No auto-reconnect/re-arbitration | `.reconnectPolicy(...)` on the connector + idempotent `sw.asPrimary()` for HA re-claim |
| 16 | Generated proto can shadow protobuf-java internals | Build-system concern, not API surface; covered by `CommonProtosResolutionTest` regression guard |

---

## 7. Out of Scope for v1 (planned for v0.2+)

These are real P4Runtime features deliberately deferred. Each will get its own design
pass before being added:

- **Multi-switch coordination (`P4Controller`)** — fan-out, parallel execution, error
  aggregation. v1 users use `List<P4Switch>` if they need a few targets.
- **Tofino device config** — `DeviceConfig.Tofino` variant alongside the existing
  `Bmv2` and `Raw`.
- **Additional pipeline actions** — `VERIFY`, `VERIFY_AND_SAVE`, `COMMIT` will be added
  to the `PipelineAction` enum when a real use case appears.
- **Field-level read filters** — `ReadQuery.where(Predicate<TableEntry>)` for
  client-side filtering, `ReadQuery.fields(...)` for projection.
- Action profiles & action selectors (ECMP/WCMP groups)
- Counters, meters, registers, direct counters/meters
- Multicast group / clone session management
- Packet replication engine (PRE)
- Idle timeout notifications (the gRPC stream message exists; no API yet)
- Digest list (collected packet headers from the data plane)
- Role-based arbitration (P4Runtime 1.3 roles)
- TLS / mTLS for the gRPC channel
- Default-action management (`is_default_action` table entries)

---

## 8. Changelog v2 → v3

Closing every open question from v2 and tightening scope:

- **D1, philosophy 8**: `bindPipeline` and friends return `this` (closed v2 OQ1).
  Reasoning lifted into a top-level philosophy point so future API additions inherit it.
- **D1**: `asPrimary()` idempotency contract spelled out, including the instance-method
  variant `sw.asPrimary()` for HA re-claim after `MastershipStatus.lost`. Closed
  v2 OQ6.
- **D8 (new)**: Reads moved to a query-builder model
  (`sw.read(table).match(...).all()/one()/stream()`). The three v2 methods
  (`readAll`, `readOne`, `readAllStream`) are gone. Closed v2 OQ5; leaves room for
  v0.2 `.where(filter)` / `.fields(projection)` without method explosion.
- **§3 Pipeline scope cut**: `DeviceConfig.Tofino` removed (use `Raw` or wait for v0.2);
  `PipelineAction` reduced to `VERIFY_AND_COMMIT` and `RECONCILE_AND_COMMIT`. Easier to
  add back than to remove later.
- **§3 Value types**: explicit note on why `Bytes` is a `final class` while everything
  else is a `record` (defensive copy of the `byte[]` backing field). v2 OQ4
  (`unsafeBytes`) declined — premature optimisation; revisit only if profiling shows
  it.
- **§3 ReadQuery**: type definition added.
- **§5 Scenario C**: every read call rewritten in the query-builder shape.
- **§5 Scenario F**: `sw.asPrimary()` shown inside `onMastershipChange` to demonstrate
  the HA re-claim pattern.
- **§6 Pain matrix**: rows 12, 13, 15 updated to reflect the read query builder and
  the idempotent `asPrimary`.
- **§7**: Tofino device config, additional pipeline actions, and read filters added
  to the v0.2 list.
- **§8 Open Questions removed** — every v2 question is now answered. v2 OQ2
  (`TableEntry` vs `Entry`): keep `TableEntry`, matches spec, future entity types
  (`ActionProfileEntry`, `MeterEntry`) follow the same pattern. v2 OQ3 (default
  electionId): keep `1`; users can override via the connector chain.
- **Markdown**: table separator rows changed from `|---|---|` to `| --- | --- |` for
  consistent spacing (markdownlint MD060 compliance).
