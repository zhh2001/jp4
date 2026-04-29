package io.github.zhh2001.jp4.spec;

import io.github.zhh2001.jp4.BatchBuilder;
import io.github.zhh2001.jp4.MastershipStatus;
import io.github.zhh2001.jp4.P4Switch;
import io.github.zhh2001.jp4.Pipeline;
import io.github.zhh2001.jp4.ReadQuery;
import io.github.zhh2001.jp4.ReconnectPolicy;
import io.github.zhh2001.jp4.WriteResult;
import io.github.zhh2001.jp4.entity.PacketIn;
import io.github.zhh2001.jp4.entity.PacketOut;
import io.github.zhh2001.jp4.entity.TableEntry;
import io.github.zhh2001.jp4.entity.UpdateFailure;
import io.github.zhh2001.jp4.error.P4ConnectionException;
import io.github.zhh2001.jp4.error.P4OperationException;
import io.github.zhh2001.jp4.error.P4PipelineException;
import io.github.zhh2001.jp4.error.P4RuntimeException;
import io.github.zhh2001.jp4.match.Match;
import io.github.zhh2001.jp4.pipeline.DeviceConfig;
import io.github.zhh2001.jp4.pipeline.P4Info;
import io.github.zhh2001.jp4.types.Bytes;
import io.github.zhh2001.jp4.types.ElectionId;
import io.github.zhh2001.jp4.types.Ip4;
import io.github.zhh2001.jp4.types.Mac;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p><b>Maintenance contract.</b> This test is tightly coupled to
 * {@code docs/api-design.md}. When scenarios are added, removed, or changed in the
 * design document, the corresponding compile-only methods and {@code @Test} methods
 * in this class must be kept in sync — otherwise the test silently drifts away from
 * the design it is meant to enforce.
 *
 * <p>Two layers of validation against {@code docs/api-design.md} v3:
 *
 * <ol>
 *   <li><b>Compile validation.</b> Each {@code scenarioX_compiles()} private method
 *       contains the verbatim code from the corresponding v3 §5 scenario. The methods
 *       are never executed; their existence forces the compiler to confirm that every
 *       jp4 type and method signature exposed in the design exists with the right
 *       shape. If a v3 example stops compiling, the design and the implementation have
 *       drifted apart.</li>
 *   <li><b>Behaviour validation.</b> The {@code @Test} methods assert that the first
 *       call in each scenario throws {@link UnsupportedOperationException}, confirming
 *       the 4A skeleton is intentionally not implemented yet. Later sub-phases delete
 *       these assertions one scenario at a time as real behaviour lands.</li>
 * </ol>
 *
 * <p>Reactor / RxJava bridge snippets in v3 §5 Scenario E are commented out below
 * because jp4 does not depend on those libraries; they are documentation, not API.
 */
class SpecComplianceTest {

    // ===================================================================================
    // Compile validation — never executed; the compiler is the assertion.
    // ===================================================================================

    @SuppressWarnings({"unused", "EmptyTryBlock", "try"})
    private void scenarioA_compiles() {
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
    }

    @SuppressWarnings({"unused", "EmptyTryBlock", "try"})
    private void scenarioB_compiles() {
        String addr = "127.0.0.1:50051";

        P4Info p4info = P4Info.fromFile(Path.of("basic.p4info.txt"));

        try (P4Switch sw = P4Switch.connectAsPrimary(addr).loadPipeline()) {
            P4Info live = sw.pipeline().p4info();
        }
    }

    @SuppressWarnings({"unused", "try"})
    private void scenarioC_compiles() {
        P4Switch sw = null;   // never executed; type-checking only.

        TableEntry exact = TableEntry.in("MyIngress.dmac")
                .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
                .action("MyIngress.set_egress").param("port", 1)
                .build();
        sw.insert(exact);

        TableEntry lpm = TableEntry.in("MyIngress.ipv4_lpm")
                .match("hdr.ethernet.etherType", 0x0800)
                .match("hdr.ipv4.dstAddr",       Match.Lpm.of("10.0.0.0/24"))
                .action("MyIngress.forward")
                    .param("port",    2)
                    .param("nextHop", Mac.of("aa:bb:cc:dd:ee:ff"))
                .build();
        sw.insert(lpm);

        TableEntry acl = TableEntry.in("MyIngress.acl")
                .match("hdr.ipv4.protocol", Match.Ternary.of(0x06, 0xff))
                .match("hdr.tcp.dstPort",   Match.Range.of(1024, 65535))
                .action("MyIngress.allow")
                .priority(100)
                .build();
        sw.insert(acl);

        sw.modify(TableEntry.in("MyIngress.dmac")
                .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
                .action("MyIngress.set_egress").param("port", 5)
                .build());

        sw.delete(TableEntry.in("MyIngress.dmac")
                .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
                .build());

        List<TableEntry> all = sw.read("MyIngress.dmac").all();

        Optional<TableEntry> hit = sw.read("MyIngress.dmac")
                .match("hdr.ethernet.dstAddr", Mac.of("de:ad:be:ef:00:01"))
                .one();

        List<TableEntry> tcp = sw.read("MyIngress.acl")
                .match("hdr.ipv4.protocol", Match.Ternary.of(0x06, 0xff))
                .all();

        try (Stream<TableEntry> s = sw.read("MyIngress.dmac").stream()) {
            long drops = s.filter(e -> e.action().name().equals("MyIngress.drop_pkt")).count();
        }

        TableEntry e = hit.orElseThrow();
        String desc = switch (e.match("hdr.ipv4.dstAddr")) {
            case Match.Exact x    -> "exact:"   + x.value();
            case Match.Lpm   l    -> "lpm:"     + l.value() + "/" + l.prefixLen();
            case Match.Ternary t  -> "ternary:" + t.value() + "&" + t.mask();
            case Match.Range r    -> "range:"   + r.low() + ".." + r.high();
            case Match.Optional o -> "opt:"     + o.value();
        };
    }

