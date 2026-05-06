package io.github.zhh2001.jp4.examples.loadbalancer;

import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.Ip4;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LPM load balancer — controller side.
 *
 * <p>Demonstrates: bulk write via batch, periodic read-back to verify state,
 * runtime modify to change a backend mapping, async send. Maps to v3 §5
 * Scenarios B (loadPipeline / pipeline acceptance), C (insert / modify),
 * and D (batch).
 *
 * <p>Operating model:
 * <ol>
 *   <li>Connect, push the {@code loadbalancer.p4} pipeline.</li>
 *   <li>Pre-populate {@code backend_lookup} with three /24 prefixes mapped to
 *       three backend ports — done as a single Write RPC via {@code sw.batch()}.</li>
 *   <li>Read back the table, print what the device reports.</li>
 *   <li>Modify one route's egress port at runtime to demonstrate
 *       {@code sw.modify(...)}.</li>
 *   <li>Read back again to confirm the modification took effect.</li>
 * </ol>
 *
 * <p>Each route is an {@code (ip-prefix /24) → backend-port} pair; ports are
 * notional (1, 2, 3). The example does not inject IP traffic — verification is
 * by reading the device's own state, which exercises jp4's read RPC + reverse
 * parse end-to-end.
 */
public final class SimpleLoadbalancer {

    /** Initial route table — three /24 prefixes, three backend ports. */
    private static final Map<String, Integer> INITIAL_ROUTES = new LinkedHashMap<>();
    static {
        INITIAL_ROUTES.put("10.0.1.0/24", 1);
        INITIAL_ROUTES.put("10.0.2.0/24", 2);
        INITIAL_ROUTES.put("10.0.3.0/24", 3);
    }

    public static void main(String[] args) throws Exception {
        String address = args.length > 0 ? args[0] : "127.0.0.1:50051";

        P4Info p4info = P4Info.fromBytes(loadResource("/p4/loadbalancer.p4info.txtpb"));
        DeviceConfig dc = new DeviceConfig.Bmv2(loadResource("/p4/loadbalancer.json"));

        try (P4Switch sw = P4Switch.connectAsPrimary(address).bindPipeline(p4info, dc)) {
            System.out.println("[LB] connected as primary on " + address + ", pipeline pushed");

            // 1. Batch-install initial routes.
            var batch = sw.batch();
            for (Map.Entry<String, Integer> r : INITIAL_ROUTES.entrySet()) {
                batch.insert(routeEntry(r.getKey(), r.getValue()));
            }
            var result = batch.execute();
            System.out.printf("[LB] installed %d routes (allSucceeded=%s)%n",
                    result.submitted(), result.allSucceeded());

            // 2. Read back and print.
            printRoutes(sw, "after install");

            // 3. Modify one route: 10.0.2.0/24 → port 4 (was port 2).
            String movedPrefix = "10.0.2.0/24";
            int newPort = 4;
            sw.modify(routeEntry(movedPrefix, newPort));
            System.out.printf("[LB] moved %s to port %d%n", movedPrefix, newPort);

            // 4. Read back and confirm.
            printRoutes(sw, "after modify");

            // 5. Cleanup so re-runs do not see ALREADY_EXISTS errors.
            var cleanup = sw.batch();
            for (String prefix : INITIAL_ROUTES.keySet()) {
                int port = prefix.equals(movedPrefix) ? newPort : INITIAL_ROUTES.get(prefix);
                cleanup.delete(routeEntry(prefix, port));
            }
            cleanup.execute();
            System.out.println("[LB] cleaned up; goodbye");
        }
    }

    // SNIPPET_START routeEntry
    private static TableEntry routeEntry(String cidr, int port) {
        String[] parts = cidr.split("/");
        Ip4 prefix = Ip4.of(parts[0]);
        int prefixLen = Integer.parseInt(parts[1]);
        return TableEntry.in("MyIngress.backend_lookup")
                .match("hdr.ipv4.dstAddr", new Match.Lpm(prefix.toBytes(), prefixLen))
                .action("MyIngress.forward").param("port", port)
                .build();
    }
    // SNIPPET_END routeEntry

    private static void printRoutes(P4Switch sw, String label) {
        List<TableEntry> got = sw.read("MyIngress.backend_lookup").all();
        System.out.printf("[LB] backend_lookup %s: %d entries%n", label, got.size());
        for (TableEntry e : got) {
            Match.Lpm key = (Match.Lpm) e.match("hdr.ipv4.dstAddr");
            Bytes portBytes = e.action().param("port");
            int port = portBytes == null ? -1 : new java.math.BigInteger(1, portBytes.toByteArray()).intValue();
            System.out.printf("[LB]   %s/%d → port %d%n",
                    formatIpv4(key.value().canonical().toByteArray()),
                    key.prefixLen(), port);
        }
    }

    private static String formatIpv4(byte[] canonical) {
        // canonical may be < 4 bytes (leading zeros stripped); pad to 4.
        byte[] full = new byte[4];
        System.arraycopy(canonical, 0, full, 4 - canonical.length, canonical.length);
        return String.format("%d.%d.%d.%d",
                full[0] & 0xff, full[1] & 0xff, full[2] & 0xff, full[3] & 0xff);
    }

    private static byte[] loadResource(String path) throws Exception {
        try (InputStream in = SimpleLoadbalancer.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("classpath resource not found: " + path);
            return in.readAllBytes();
        }
    }
}
