/*
 * Simple L2 learning switch — controller-side learning, data-plane forwarding.
 *
 * Lineage: same controller-header pattern as src/test/resources/p4/packet_io.p4.
 *
 * Forwarding model:
 *   - PacketOut from controller carries an explicit egress_port. For this
 *     self-contained demo, the program treats that field as a "simulated
 *     ingress port" so the controller's injected PacketOut goes through the
 *     same l2_forward table a real ingress packet would. (In a production
 *     deployment, packet_out.egress_port has its conventional meaning and
 *     real ingress traffic comes from the network.)
 *   - Apply l2_forward (exact match on dstAddr → forward(port)).
 *   - On miss, the table's default action (flood_via_cpu) hands the packet up
 *     to the controller carrying the simulated_ingress_port, so the controller
 *     learns srcAddr ↔ ingress_port from the PacketIn metadata.
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

struct metadata_t {
    // The port the controller asked us to treat as ingress for this frame.
    // For real ingress packets, this equals std.ingress_port. For
    // controller-injected PacketOuts, this is the controller-supplied
    // packet_out.egress_port — letting the demo exercise the learning loop
    // without external traffic injection.
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
    action send_to_port(bit<9> port) {
        std.egress_spec = port;
    }
    action flood_via_cpu() {
        // Hand the packet to the controller; controller learns srcAddr ↔ ingress.
        std.egress_spec = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = meta.simulated_ingress_port;
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
            // Demo loopback: the controller-supplied "egress_port" is treated
            // as the simulated ingress port for this frame, so flood_via_cpu
            // can carry it back as PacketIn.ingress_port. Strip the controller
            // header so l2_forward sees a normal Ethernet frame.
            meta.simulated_ingress_port = hdr.packet_out.egress_port;
            hdr.packet_out.setInvalid();
        } else {
            meta.simulated_ingress_port = std.ingress_port;
        }
        l2_forward.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.packet_in);     // emitted only if valid (set by flood_via_cpu)
        packet.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