    @SuppressWarnings("unused")
    private void scenarioD_compiles() {
        P4Switch sw = null;

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
                // log.warn("update[{}] failed: {} {}", f.index(), f.code(), f.message());
            }
        }
    }

    @SuppressWarnings("unused")
    private void scenarioE_compiles() {
        P4Switch sw = null;
        byte[] rawBytes = new byte[0];

        sw.onPacketIn(packet -> {
            int ingressPort = packet.metadataInt("ingress_port");
            // handle(ingressPort, packet.payload().toByteArray());
        });

        sw.send(PacketOut.builder()
                .payload(rawBytes)
                .metadata("egress_port", 1)
                .build());

        sw.packetInStream().subscribe(new Flow.Subscriber<PacketIn>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(PacketIn p)              { /* ... */ }
            @Override public void onError(Throwable t)            { /* ... */ }
            @Override public void onComplete()                    { }
        });

        try {
            PacketIn p = sw.pollPacketIn(Duration.ofSeconds(1));
            if (p != null) { /* ... */ }
        } catch (InterruptedException ignored) {
        }

        // Reactor / RxJava bridge examples from v3 §5 Scenario E are documentation
        // only — jp4 doesn't depend on Reactor or RxJava, so they aren't compiled here.
        // // Flux<PacketIn> flux = reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(sw.packetInStream());
        // // Flowable<PacketIn> flow = Flowable.fromPublisher(
        // //         org.reactivestreams.FlowAdapters.toPublisher(sw.packetInStream()));
    }

    @SuppressWarnings({"unused", "try"})
    private void scenarioF_compiles() {
        String addr = "127.0.0.1:50051";
        P4Info p4info = null;
        DeviceConfig dc = null;

        try (P4Switch sw = P4Switch.connectAsPrimary(addr).bindPipeline(p4info, dc)) {

            sw.insert(TableEntry.in("MyIngress.dmac")
                    .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:01"))
                    .action("MyIngress.set_egress").param("port", 1)
                    .build());

        } catch (P4PipelineException e) {
            // P4Info / device config mismatch, malformed entry, unknown table or field name.
        } catch (P4OperationException e) {
            for (UpdateFailure f : e.failures()) { /* ... */ }
        } catch (P4ConnectionException e) {
            // Transport faults, mastership lost, channel shutdown.
        } catch (P4RuntimeException e) {
            // Catch-all (parent of all above).
        }

        P4Switch sw = P4Switch.connect(addr)
                .deviceId(0)
                .electionId(1)
                .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
                        Duration.ofMillis(100), Duration.ofSeconds(10), /*maxRetries*/ 5))
                .asPrimary();

        sw.onMastershipChange(status -> {
            if (status.isLost()) {
                // reportToOps();
                sw.asPrimary();   // idempotent re-claim.
            }
        });

        CompletableFuture<Void> f = sw.insertAsync(TableEntry.in("MyIngress.dmac")
                .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:01"))
                .action("MyIngress.set_egress").param("port", 1)
                .build());
    }

    // ===================================================================================
    // Behaviour validation — assert that the first user-visible RPC in each scenario is
    // intentionally unimplemented yet. Sub-phases delete these one scenario at a time as
    // real behaviour lands; once a scenario is fully implemented its assertion moves to
    // an integration test class. Connection / arbitration (scenarios A and F's connect
    // half) are now covered by the ArbitrationTest / StreamLifecycleTest /
    // ConnectionFailureTest classes — their UOE rows are gone from this file.
    // ===================================================================================

    @Test
    void scenarioB_p4InfoFromFileThrowsUOE() {
        assertThrows(UnsupportedOperationException.class,
                () -> P4Info.fromFile(Path.of("nonexistent.p4info.txt")));
    }

    @Test
    void scenarioC_tableEntryBuildThrowsUOE() {
        // .in() / .match() / .action() / .param() chain returns builders fine;
        // the terminal .build() is the first call that should be UOE today.
        assertThrows(UnsupportedOperationException.class,
                () -> TableEntry.in("MyIngress.dmac")
                        .match("hdr.ethernet.dstAddr", Mac.of("00:00:00:00:00:01"))
                        .action("MyIngress.set_egress").param("port", 1)
                        .build());
    }

    @Test
    void scenarioE_packetOutBuildThrowsUOE() {
        assertThrows(UnsupportedOperationException.class,
                () -> PacketOut.builder().payload(new byte[]{1}).metadata("p", 1).build());
    }
}
