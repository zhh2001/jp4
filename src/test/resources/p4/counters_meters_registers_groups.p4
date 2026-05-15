/*
 * v1model P4 program for jp4's counter / meter / register / action-profile
 * read-API integration tests. Declares four indirect features in one
 * pipeline so a single BMv2 spawn amortises across all five read API
 * families (counter, meter, register, action-profile member, action-profile
 * group).
 *
 * Features declared:
 *   - my_counter   : indirect counter, size 64, packets_and_bytes
 *   - my_meter     : indirect meter,   size 32, bytes
 *   - my_register  : register array<bit<32>>, size 16
 *   - my_selector  : action_selector backing lb_table (P4Info attaches
 *                    the implicit action profile to lb_table's id, which
 *                    is the prerequisite for selector-group reads)
 *
 * The pipeline is intentionally minimal — every packet bumps cell 0 of
 * the counter, executes the meter at cell 0, writes the register at
 * cell 0, then applies lb_table. The controller never needs to inject
 * traffic for the read tests; counter / meter / register cells are
 * read against the freshly-loaded zero-state device, and the
 * action-profile read tests seed BMv2 state via a raw P4Runtime stub
 * Write before reading through jp4.
 *
 * Recompile with:
 *   p4c --target bmv2 --arch v1model \
 *       --p4runtime-files src/test/resources/p4/counters_meters_registers_groups.p4info.txtpb \
 *       --p4runtime-format text \
 *       -o src/test/resources/p4 \
 *       src/test/resources/p4/counters_meters_registers_groups.p4
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

struct headers_t {
    packet_out_h packet_out;
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

    counter(64, CounterType.packets_and_bytes) my_counter;
    meter(32, MeterType.bytes)                 my_meter;
    register<bit<32>>(16)                      my_register;
    action_selector(HashAlgorithm.crc16, 32w128, 32w16) my_selector;

    action set_egress(bit<9> port) {
        std.egress_spec = port;
    }

    action send_to_cpu() {
        std.egress_spec = CPU_PORT;
    }

    table lb_table {
        key = { hdr.ethernet.srcAddr : exact; }
        actions = { set_egress; send_to_cpu; NoAction; }
        implementation = my_selector;
        size = 64;
    }

    apply {
        my_counter.count(32w0);
        bit<2> meter_color;
        my_meter.execute_meter<bit<2>>(32w0, meter_color);
        my_register.write(32w0, 32w0);
        lb_table.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t std) { apply { } }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply { } }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
