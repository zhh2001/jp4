/*
 * Minimal v1model P4 program used by jp4's pipeline integration tests.
 * Single IPv4 LPM table that picks an egress port. Compiled artifacts
 * live next to this file (basic.json, basic.p4info.txtpb, basic.p4info.bin).
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/test/resources/p4/basic.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/test/resources/p4 \
 *       src/test/resources/p4/basic.p4
 */

#include <core.p4>
#include <v1model.p4>

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
struct headers_t { ethernet_t ethernet; ipv4_t ipv4; }
struct metadata_t { }

parser MyParser(packet_in packet, out headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    state start {
        packet.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            16w0x0800: parse_ipv4;
            default:   accept;
        }
    }
    state parse_ipv4 { packet.extract(hdr.ipv4); transition accept; }
}
control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }
control MyIngress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    action forward(bit<9> port) { std.egress_spec = port; }
    action drop_pkt() { mark_to_drop(std); }
    table ipv4_lpm {
        key = { hdr.ipv4.dstAddr : lpm; }
        actions = { forward; drop_pkt; NoAction; }
        size = 1024;
        default_action = NoAction();
    }
    apply { ipv4_lpm.apply(); }
}
control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }
control MyDeparser(packet_out packet, in headers_t hdr) {
    apply { packet.emit(hdr.ethernet); packet.emit(hdr.ipv4); }
}
V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
