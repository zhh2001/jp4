<!-- doc-lint: skip-file (migration guide; code blocks are before/after pseudocode for the v0.1 -> v1.0 surface changes, not user-facing best-practice examples) -->

# Migration guide: jp4 v0.1 → v1.0

This guide documents the API surface changes between v0.1 and v1.0,
with before/after examples for callers updating from v0.1. It is the
authoritative reference for the v0.1 → v1.0 transition; the
[CHANGELOG](../CHANGELOG.md) carries the same information in
release-note form.

The changes split into four categories:

- **Additions** — new methods on the v1.0 surface; opt-in, no
  migration required.
- **Behaviour changes** — a small set of methods whose behaviour
  tightened. Callers passing legitimate inputs are unaffected;
  callers passing null or relying on specific exception messages may
  need to adjust.
- **Documentation changes** — knowledge updates that do not affect
  runtime behaviour but are worth noting.
- **v1.x roadmap** — capabilities deferred to point releases of v1.x.

Each entry below cites the commit hash where the change landed; use
`git show <hash>` for the full diff.

## Additions (opt-in)

These new methods are available on the v1.0 surface; v0.1 patterns
continue to work indefinitely.

### `ActionInstance.paramInt(String)` and `paramLong(String)`

(commit `b544caa`, severity: opt-in addition)

Convenience accessors mirroring `PacketIn.metadataInt(String)`.
Extract a primitive integer parameter without manually wrapping
`new BigInteger(1, b.toByteArray())`.

```java
// v0.1 -- still works on v1.0
Bytes portBytes = entry.action().param("port");
int port = portBytes == null ? 0
    : new BigInteger(1, portBytes.toByteArray()).intValueExact();

// v1.0 convenience
int port = entry.action().paramInt("port");
```

`paramLong(String)` is the long-shaped sister for fields up to 63
bits. Both throw `IllegalStateException` on absent parameter or on
a value too wide for the target primitive (use `param(String)` for
wider values).

### `PacketIn.metadataLong(String)`

(commit `b544caa`, severity: opt-in addition)

Long-shaped sister of `metadataInt(String)`. Closes the asymmetry
with `paramInt` / `paramLong` on `ActionInstance`.

```java
// v0.1
Bytes traceBytes = pkt.metadata("trace_id");
long trace = new BigInteger(1, traceBytes.toByteArray()).longValueExact();

// v1.0
long trace = pkt.metadataLong("trace_id");
```

### Symmetric `fromBytes(byte[])` factories

(commit `42e3cfa`, severity: opt-in addition)

`Ip4.fromBytes(byte[])`, `Ip6.fromBytes(byte[])`, `Mac.fromBytes(byte[])`,
`DeviceConfig.Bmv2.fromBytes(byte[])`, `DeviceConfig.Raw.fromBytes(byte[])`.
The naming family mirrors `P4Info.fromBytes(byte[])` (which has
existed since v0.1).

```java
// v0.1 -- canonical record constructor
Ip4 addr = new Ip4(rawBytes);

// v1.0 -- explicit factory
Ip4 addr = Ip4.fromBytes(rawBytes);
```

The record canonical constructors remain available; factory choice
is purely stylistic.

## Behaviour changes (callers may need to adjust)

### `MastershipStatus.toString()` format change

(commit `23481ce`, severity: practically disruptive)

The default record `toString()` was replaced with a compact,
grep-friendly form so that log lines stay short and one grep pattern
catches both states.

```
v0.1
  Acquired[ourElectionId=ElectionId(10)]
  Lost[previousElectionId=null, currentPrimaryElectionId=ElectionId(10)]

v1.0
  Acquired(primary=10)
  Lost(prev=null, primary=10)
  Lost(prev=5, primary=10)
```

Action required if:

- Your code parses `toString()` output (it should not — the record
  accessors `Acquired.ourElectionId()`, `Lost.previousElectionId()`,
  `Lost.currentPrimaryElectionId()` are the stable contract).
- A log parser greps on the old field names (`ourElectionId`,
  `currentPrimaryElectionId`) or the wrap form `ElectionId(N)`.

The new format unifies the field name `primary` across both states,
so a single `grep "primary=10"` catches both `Acquired(primary=10)`
and `Lost(prev=*, primary=10)`.

### `DeviceConfig.{Bmv2,Raw}.fromFile` exception type

(commit `2bb1acd`, severity: SemVer-safe — strictly more specific)

`fromFile` now wraps `IOException` as `java.io.UncheckedIOException`
instead of plain `RuntimeException`.

```java
// v0.1
try {
    DeviceConfig.Bmv2 cfg = DeviceConfig.Bmv2.fromFile(path);
} catch (RuntimeException e) {
    if (e.getCause() instanceof IOException ioe) { ... }
}

// v1.0 -- type-safe cause access
try {
    DeviceConfig.Bmv2 cfg = DeviceConfig.Bmv2.fromFile(path);
} catch (UncheckedIOException e) {
    IOException ioe = e.getCause();   // no instanceof check
}
```

