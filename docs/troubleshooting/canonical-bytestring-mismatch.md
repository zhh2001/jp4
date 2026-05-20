---
title: Read-back bytes don't match write-time bytes
description: Why a byte-for-byte equals comparison fails between a value you wrote to the device and the value the device returns on a Read — the P4Runtime canonical-bytestring encoding strips leading zero bytes.
keywords: [jp4, troubleshooting, canonical bytestring, leading zero, Bytes, equality, comparison, read-back]
---

<!-- doc-lint: skip-file (troubleshooting page; code blocks are illustrative fix patterns and diagnostic snippets, not source-verified examples) -->

# Read-back bytes don't match write-time bytes

## Symptom

You wrote a value to the device — say, a 9-bit port number `5` encoded as `{0x00, 0x05}` — and a subsequent Read returns the same field as `{0x05}` (one byte instead of two). A direct byte-for-byte comparison fails:

```java
byte[] written = { 0x00, 0x05 };
sw.insert(entry);                                   // writes {0x00, 0x05}
TableEntry e = sw.read("...").one().orElseThrow();
byte[] readBack = e.match("port").asExact().value().toByteArray();
// readBack is {0x05}, not {0x00, 0x05}
java.util.Arrays.equals(written, readBack);         // false
```

Numerically the values are equivalent (both decode to 5), but the byte arrays differ in length.

## Reason

P4Runtime 1.3+ specifies that the bytes a device puts on the wire for value-shaped fields are the **canonical** form — the minimum number of bytes that represent the numeric magnitude. Leading zero bytes are stripped. The spec citation is P4Runtime §8.4 *"Bytestrings"*. The encoding rule applies to every value-shaped field on the read side: match keys, action parameters, counter / meter / register cell values, `Replica.port`, `BackupReplica.port`, and so on.

On the write side, jp4 accepts either form — the device is required to accept both. The asymmetry only shows up on the read side.

## Fix

Three options, ordered by what your controller already has:

**1. Compare numerically.** Wrap both byte arrays in `BigInteger(1, ...)`:

```java
new BigInteger(1, written).equals(new BigInteger(1, readBack));   // true
```

**2. Canonicalise the reference before comparing:**

```java
static byte[] canonicalise(byte[] in) {
    int i = 0;
    while (i < in.length - 1 && in[i] == 0) i++;
    return java.util.Arrays.copyOfRange(in, i, in.length);
}

Arrays.equals(canonicalise(written), readBack);   // true
```

Single-byte zero (`{0x00}`) is the canonical form of zero — the `i < in.length - 1` bound preserves it.

**3. Wrap in `Bytes`** — jp4's `Bytes` value type canonicalises on construction:

```java
Bytes.of(written).equals(Bytes.of(readBack));   // true
```

## Background

Two reasons drove the spec choice:
- **Wire size**: a 256-bit IPv6 address with a small value would otherwise consume 32 bytes; the canonical form takes 2.
- **Bit-width independence on the wire**: devices can evolve internal widths over software upgrades without breaking controllers that compare against historical reads — both sides only see numerical magnitude.

The cost is the comparison-side rule above; the benefit is decoupled wire and storage representations.

## See also

- [Canonical bytestring encoding](/concepts/canonical-bytestring) — full reasoning, worked examples, why width-shaped fields are not value-shaped.
- [Tables](/guides/tables) — the write-side `Match` builder accepts any byte-shaped input; the device is required to accept it.
- [v1.3 → v1.4 migration guide](/migrations/migration-1.3-to-1.4) — also notes this for action-profile reads.
