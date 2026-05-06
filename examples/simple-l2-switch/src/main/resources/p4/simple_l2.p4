/*
 * Simple L2 learning switch — controller-side learning, data-plane forwarding.
 *
 * Lineage: same controller-header pattern as src/test/resources/p4/packet_io.p4.
 *
 * Forwarding model:
 *   - PacketOut from controller carries an explicit egress_port → forward there.
 *   - Otherwise apply l2_forward (exact match on dstAddr → forward(port)).
 *   - On miss, the table's default action (flood_via_cpu) hands the packet up to
 *     the controller, which floods it to all other ports via PacketOut and
 *     simultaneously learns dstAddr ↔ ingress_port.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/main/resources/p4/simple_l2.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/main/resources/p4 \
 *       src/main/resources/p4/simple_l2.p4
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
    action send_to_port(bit<9> port) {
        std.egress_spec = port;
    }
    action flood_via_cpu() {
        // Hand the packet to the controller; controller floods + learns.
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = std.ingress_port;
        hdr.packet_in._pad = 0;
    }
    action drop_pkt() {
        mark_to_drop(std);
    }

    table l2_forward {
        key = { hdr.ethernet.dstAddr : exact; }
        actions = {
            send_to_port;
            flood_via_cpu;
            drop_pkt;
            NoAction;
        }
        size = 1024;
        default_action = flood_via_cpu();
    }

    apply {
        if (hdr.packet_out.isValid()) {
            // Controller-injected: obey egress, strip controller header.
            send_to_port(hdr.packet_out.egress_port);
            hdr.packet_out.setInvalid();
        } else {
            l2_forward.apply();
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.packet_in);     // emitted only when set valid by flood_via_cpu
        packet.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
