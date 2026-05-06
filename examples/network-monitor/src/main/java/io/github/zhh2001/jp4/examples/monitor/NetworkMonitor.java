package io.github.zhh2001.jp4.examples.monitor;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.ElectionId;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Passive network monitor — `Flow.Publisher` PacketIn observation + the
 * read-without-primary contract.
 *
 * <p>Demonstrates two distinct things:
 * <ol>
 *   <li><b>The Flow.Publisher subscription pattern</b> for PacketIn — the
 *       reactive style most production HA monitors actually use, with a
 *       backpressure-aware {@link Flow.Subscriber} that tallies stats per
 *       ingress port.</li>
 *   <li><b>Read-without-primary works.</b> A secondary client opens against
 *       the same device with a lower election id, reports {@code Lost}
 *       mastership, and calls {@code loadPipeline()} — a read-only RPC that
 *       succeeds without primary privileges. This validates P4Runtime spec
 *       §6.4 ("a controller can issue Read RPCs whether or not it is the
 *       primary client") against jp4's looser-on-read gate.</li>
 * </ol>
 *
 * <p>The PacketIn observation runs on the primary connection — not on the
 * secondary — because BMv2 delivers PacketIn only to the primary client. Per
 * spec §16.1 PacketIn MUST go to the primary and SHOULD go to backups; BMv2
 * implements only the MUST. On a spec-compliant target that also broadcasts
 * to backups (e.g. expected behaviour for some Tofino/Stratum builds), the
 * same {@code packetInStream().subscribe(...)} code runs unchanged from a
 * secondary. See {@code NOTES.md} for the BMv2 quirk in detail.
 *
 * <p>Two switches in one JVM share the device; in a real HA deployment they
 * would live in different processes / hosts.
 */
public final class NetworkMonitor {

    private static final int N_FRAMES = 30;
    private static final long INJECT_INTERVAL_MS = 30L;

    public static void main(String[] args) throws Exception {
        String address = args.length > 0 ? args[0] : "127.0.0.1:50051";

        P4Info p4info = P4Info.fromBytes(loadResource("/p4/monitor.p4info.txtpb"));
        DeviceConfig dc = new DeviceConfig.Bmv2(loadResource("/p4/monitor.json"));

        try (P4Switch primary = P4Switch.connect(address)
                .electionId(ElectionId.of(10))
                .asPrimary()
                .bindPipeline(p4info, dc)) {
            System.out.println("[MON] primary connected (election_id=10), pipeline pushed");

            // 1. Read-without-primary demonstration: open a secondary just long
            //    enough to prove loadPipeline() succeeds without primary privileges,
            //    then close it. We do this BEFORE the primary subscribes / injects
            //    so its lifecycle is self-contained.
            try (P4Switch secondary = P4Switch.connect(address)
                    .electionId(ElectionId.of(1))
                    .asSecondary()) {
                System.out.println("[MON] secondary connected (election_id=1)");
                System.out.println("[MON] secondary mastership: " + secondary.mastership());
                secondary.loadPipeline();   // read-only RPC; succeeds for non-primary clients
                System.out.println("[MON] secondary loadPipeline() OK — spec §6.4 read-without-primary verified");
            }

            // 2. Flow.Publisher observation — production HA monitors use this
            //    shape; runs on the primary here because BMv2 delivers PacketIn
            //    only to the primary client (see this example's README and
            //    NOTES.md "BMv2 PacketIn delivery is primary-only").
            Stats stats = new Stats();
            primary.packetInStream().subscribe(stats);

            System.out.printf("[MON] injecting %d synthetic frames at %dms intervals…%n",
                    N_FRAMES, INJECT_INTERVAL_MS);
            for (int i = 0; i < N_FRAMES; i++) {
                int simulatedIngressPort = (i % 4) + 1;     // rotate ports 1..4
                primary.send(PacketOut.builder()
                        .payload(syntheticFrame(i))
                        .metadata("egress_port", simulatedIngressPort)
                        .build());
                Thread.sleep(INJECT_INTERVAL_MS);
            }

            // Drain — let the last few PacketIns reach the subscriber.
            Thread.sleep(300);
            stats.print(N_FRAMES);
        }
    }

    private static byte[] syntheticFrame(int seq) {
        // 14-byte ethernet header + small payload; vary the size a bit so the
        // monitor's "average size" output is meaningful.
        int payloadLen = 16 + (seq % 8) * 4;     // 16, 20, 24, … bytes
        byte[] frame = new byte[14 + payloadLen];
        // dst, src — fixed
        frame[0] = (byte) 0xFF; // broadcast dst
        for (int i = 1; i < 6; i++) frame[i] = (byte) 0xFF;
        for (int i = 6; i < 12; i++) frame[i] = (byte) 0x11;
        frame[12] = 0x08; frame[13] = 0x00;     // etherType IPv4 (just so we have one)
        for (int i = 14; i < frame.length; i++) frame[i] = (byte) (seq + i);
        return frame;
    }

    private static byte[] loadResource(String path) throws Exception {
        try (InputStream in = NetworkMonitor.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("classpath resource not found: " + path);
            return in.readAllBytes();
        }
    }

    /** Per-port (count, total bytes) accumulator + Flow.Subscriber adapter. */
    static final class Stats implements Flow.Subscriber<PacketIn> {

        private final Map<Integer, AtomicLong> countsByPort  = new ConcurrentHashMap<>();
        private final Map<Integer, AtomicLong> bytesByPort   = new ConcurrentHashMap<>();
        private final AtomicLong total = new AtomicLong();

        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

        @Override
        public void onNext(PacketIn p) {
            int port = p.metadataInt("ingress_port");
            int bytes = p.payload().length();
            countsByPort.computeIfAbsent(port, k -> new AtomicLong()).incrementAndGet();
            bytesByPort.computeIfAbsent(port, k -> new AtomicLong()).addAndGet(bytes);
            total.incrementAndGet();
        }

        @Override public void onError(Throwable t) {
            System.err.println("[MON] subscriber error: " + t);
        }

        @Override public void onComplete() {
            System.out.println("[MON] stream completed");
        }

        void print(int expected) {
            System.out.printf("[MON] observed %d / %d expected packets%n",
                    total.get(), expected);
            countsByPort.keySet().stream().sorted().forEach(port -> {
                long n = countsByPort.get(port).get();
                long b = bytesByPort.get(port).get();
                System.out.printf("[MON]   port %d: %d packets, %d bytes total, %d avg%n",
                        port, n, b, n == 0 ? 0 : b / n);
            });
        }
    }
}
