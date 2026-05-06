# Pipelines

A P4Runtime device runs a P4 program; jp4 calls the install of that
program a **pipeline**. Pushing a pipeline gives the device its forwarding
behaviour and gives jp4 the schema (P4Info) needed to translate
name-based API calls into wire-level numeric ids.

## What jp4 needs

Two artefacts, both produced by the P4 compiler (`p4c`):

- **P4Info** — the schema. Lists every table, action, match field, and
  `controller_packet_metadata` declaration with its name, numeric id,
  and bit width. P4Runtime defines this as a `p4.config.v1.P4Info`
  protobuf; jp4 wraps it as the `P4Info` class.
- **Device config** — the target-specific binary the device executes.
  For BMv2 this is the `.json` produced by `p4c-bm2-ss`. jp4 wraps it
  in `DeviceConfig.Bmv2` (BMv2 JSON) or `DeviceConfig.Raw` (escape
  hatch for any other target).

Both are usually compiled once and shipped as resources alongside your
controller. Loading them:

<!-- illustrative -->

```java
P4Info p4info = P4Info.fromFile(Path.of("…/myprog.p4info.txtpb"));
DeviceConfig dc = DeviceConfig.Bmv2.fromFile(Path.of("…/myprog.json"));
```

*Real usage: [`simple-loadbalancer`](../examples/simple-loadbalancer/).*

`P4Info.fromFile(...)` auto-detects whether the file is binary protobuf
or P4Runtime text format. `P4Info.fromBytes(byte[])` accepts the same
formats from any byte source (resource, network, generated).

## bindPipeline vs loadPipeline

Two operations, two different relationships with the device:

**`bindPipeline(p4info, dc)`** — *push.* The primary client tells the
device what pipeline to run. The wire RPC is
`SetForwardingPipelineConfig(VERIFY_AND_COMMIT)`; the call blocks until
the device acknowledges. Throws `P4ConnectionException` if the switch
isn't primary, `P4PipelineException` if the device rejects the pipeline.

<!-- illustrative -->

```java
sw.bindPipeline(p4info, dc);   // primary only
```

*Real usage: [`simple-l2-switch`](../examples/simple-l2-switch/).*

**`loadPipeline()`** — *pull.* The client fetches whatever pipeline is
already installed on the device, populates its local P4Info reference,
and returns. No write. Secondaries use this to populate their schema
without holding primary.

<!-- illustrative -->

```java
sw.loadPipeline();   // primary or secondary
```

*Real usage: [`network-monitor`](../examples/network-monitor/).*

Both methods return `P4Switch` so they compose with the connect chain:

<!-- illustrative -->

```java
try (P4Switch sw = P4Switch.connectAsPrimary(addr).bindPipeline(p4info, dc)) {
    // ...
}

try (P4Switch monitor = P4Switch.connect(addr).electionId(...).asSecondary()) {
    monitor.loadPipeline();
    // ...
}
```

*Real usage: [`simple-l2-switch`](../examples/simple-l2-switch/).*

## P4Info as a name index

Once a pipeline is bound, the rest of jp4's API takes names, not ids:

<!-- illustrative -->

```java
sw.read("MyIngress.ipv4_lpm").all();             // table name
sw.insert(TableEntry.in("MyIngress.ipv4_lpm")    // table name
        .match("hdr.ipv4.dstAddr",               // match-field name
               new Match.Lpm(...))
        .action("MyIngress.forward")             // action name
        .param("port", 1)                        // param name
        .build());
```

*Real usage: [`simple-loadbalancer`](../examples/simple-loadbalancer/).*

Misspelled names fail fast at the call site with a known-list error
message, so a typo never reaches the device:

```
P4PipelineException: Field 'hdr.ipv4.bogus' not found in table
'MyIngress.ipv4_lpm'. Known fields: [hdr.ipv4.dstAddr, …]
```

The P4Info object also exposes the schema directly if you need to walk
it (e.g. building a tooling layer):

<!-- illustrative: concept fragment -->

```java
for (TableInfo t : p4info.tableNames().stream()
        .map(p4info::table).toList()) {
    System.out.println(t.name() + " (" + t.id() + ")");
    for (MatchFieldInfo mf : t.matchFields()) {
        System.out.println("  " + mf.name()
                + " " + mf.matchKind() + " " + mf.bitWidth() + " bits");
    }
}
```

## DeviceConfig variants

`DeviceConfig` is a sealed type:

- **`DeviceConfig.Bmv2`** wraps a BMv2 JSON byte array. Use this for
  any BMv2-flavoured target (`simple_switch_grpc`, `simple_switch`,
  derivatives).
- **`DeviceConfig.Raw`** is the escape hatch for any other target. The
  device-side parsing is the device's problem; jp4 just ships the bytes.

Both have `fromFile(Path)` factories. v0.2 is expected to add
`DeviceConfig.Tofino` for the SDE; pull-requests welcome.

## Pipeline drift between client and device

When the client's bound P4Info disagrees with the device (e.g. someone
hot-swapped the pipeline out from under you), incoming PacketIns may
carry metadata ids the client cannot decode. jp4 surfaces this loudly
on the read-side reverse parse:

```
P4PipelineException: device returned table id 12345678 which is not in
the bound P4Info; pipeline may have drifted since bindPipeline (known
table ids: [33554497, 33554498, …])
```

Per-packet, the dispatch logs a SLF4J warn and drops the packet rather
than poisoning the whole stream. If this fires in production, your
client and device pipelines have desynced — re-`loadPipeline()` or
re-`bindPipeline(...)` to recover.

## See also

- [Tables](tables.md) — uses the bound P4Info for every name-based
  lookup.
- [Packet I/O](packet-io.md) — `controller_packet_metadata` declarations
  feed the PacketIn / PacketOut codec.
- [`examples/simple-loadbalancer/`](../examples/simple-loadbalancer/)
  shows `bindPipeline` + table operations end-to-end.
- [`examples/network-monitor/`](../examples/network-monitor/) shows
  the secondary `loadPipeline()` pattern.
