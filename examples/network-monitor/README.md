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
[MON] secondary mastership: Lost(...)
[MON] secondary loaded pipeline from device
[MON] injecting 30 synthetic frames at 30ms intervals…
[MON] observed 30 / 30 expected packets
[MON]   port 1: 8 packets, 184 bytes total, 23 avg
[MON]   port 2: 8 packets, 200 bytes total, 25 avg
[MON]   port 3: 7 packets, 175 bytes total, 25 avg
[MON]   port 4: 7 packets, 168 bytes total, 24 avg
```

The `secondary mastership: Lost(...)` line is the proof point — the
secondary's mastership status reads as not-primary, yet it still received
every PacketIn. Per-port counts are determined by the demo's port-rotation
pattern (`(seq % 4) + 1`); exact byte totals depend on the synthetic frame
sizes the loop produces.

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

## On the two-switch pattern

In production, the primary controller usually lives in another process or
host; the monitor connects with its own credentials and never claims
primary. This example folds both into one JVM only so the demo is
self-contained — the same `monitor` switch code (the inner try-with-resources
block) is what you would deploy as a stand-alone secondary.
