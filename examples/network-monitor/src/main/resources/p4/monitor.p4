/*
 * Pure passive monitor — every ingress packet goes to the controller.
 *
 * No tables. The data plane unconditionally sets std.egress_spec = CPU_PORT
 * and stamps the packet_in header with the original ingress_port. The
 * controller subscribes to PacketIn and computes per-port stats.
 *
 * Not a forwarding pipeline — the data plane consumes every packet. Real
 * monitoring deployments use clone3() to mirror packets while keeping the
 * forwarding path; clone sessions live in the PRE which is v0.2 work for
 * jp4. This example is intentionally minimal so it focuses on jp4's
 * Flow.Publisher consumption pattern.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/main/resources/p4/monitor.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/main/resources/p4 \
 *       src/main/resources/p4/monitor.p4
 */

#include <core.p4>
#include <v1model.p4>

#define CPU_PORT 255

@controller_header("packet_in")
header packet_in_h {
    bit<9> ingress_port;
    bit<7> _pad;
}

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t {
    packet_in_h packet_in;
    ethernet_t  ethernet;
}
struct metadata_t { }

parser MyParser(packet_in packet, out headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    state start {
        packet.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyIngress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    apply {
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = std.ingress_port;
        hdr.packet_in._pad = 0;
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.packet_in);
        packet.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
