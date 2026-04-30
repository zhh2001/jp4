/*
 * Single-purpose v1model P4 program for jp4's Phase 7 packet I/O tests.
 * Defines packet_in / packet_out controller headers (the P4Runtime
 * controller_packet_metadata source), an ingress that obeys
 * packet_out.egress_port when present and otherwise clones the packet
 * to the controller (port CPU_PORT == 255), and a deparser that strips
 * the packet_out header and prepends the packet_in header.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/test/resources/p4/packet_io.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/test/resources/p4 \
 *       src/test/resources/p4/packet_io.p4
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

struct headers_t {
    packet_out_h packet_out;
    packet_in_h  packet_in;
    ethernet_t   ethernet;
}
struct metadata_t { }

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
    action loopback_to_cpu(bit<9> port) {
        // Strip the controller-supplied header and CPU it; carry the controller's
        // egress_port choice forward as the PacketIn's ingress_port so jp4 tests can
        // verify metadata round-trip without injecting external traffic.
        hdr.packet_out.setInvalid();
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = port;
        hdr.packet_in._pad = 0;
    }
    action send_to_cpu() {
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = std.ingress_port;
        hdr.packet_in._pad = 0;
    }
    apply {
        if (hdr.packet_out.isValid()) {
            // Loopback design: every PacketOut returns as a PacketIn carrying the
            // same payload, with ingress_port = the controller's egress_port choice.
            loopback_to_cpu(hdr.packet_out.egress_port);
        } else {
            // External traffic injection path: also CPU.
            send_to_cpu();
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.packet_in);     // emitted only if valid (set by send_to_cpu)
        packet.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
