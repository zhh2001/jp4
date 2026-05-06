package io.github.zhh2001.jp4.examples.l2switch;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Mac;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 learning switch — controller side.
 *
 * <p>Demonstrates: connect + bindPipeline, table writes, PacketIn handler,
 * PacketOut send. Maps to v3 §5 Scenarios A (connect/pipeline), C (table
 * insert), and E (Packet I/O).
 *
 * <p>Operating model:
 * <ol>
 *   <li>Connect to BMv2 as primary and push the {@code simple_l2.p4} pipeline.</li>
 *   <li>Subscribe to PacketIn via {@code onPacketIn(...)}: every packet that
 *       missed the L2 table arrives here with the original {@code ingress_port}
 *       in metadata.</li>
 *   <li>For each PacketIn, learn {@code srcAddr → ingress_port} into a local
 *       map and write a forwarding entry for that {@code srcAddr} into
 *       {@code l2_forward} so future traffic to that MAC short-circuits the
 *       data plane without bouncing through the controller.</li>
 *   <li>Flood the unknown-dst packet to all other configured ports by sending
 *       one PacketOut per port, with {@code egress_port} set explicitly.</li>
 * </ol>
 *
 * <p>This demo doubles jp4 as a traffic source — the {@code main} method
 * injects a few synthetic frames after a short warm-up so users can see the
 * learn-and-forward cycle on their own machine without external tooling.
 */
public final class SimpleL2Switch {

    /** MAC → ingress_port table built from PacketIns. ConcurrentHashMap because the
     *  callback runs on a different thread than main(). */
    private final Map<String, Integer> learned = new ConcurrentHashMap<>();

    private final P4Switch sw;

    SimpleL2Switch(P4Switch sw) {
        this.sw = sw;
    }

    public static void main(String[] args) throws Exception {
        String address = args.length > 0 ? args[0] : "127.0.0.1:50051";

        P4Info p4info = P4Info.fromBytes(loadResource("/p4/simple_l2.p4info.txtpb"));
        DeviceConfig dc = new DeviceConfig.Bmv2(loadResource("/p4/simple_l2.json"));

        try (P4Switch sw = P4Switch.connectAsPrimary(address).bindPipeline(p4info, dc)) {
            System.out.println("[L2] connected as primary on " + address + ", pipeline pushed");

            SimpleL2Switch app = new SimpleL2Switch(sw);
            sw.onPacketIn(app::handlePacketIn);

            // Inject demo traffic so the learn cycle is observable on a single host
            // without external traffic injection. The simple_l2.p4 program treats the
            // controller-supplied PacketOut.egress_port as the simulated ingress port
            // for the loopback demo (see the P4 source comment); both frames target a
            // broadcast destination, so each misses l2_forward and triggers
            // flood_via_cpu → PacketIn → learning.
            Thread.sleep(500);   // let the stream be fully active before we start sending
            sendDemoFrame(sw, mac("AA:00:00:00:00:01"), mac("FF:FF:FF:FF:FF:FF"), 1);
            Thread.sleep(300);
            sendDemoFrame(sw, mac("BB:00:00:00:00:02"), mac("FF:FF:FF:FF:FF:FF"), 2);
            Thread.sleep(800);

            System.out.println("[L2] learned table: " + app.learned);
        }
    }

    /** Called once per PacketIn — runs on the jp4 callback executor. The handler
     *  learns srcAddr ↔ ingress_port from the PacketIn metadata and writes a
     *  forwarding entry for srcAddr. Production controllers would also flood the
     *  unknown-dst frame to other ports; this demo skips that step for output
     *  clarity (the focus is on the learn-and-write cycle). */
    void handlePacketIn(PacketIn packet) {
        int ingressPort = packet.metadataInt("ingress_port");
        byte[] frame = packet.payload().toByteArray();
        if (frame.length < 12) {
            System.err.println("[L2] runt frame, len=" + frame.length + "; ignored");
            return;
        }
        String dst = formatMac(frame, 0);
        String src = formatMac(frame, 6);
        System.out.printf("[L2] PacketIn  src=%s dst=%s ingress=%d%n", src, dst, ingressPort);

        // Learn src → ingress_port once. Subsequent PacketIns for the same src
        // (e.g. from genuine topology changes) would call modify() in production;
        // this demo is content with first-write-wins.
        if (learned.putIfAbsent(src, ingressPort) == null) {
            installForwardEntry(src, ingressPort);
        }
    }

    private void installForwardEntry(String srcMac, int ingressPort) {
        TableEntry e = TableEntry.in("MyIngress.l2_forward")
                .match("hdr.ethernet.dstAddr", Mac.of(srcMac))
                .action("MyIngress.send_to_port").param("port", ingressPort)
                .build();
        try {
            sw.insert(e);
            System.out.printf("[L2] LEARN     %s → port %d (entry installed)%n",
                    srcMac, ingressPort);
        } catch (RuntimeException duplicateOrTransient) {
            // Best effort: a duplicate insert (from an out-of-order PacketIn race) is
            // benign for this demo. A controller in production would modify() instead.
            System.out.printf("[L2] LEARN     %s → port %d (insert skipped: %s)%n",
                    srcMac, ingressPort, duplicateOrTransient.getMessage());
        }
    }

    private static void sendDemoFrame(P4Switch sw, Mac src, Mac dst, int simulatedIngressPort) {
        byte[] frame = ethFrame(src, dst, /*etherType*/ 0x0800,
                "demo-l2-payload".getBytes());
        // simple_l2.p4 reads PacketOut.egress_port as the simulated ingress port for
        // this loopback demo, so the table sees the frame as if it ingressed on that
        // port. See the P4 source for why the field is repurposed.
        sw.send(PacketOut.builder()
                .payload(frame)
                .metadata("egress_port", simulatedIngressPort)
                .build());
        System.out.printf("[L2] inject    src=%s dst=%s via simulated ingress %d%n",
                src, dst, simulatedIngressPort);
    }

    private static byte[] ethFrame(Mac src, Mac dst, int etherType, byte[] payload) {
        byte[] hdr = new byte[14];
        System.arraycopy(dst.toBytes().toByteArray(), 0, hdr, 0, 6);
        System.arraycopy(src.toBytes().toByteArray(), 0, hdr, 6, 6);
        hdr[12] = (byte) (etherType >> 8);
        hdr[13] = (byte) etherType;
        byte[] frame = new byte[hdr.length + payload.length];
        System.arraycopy(hdr, 0, frame, 0, hdr.length);
        System.arraycopy(payload, 0, frame, hdr.length, payload.length);
        return frame;
    }

    private static String formatMac(byte[] buf, int offset) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", buf[offset + i] & 0xff));
        }
        return sb.toString();
    }

    private static Mac mac(String s) { return Mac.of(s); }

    private static byte[] loadResource(String path) throws Exception {
        try (InputStream in = SimpleL2Switch.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("classpath resource not found: " + path);
            return in.readAllBytes();
        }
    }
}