`UncheckedIOException` is a subclass of `RuntimeException`; existing
`catch (RuntimeException)` continues to work without modification.
The cause chain remains the original `IOException`.

### Accessor methods reject null parameter

(commit `ff71c89`, severity: behaviour change for null inputs)

Three accessors that previously returned `null` for a null parameter
name now throw `NullPointerException`, aligning with the
project-wide convention stated in each `package-info.java`.

| Method | v0.1 behaviour | v1.0 behaviour |
|---|---|---|
| `ActionInstance.param(null)` | returned `null` | `NullPointerException("paramName")` |
| `PacketIn.metadata((String) null)` | returned `null` | `NullPointerException("name")` |
| `TableEntry.match(null)` | returned `null` | `NullPointerException("fieldName")` |

The null-return behaviour for an *unknown but non-null* name is
unchanged — `entry.match("not-a-real-field")` still returns `null`.

```java
// v0.1 -- using null to test "absent"
Match m = entry.match(null);             // returned null

// v1.0 -- pass an unknown name explicitly
Match m = entry.match("definitely-not-a-real-field");
if (m == null) { /* still works */ }
```

### `MastershipStatus.Acquired(null)` rejected

(commit `ff71c89`, severity: behaviour change for null inputs)

The `Acquired` record's canonical constructor now rejects a null
`ourElectionId` with `NullPointerException`. An `Acquired` event
fundamentally means "this client became primary with election id X";
X is never null in any legitimate use.

```java
// v0.1 -- silently constructed
new MastershipStatus.Acquired(null);

// v1.0 -- throws NullPointerException("ourElectionId")
new MastershipStatus.Acquired(null);
```

The `Lost` record's two `ElectionId` fields remain nullable on
purpose; see the updated javadoc on `MastershipStatus.Lost`.

### Actionable error messages on width-overflow

(commit `b544caa`, severity: minor — message text only)

The `IllegalStateException` thrown by `metadataInt` / `metadataLong` /
`paramInt` / `paramLong` when a value is too wide for the target
primitive now names the recommended alternative.

```
v0.1
  metadata field 'big' is 32 bits wide; does not fit in int

v1.0
  metadata field 'big' has width 32 bits, exceeds 31-bit signed int range;
  use metadataLong or metadata(String) directly
```

The exception type (`IllegalStateException`) and the conditions
under which it fires are unchanged. Only callers grep'ing on the
message text need to update.

## Documentation changes

These do not affect runtime behaviour.

- **Thread-safety contracts** are explicitly documented on 11
  user-facing classes (commit `9c58ee6`): builders are not safe for
  concurrent use; sealed-type records and schema metadata are safe
  to share across threads. The pre-existing `P4Switch` threading
  model section is unchanged.
- **Null contract** is stated in all four `package-info.java` files
  (commit `ff71c89`): public methods reject null arguments with
  `NullPointerException` unless explicitly documented otherwise.
  Methods that accept null on purpose (e.g.,
  `MastershipStatus.Lost.previousElectionId`) document the null
  semantics in their `@param`.
- **Production readiness** is documented in the README's
  `## Production readiness` section (commit `b106cfa`): validation
  status, production-ready scope, known limitations.

## v1.x roadmap

Capabilities deferred to point releases of v1.x. The
[`CHANGELOG.md`](../CHANGELOG.md) "Roadmap" section is the
authoritative list; this is the current snapshot.

- **Multi-switch coordination** — a `P4Controller` with deliberate
  fan-out / parallelism / error-aggregation semantics. v1.0 callers
  compose `List<P4Switch>` themselves.
- **Other entity-type reads** — counters, meters, registers, action
  profiles, multicast groups, packet replication.
- **`ReadQuery.where(Predicate<TableEntry>)` / `.fields(...)`** —
  arbitrary client-side filtering and projection.
- **`DeviceConfig.Tofino`** variant alongside `Bmv2` and `Raw`.
- **`sw.onPacketDropped(Consumer<DropEvent>)` hook** for backpressure
  observability.
- **`ReconnectPolicy.preserveRoleOnReconnect()`** for primary
  re-arbitration after auto-reconnect.
- **Digest and IdleTimeout stream-message handlers** (P4Runtime
  spec §7 / §11.4) — currently dropped at the inbound parser.
- **Examples-CI full-output diff** — strengthen example assertions
  from grep-for-distinctive-lines to full-block diff against README
  expected output.

Each item is tracked in the CHANGELOG; community input on priority
is welcome via
[GitHub Discussions](https://github.com/zhh2001/jp4/discussions).
