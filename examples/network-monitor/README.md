# NetworkMonitor

Two-tier passive monitor: a primary controller pushes the pipeline and injects
synthetic traffic; a separate **secondary** controller (read-only) subscribes
to PacketIn via `Flow.Publisher` and tallies per-port packet counts and
average sizes.

## What this demonstrates in jp4

- Connecting as **secondary** with a lower election id (`asSecondary()`,
  no primary privileges).
- The looser-on-read gating contract: secondaries can read and subscribe to
  PacketIn without holding mastership (P4Runtime spec §6.4).
- `loadPipeline()` as the read-side complement of `bindPipeline(...)` —
  fetches the device's currently-installed P4Info into the local switch so
  the inbound packet parser has the metadata schema it needs.
- `Flow.Publisher` consumption with a `Flow.Subscriber<PacketIn>` (vs. the
  `onPacketIn(...)` callback shown in `simple-l2-switch`).
- Multi-switch JVM lifecycle (two `P4Switch` instances against one device,
  each in its own try-with-resources).

Maps to v3 §5 Scenarios A, E, and F.

## Prerequisites

- **Java 21+**.
- **Docker** with `p4lang/behavioral-model` reachable.
- See [`../README.md`](../README.md) for the one-line `docker run`.

## Run

```bash
cd examples
./gradlew :network-monitor:run
```

Optional: `--args="my-bmv2-host:50051"` to override the device address.

## Expected output

```
[MON] primary connected (election_id=10), pipeline pushed
[MON] secondary connected (election_id=1)
[MON] secondary mastership: Lost[previousElectionId=null, currentPrimaryElectionId=ElectionId(10)]
[MON] secondary loadPipeline() OK — spec §6.4 read-without-primary verified
[MON] injecting 30 synthetic frames at 30ms intervals…
[MON] observed 30 / 30 expected packets
[MON]   port 1: 8 packets, 304 bytes total, 38 avg
[MON]   port 2: 8 packets, 336 bytes total, 42 avg
[MON]   port 3: 7 packets, 314 bytes total, 44 avg
[MON]   port 4: 7 packets, 342 bytes total, 48 avg
[MON] stream completed
```

The `secondary mastership: Lost[...]` line + `loadPipeline() OK` line are
the proof points — a secondary client (lower election id) successfully
issued a read-only RPC without holding primary, validating P4Runtime spec
§6.4. Per-port counts are determined by the demo's port-rotation pattern
(`(seq % 4) + 1`); exact byte totals depend on the synthetic frame sizes
the loop produces.

## Try this next

- Comment out the `monitor.loadPipeline()` call and re-run. PacketIns will
  arrive but be dropped during dispatch (see the SLF4J warn: "no pipeline
  bound; dropping PacketIn"). This is the inbound parser's safety net when
  the local switch has no metadata schema to interpret PacketIn against.
- Cancel the subscription mid-run by capturing the `Subscription` in
  `onSubscribe` and calling `cancel()` after, say, 10 packets — the primary
  will keep injecting but the subscriber stops receiving.
- Open the same secondary connection twice (two subscribers on two switches)
  to confirm BMv2 broadcasts PacketIn to both.

## Why the observation runs on the primary

P4Runtime spec §16.1 says PacketIn **MUST** be sent to the primary client
and **SHOULD** be sent to backups. BMv2 implements only the MUST — it
delivers PacketIn to the primary and nothing to secondaries. So the
observation half of this example (the `Flow.Subscriber` for stats) lives
on the primary connection.

The secondary's lifecycle in this example is brief by design: it connects,
its mastership reads as `Lost`, it issues a read-only RPC (`loadPipeline()`)
that succeeds, and it closes. That's the spec §6.4 demonstration —
"a controller can issue Read RPCs whether or not it is the primary client".

On a target that **does** broadcast PacketIn to backups (some Tofino /
Stratum builds), the same `Flow.Publisher` subscriber code attaches
unchanged to the secondary instead of the primary. The Java code does not
know or care which connection it is on. See the project's `NOTES.md` entry
"BMv2 PacketIn delivery is primary-only" for the empirical detail.

## On the two-switch pattern

In production, the primary and any secondaries usually live in different
processes or hosts. This example folds both into one JVM only so the demo
is self-contained — the secondary's `try-with-resources` block is what you
would deploy as a stand-alone observer process against a target that
broadcasts PacketIn.
