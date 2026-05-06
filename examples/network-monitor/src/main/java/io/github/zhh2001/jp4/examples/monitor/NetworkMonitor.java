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
 * Passive network monitor — secondary controller observing the data plane.
 *
 * <p>Demonstrates the two-tier HA-monitor pattern: a primary controller owns
 * the device (pushes pipeline, can write), and a secondary observer reads
 * PacketIn from the same StreamChannel without primary privileges. P4Runtime
 * spec §6.4 permits secondaries to read; jp4's {@code readabilityException}
 * gate is correspondingly looser than the write gate (closed/broken only,
 * no mastership check).
 *
 * <p>Operating model:
 * <ol>
 *   <li>Open a primary connection (election_id=10), push the {@code monitor.p4}
 *       pipeline.</li>
 *   <li>Open a secondary connection (election_id=1) on the same address. Confirm
 *       its mastership status reports "lost" (i.e. not primary).</li>
 *   <li>The secondary calls {@code loadPipeline()} — a read-only RPC that
 *       fetches the device's current P4Info — so its inbound packet parser has
 *       the metadata schema it needs. (Secondaries cannot {@code bindPipeline}
 *       because that's a write.)</li>
 *   <li>Subscribe the secondary to {@code packetInStream()} via a
 *       {@link Flow.Subscriber}.</li>
 *   <li>The primary injects synthetic Ethernet frames via
 *       {@code primary.send(PacketOut)}; BMv2's monitor.p4 unconditionally
 *       sends every ingress packet to the controller, so each PacketOut comes
 *       back as a PacketIn observable by the secondary subscriber.</li>
 *   <li>The subscriber tallies (count, total bytes) per ingress port and
 *       prints a summary at the end.</li>
 * </ol>
 *
 * <p>This is a "two switches in one JVM, same device" pattern; in production
 * the primary lives in another process or another host. Maps to v3 §5
 * Scenarios A (connect/pipeline), E (Packet I/O via Flow.Publisher), and F
 * (lifecycle / mastership).
 */
public final class NetworkMonitor {

    private static final int N_FRAMES = 30;
    private static final long INJECT_INTERVAL_MS = 30L;

    public static void main(String[] args) throws Exception {
        String address = args.length > 0 ? args[0] : "127.0.0.1:50051";

        P4Info p4info = P4Info.fromBytes(loadResource("/p4/monitor.p4info.txtpb"));
        DeviceConfig dc = new DeviceConfig.Bmv2(loadResource("/p4/monitor.json"));

        // Primary: pushes pipeline, owns write side, injects synthetic traffic.
        try (P4Switch primary = P4Switch.connect(address)
                .electionId(ElectionId.of(10))
                .asPrimary()
                .bindPipeline(p4info, dc)) {
            System.out.println("[MON] primary connected (election_id=10), pipeline pushed");

            // Secondary: monitor — read-only.
            try (P4Switch monitor = P4Switch.connect(address)
                    .electionId(ElectionId.of(1))
                    .asSecondary()) {
                System.out.println("[MON] secondary connected (election_id=1)");
                System.out.println("[MON] secondary mastership: " + monitor.mastership());

                // Secondary cannot bindPipeline (it's a write); fetch the
                // pipeline the primary already pushed.
                monitor.loadPipeline();
                System.out.println("[MON] secondary loaded pipeline from device");

                Stats stats = new Stats();
                monitor.packetInStream().subscribe(stats);

                System.out.printf("[MON] injecting %d synthetic frames at %dms intervals…%n",
                        N_FRAMES, INJECT_INTERVAL_MS);
                for (int i = 0; i < N_FRAMES; i++) {
                    int ingressPort = (i % 4) + 1;   // rotate across ports 1..4
                    primary.send(PacketOut.builder()
                            .payload(syntheticFrame(i))
                            .build());     // monitor.p4 has no packet_out header
                    Thread.sleep(INJECT_INTERVAL_MS);
                }

                // Drain — let the last few PacketIns reach the subscriber.
                Thread.sleep(300);
                stats.print(N_FRAMES);
            }
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
