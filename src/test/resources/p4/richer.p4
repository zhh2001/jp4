/*
 * P4 program with all four match kinds (exact, lpm, ternary, range) on a single
 * table, plus a multi-param action. Used by 6B's EntryValidator and write-RPC tests
 * to cover the breadth of match-kind validation paths.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/test/resources/p4/richer.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/test/resources/p4 \
 *       src/test/resources/p4/richer.p4
 */

#include <core.p4>
#include <v1model.p4>

header h_t {
    bit<8>  exact_field;
    bit<32> lpm_field;
    bit<16> ternary_field;
    bit<16> range_field;
}

struct headers_t { h_t h; }
struct metadata_t { }

parser MyParser(packet_in packet, out headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    state start { packet.extract(hdr.h); transition accept; }
}
control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }
control MyIngress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) {
    action forward(bit<9> port, bit<48> nextHop) {
        std.egress_spec = port;
    }
    action drop_pkt() { mark_to_drop(std); }

    table multi {
        key = {
            hdr.h.exact_field   : exact;
            hdr.h.lpm_field     : lpm;
            hdr.h.ternary_field : ternary;
            hdr.h.range_field   : range;
        }
        actions = { forward; drop_pkt; NoAction; }
        size = 256;
        default_action = NoAction();
    }
    apply { multi.apply(); }
}
control MyEgress(inout headers_t hdr, inout metadata_t meta, inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }
control MyDeparser(packet_out packet, in headers_t hdr) { apply { packet.emit(hdr.h); } }
V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(), MyEgress(), MyComputeChecksum(), MyDeparser()) main;
