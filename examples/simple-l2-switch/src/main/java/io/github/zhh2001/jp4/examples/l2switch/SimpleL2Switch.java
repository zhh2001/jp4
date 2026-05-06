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

    /** Ports we treat as "front-panel"; the controller floods to these on miss. */
    private static final int[] FRONT_PORTS = { 1, 2, 3, 4 };

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

            // Inject demo traffic so the learn-and-forward cycle is observable.
            // Frame 1: src=AA:00:00:00:00:01, dst=BB:00:00:00:00:02 from port 1.
            //   → controller learns AA:..:01@1, floods (no entry for BB:..:02 yet).
            // Frame 2: src=BB:00:00:00:00:02, dst=AA:00:00:00:00:01 from port 2.
            //   → controller learns BB:..:02@2 AND finds AA:..:01@1 already learned;
            //     the data-plane table now has AA:..:01@1 from frame 1, so frame 2's
            //     reply (and any subsequent traffic to AA:..:01) takes the short path.
            Thread.sleep(500);   // let the stream be fully active before we start sending
            sendDemoFrame(sw, mac("AA:00:00:00:00:01"), mac("BB:00:00:00:00:02"), 1);
            Thread.sleep(300);
            sendDemoFrame(sw, mac("BB:00:00:00:00:02"), mac("AA:00:00:00:00:01"), 2);
            Thread.sleep(800);

            System.out.println("[L2] learned table: " + app.learned);
        }
    }

    /** Called once per PacketIn — runs on the jp4 callback executor. */
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

        // 1. Learn src → ingress_port, install a forwarding entry for src.
        Integer previous = learned.put(src, ingressPort);
        if (previous == null || previous != ingressPort) {
            installForwardEntry(src, ingressPort);
        }

        // 2. Flood out every front port that is NOT the ingress.
        for (int port : FRONT_PORTS) {
            if (port == ingressPort) continue;
            sendOut(frame, port);
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

    private void sendOut(byte[] frame, int egressPort) {
        sw.send(PacketOut.builder()
                .payload(frame)
                .metadata("egress_port", egressPort)
                .build());
    }

    private static void sendDemoFrame(P4Switch sw, Mac src, Mac dst, int ingressPort) {
        byte[] frame = ethFrame(src, dst, /*etherType*/ 0x0800,
                "demo-l2-payload".getBytes());
        // Set ingress_port via egress_port metadata trick? No — for this demo we want
        // BMv2 to treat the packet as if it ingressed on FRONT_PORTS[ingressPort]. The
        // simplest path is to send to that port and let BMv2 process it as a regular
        // packet (PacketOut.egress_port forwards there; the next packet on that port
        // ingresses normally). For self-contained reproducibility we accept that the
        // demo's "ingress" is whatever BMv2 reports.
        sw.send(PacketOut.builder()
                .payload(frame)
                .metadata("egress_port", ingressPort)
                .build());
        System.out.printf("[L2] inject    src=%s dst=%s via port %d%n", src, dst, ingressPort);
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
