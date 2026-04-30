/*
 * Structurally distinct v1model program — used by jp4's mismatched-pipeline
 * integration test (scenario d). Different parser (no IPv4), different table
 * (ethernet exact match instead of IPv4 LPM), different deparser. The compiled
 * BMv2 device config is intentionally incompatible with basic.p4info.txtpb,
 * which is the whole point of the test.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/test/resources/p4/alt.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/test/resources/p4 \
 *       src/test/resources/p4/alt.p4
 */

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}
struct headers_t { ethernet_t ethernet; }
struct metadata_t { }

parser MyParser(packet_in packet, out headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    state start { packet.extract(hdr.ethernet); transition accept; }
}
control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }
control MyIngress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    action forward(bit<9> port) { std.egress_spec = port; }
    action drop_pkt() { mark_to_drop(std); }
    table ethernet_dmac {
        key = { hdr.ethernet.dstAddr : exact; }
        actions = { forward; drop_pkt; NoAction; }
        size = 256;
        default_action = NoAction();
    }
    apply { ethernet_dmac.apply(); }
}
control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }
control MyDeparser(packet_out packet, in headers_t hdr) { apply { packet.emit(hdr.ethernet); } }
V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
