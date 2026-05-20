---
title: Canonical bytestring encoding
description: How P4Runtime 1.3+ strips leading zero bytes from value-shaped fields on the wire, what jp4 returns on the read side, and how controllers should compare a read-back value to a known reference.
keywords: [jp4, P4Runtime, canonical bytestring, encoding, leading zero, Bytes, byte comparison]
---

# Canonical bytestring encoding

P4Runtime 1.3 introduced a wire-encoding rule for byte-shaped value
fields — the bytes a device puts on the wire are the *canonical* form
of the value, with leading zero bytes stripped. v1.4 carried it forward,
v1.5 carried it forward. Controllers that compare read-back bytes to a
locally-constructed reference need to know about this rule, because
the obvious byte-for-byte `equals` is wrong on the read side.

This page explains what the encoding does, why P4Runtime adopted it,
and how jp4 callers should reason about it.

## The rule

A value of width `W` bits is carried in `ceil(W / 8)` bytes — *no, the
minimum number of bytes that fits the actual numeric value*. Leading
zero bytes are stripped. A 9-bit port value of 5 sent as
`{0x00, 0x05}` round-trips through a P4Runtime 1.3+ device as the
single-byte canonical form `{0x05}`.

Numeric equivalence is preserved: `BigInteger(1, {0x00, 0x05})` and
`BigInteger(1, {0x05})` both evaluate to 5. The bytes-with-leading-zeroes
form is a controller-side convention; the canonical form is the spec's
on-wire shape.

The spec citation is P4Runtime §8.4 "Bytestrings" — the section
describes the conformant encoding (numerical-magnitude bytes, no
leading zeroes) and the conformant decoding (a receiver MUST accept
either shape).

## What jp4 returns

jp4 returns whatever the device returns. On a P4Runtime 1.3+ device
that emits the canonical form, a `CounterEntry`, `TableEntry`,
`ActionProfileMember`, `Replica`, or any other read-side record
carrying `Bytes` exposes the *canonical* bytes — single-byte `{0x05}`
for the example above.

On the write side, jp4 accepts either shape. The fluent `Match` builder
accepts `int` / `long` / `Bytes` / `byte[]` / `Mac` / `Ip4` / `Ip6` and
serialises whichever the caller passed; the device is required to
accept both. Controllers that store a known reference in
bytes-with-leading-zeros form do not need to canonicalise on the write
side, only on the comparison side.

## How to compare read-back values to a reference

Three options, ordered by what the controller already has:

**1. Compare numerically.** If the field is a fixed-width integer, the
`BigInteger(1, ...)` wrapper works on both forms:

<!-- illustrative: concept fragment -->

```java
byte[] readBack = entry.match("hdr.ipv4.dstAddr").asLpm().value().toByteArray();
byte[] referenceBytes = referenceIp.toBytes();   // may carry leading zeros
if (new BigInteger(1, readBack).equals(new BigInteger(1, referenceBytes))) {
    // numeric equivalence — works regardless of leading-zero stripping
}
```

**2. Canonicalise the reference.** Strip the leading zero bytes before
comparing on the bytes themselves:

<!-- illustrative: concept fragment -->

```java
static byte[] canonicalise(byte[] in) {
    int i = 0;
    while (i < in.length - 1 && in[i] == 0) i++;
    return java.util.Arrays.copyOfRange(in, i, in.length);
}

if (java.util.Arrays.equals(readBack, canonicalise(referenceBytes))) {
    // canonical-form byte equality
}
```

Single-byte zero (`{0x00}`) is the canonical form of zero — keep one
byte, do not strip to empty. The `i < in.length - 1` bound on the loop
guards that case.

**3. Wrap in `Bytes` and call `equals`.** jp4's `Bytes` value type
canonicalises on construction, so `Bytes.of(referenceBytes).equals(readBackBytes)`
holds for any numerically-equivalent input pair. Useful when the
controller already lives in `Bytes`-land and does not want the
imperative loop.

## Width-shaped fields are not value-shaped

The canonical-bytestring rule applies to *value-shaped* fields — match
keys, action parameters, counter / meter / register cell values, the
`Replica.port` and `BackupReplica.port` fields, and so on. It does not
apply to length-shaped or width-shaped fields, which are carried as
proto `uint32` / `int32` and have no per-byte representation on the
wire (the proto encoding handles them).

In jp4, every field where the wire type is `bytes` is subject to the
rule; every field where the wire type is `uint32` / `int32` / `int64`
(prefix lengths, counts, ids, timestamps) is not.

## Why the rule exists

Two reasons in the spec rationale:

- **Wire size.** Without canonicalisation, a 256-bit IPv6 address with
  a small value (loopback, link-local prefix) would consume 32 bytes
  even when 2 are sufficient. At scale (millions of routes), this is
  meaningful.
- **Bit-width independence on the wire.** A device that internally
  widens or narrows a field by a few bits over a software upgrade can
  do so without breaking controllers that compare byte-for-byte
  against historical reads — both sides only see numerical magnitude.

The cost is the comparison-side rule above; the benefit is that
controllers and devices can evolve their internal widths
independently.

## See also

- [port_kind idiom](/concepts/port-kind-idiom) — how jp4 handles
  `Replica.port` (a canonical-bytestring field) when the value is
  unset versus zero.
- [Tables](/guides/tables) — the write-side `Match` builder accepts
  any byte-shaped input and the device is required to accept it.
- [v1.3 → v1.4 migration guide](/migrations/migration-1.3-to-1.4) —
  carries the same point for action-profile reads.
