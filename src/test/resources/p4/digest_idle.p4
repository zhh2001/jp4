/*
 * v1model P4 program for jp4's Digest + IdleTimeout integration tests.
 * Declares one digest extern (learn_digest_t) and one table
 * (mac_learn) with idle-timeout support, plus the packet_in /
 * packet_out controller headers so the controller can inject and
 * observe packets through the P4Runtime StreamChannel without
 * needing external network traffic on the veth interface.
 *
 * Every packet that enters the pipeline triggers digest() and then
 * queries mac_learn; entries inserted with idle_timeout_ns set will
 * be aged out by BMv2's AgeingMonitor and reported back to the
 * controller as IdleTimeoutNotification. Packets always loop back to
 * the controller via send_to_cpu (CPU_PORT == 255) so the test driver
 * can observe ingress flow as PacketIn events.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/test/resources/p4/digest_idle.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/test/resources/p4 \
 *       src/test/resources/p4/digest_idle.p4
 */

#include <core.p4>
#include <v1model.p4>

#define CPU_PORT 255

@controller_header("packet_in")
header packet_in_h {
    bit<9> ingress_port;
    bit<7> _pad;
}

@controller_header("packet_out")
header packet_out_h {
    bit<9> egress_port;
    bit<7> _pad;
}

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct learn_digest_t {
    bit<48> srcAddr;
    bit<9>  ingress_port;
}

struct headers_t {
    packet_out_h packet_out;
    packet_in_h  packet_in;
    ethernet_t   ethernet;
}
struct metadata_t { }

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t std) {
    state start {
        transition select(std.ingress_port) {
            CPU_PORT: parse_packet_out;
            default:  parse_ethernet;
        }
    }
    state parse_packet_out {
        pkt.extract(hdr.packet_out);
        transition parse_ethernet;
    }
    state parse_ethernet {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t std) {

    action send_to_cpu() {
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = std.ingress_port;
        hdr.packet_in._pad = 0;
    }

    action mac_seen() { }   // table-hit action; idle timer reset by table hit

    table mac_learn {
        key   = { hdr.ethernet.srcAddr : exact; }
        actions = { mac_seen; NoAction; }
        const default_action = NoAction();
        support_timeout = true;
        size = 64;
    }

    apply {
        // Fire a learn-digest on every packet so DigestList emission can be
        // observed end-to-end.
        digest<learn_digest_t>(1, { hdr.ethernet.srcAddr, std.ingress_port });

        // Query mac_learn so installed entries get their idle timer
        // reset on hits.
        mac_learn.apply();

        // Loop the packet back to the controller so the test driver can
        // observe PacketIn arrivals as a sanity-check signal.
        send_to_cpu();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.packet_in);
        pkt.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
