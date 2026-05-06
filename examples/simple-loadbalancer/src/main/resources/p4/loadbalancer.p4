/*
 * Simple LPM-based load balancer.
 *
 * Forwarding model:
 *   - Each /24 routing prefix is mapped to a backend egress port via the
 *     `backend_lookup` LPM table.
 *   - Default action drops, so a fresh device with no entries fail-closes
 *     until the controller installs routes.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/main/resources/p4/loadbalancer.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/main/resources/p4 \
 *       src/main/resources/p4/loadbalancer.p4
 */

#include <core.p4>
#include <v1model.p4>

#define CPU_PORT 255

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

header ipv4_t {
    bit<8>  version_ihl;
    bit<8>  diffserv;
    bit<16> totalLen;
    bit<16> identification;
    bit<16> flagsFragOffset;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<16> hdrChecksum;
    bit<32> srcAddr;
    bit<32> dstAddr;
}

struct headers_t {
    packet_out_h packet_out;
    ethernet_t   ethernet;
    ipv4_t       ipv4;
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
        transition select(hdr.ethernet.etherType) {
            16w0x0800: parse_ipv4;
            default:   accept;
        }
    }
    state parse_ipv4 {
        packet.extract(hdr.ipv4);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyIngress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    action forward(bit<9> port) { std.egress_spec = port; }
    action drop_pkt()           { mark_to_drop(std); }

    table backend_lookup {
        key = { hdr.ipv4.dstAddr : lpm; }
        actions = { forward; drop_pkt; }
        size = 1024;
        default_action = drop_pkt();
    }

    apply {
        if (hdr.packet_out.isValid()) {
            forward(hdr.packet_out.egress_port);
            hdr.packet_out.setInvalid();
        } else if (hdr.ipv4.isValid()) {
            backend_lookup.apply();
        } else {
            drop_pkt();
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.ethernet);
        packet.emit(hdr.ipv4);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
