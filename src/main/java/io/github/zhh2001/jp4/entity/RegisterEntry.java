package io.github.zhh2001.jp4.entity;

import io.github.zhh2001.jp4.types.Bytes;

import java.util.Objects;

/**
 * One P4 register cell returned by {@code P4Switch.readRegister}. The
 * record carries the register's fully-qualified P4 name, the cell index
 * within the register array, and the cell's payload as the serialised
 * bytes of one {@code p4.v1.P4Data} message.
 *
 * <p>The payload is intentionally kept as raw bytes: a register's
 * per-cell type is declared in P4Info as a {@code P4DataTypeSpec}, and
 * surfacing typed values would require a P4Info-aware decoder that is
 * held for a future v1.x release. The same convention is in force for
 * {@link DigestEvent#data}.
 *
 * <p>Consumers that want the typed form decode through the generated
 * proto class:
 *
 * <pre>{@code
 * P4Data datum = P4DataOuterClass.P4Data.parseFrom(entry.data().toByteArray());
 * // For the common bit<W> / int<W> register, the payload is the bitstring oneof:
 * Bytes value = Bytes.of(datum.getBitstring().toByteArray());
 * }</pre>
 *
 * <p>Records are immutable. The canonical constructor rejects null in
 * every reference component; the {@code long} index has no null surface.
 *
 * @param registerName the register's fully-qualified P4 name (resolved
 *                     from the wire {@code register_id} during read
 *                     response parsing); never {@code null}
 * @param index        the cell index within the register array, as
 *                     returned by the device
 * @param data         the serialised bytes of the {@code p4.v1.P4Data}
 *                     proto message the device returned for this cell;
 *                     never {@code null}
 * @since 1.4.0
 */
public record RegisterEntry(
        String registerName,
        long index,
        Bytes data
) {

    public RegisterEntry {
        Objects.requireNonNull(registerName, "registerName");
        Objects.requireNonNull(data, "data");
    }
}
