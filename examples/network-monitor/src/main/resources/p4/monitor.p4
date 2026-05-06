/*
 * Pure passive monitor — every ingress packet goes to the controller.
 *
 * No tables. The data plane unconditionally sets std.egress_spec = CPU_PORT
 * and stamps the packet_in header with the original ingress_port. The
 * controller subscribes to PacketIn and computes per-port stats.
 *
 * For the self-contained demo, the program also accepts a controller-supplied
 * packet_out.egress_port and treats it as the simulated ingress port so the
 * observer's per-port stats look meaningful (real ingress packets would carry
 * std.ingress_port natively). See simple_l2.p4 for the same pattern.
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

@controller_header("packet_out")
header packet_out_h {
    bit<9> egress_port;     // repurposed as "simulated ingress" for the demo
    bit<7> _pad;
}

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t {
    packet_out_h packet_out;
    packet_in_h  packet_in;
    ethernet_t   ethernet;
}

struct metadata_t {
    bit<9> simulated_ingress_port;
}

parser MyParser(packet_in packet, out headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    state start {
        transition select(std.ingress_port) {
            CPU_PORT: parse_packet_out;
            default:  parse_ethernet;
        }
    }
    state parse_packet_out {
        packet.extract(hdr.packet_out);
        transition parse_ethernet;
    }
    state parse_ethernet {
        packet.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyIngress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    apply {
        if (hdr.packet_out.isValid()) {
            // Controller-injected: use the supplied egress_port as simulated ingress.
            meta.simulated_ingress_port = hdr.packet_out.egress_port;
            hdr.packet_out.setInvalid();
        } else {
            meta.simulated_ingress_port = std.ingress_port;
        }
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = meta.simulated_ingress_port;
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
