# Error handling

jp4's exception hierarchy is small and maps cleanly to where things can
go wrong. There are four runtime exception types, all extending
`P4RuntimeException`:

```
P4RuntimeException                       (parent, abstract-ish)
├── P4ConnectionException                transport / mastership / closed switch
│       └── P4ArbitrationLost            primary denied (connect or re-claim)
├── P4PipelineException                  P4Info / device-config / schema problems
└── P4OperationException                 device rejected the RPC
```

The first three correspond to **where** the failure happened: connection
layer, schema layer, application layer. The fourth (`P4ArbitrationLost`)
is a specialisation of connection failure that controllers usually want
to handle separately for HA reasons.

## P4ConnectionException

Anything wrong below the application layer. Surfaces from:

- gRPC channel errors (peer unreachable, TLS issues, dropped connection).
- The switch is closed (`sw.close()` already called) and a subsequent
  operation tried to use it.
- The stream is broken and auto-reconnect is not configured (or has
  exhausted retries).
- Mastership was lost (someone else took primary; subsequent writes
  get this).
- Operation timed out (default 30s) waiting for a device response.

<!-- illustrative: concept fragment -->

```java
try {
    sw.insert(entry);
} catch (P4ConnectionException e) {
    // Cannot proceed against this switch right now. Log; consider
    // reconnect; if your controller has an HA partner, fail over.
}
```

`P4ConnectionException.getMessage()` is short and human-readable. The
underlying gRPC `Status` (if any) is in the cause chain — useful for
distinguishing UNAVAILABLE (try again later) from PERMISSION_DENIED
(don't try again with the same credentials).

### P4ArbitrationLost

Subclass surfaced when a connect or `asPrimary()` re-claim is denied by
the device because another client already holds primary:

<!-- illustrative: concept fragment -->

```java
try {
    sw.asPrimary();
} catch (P4ArbitrationLost e) {
    log("primary denied; current is " + e.currentPrimary()
        + ", ours was " + e.ourElectionId());
    // Maybe try with a higher election id, or back off and retry later.
}
```

Catch this separately from the generic `P4ConnectionException` if your
controller needs to behave differently when "we lost primary" vs
"network broke" — both surface as connection-level issues, but the
remedies differ.

## P4PipelineException

P4Info / device config / schema disagreement. Surfaces from:

- Loading malformed P4Info or device config bytes.
- Operations against a switch with no pipeline bound
  (`bindPipeline` or `loadPipeline` not yet called).
- Misspelled table / action / match field / param / metadata names —
  carries a known-list message naming the candidate set.
- Match-kind mismatch (using `Match.Lpm` against an exact-match field).
- Value too wide for the field's declared bit width.
- The action is not in the table's allowed action set.
- The reverse-parse of an inbound entry refers to an id the bound
  P4Info does not declare (pipeline drift between client and device).

<!-- illustrative: concept fragment -->

```java
try {
    sw.insert(entry);
} catch (P4PipelineException e) {
    // Schema problem. Almost always a code bug or a
    // P4Info-version-skew problem. Fix the call site or
    // re-bindPipeline(...) to refresh the schema.
    log.error("pipeline error: {}", e.getMessage());
}
```

Known-list error messages are jp4's main UX investment for
schema-driven APIs:

```
P4PipelineException: Field 'hdr.ipv4.bogus' not found in table
'MyIngress.ipv4_lpm'. Known fields: [hdr.ipv4.dstAddr]

P4PipelineException: Action 'do_nothing' not part of action set for
table 'MyIngress.ipv4_lpm'. Allowed actions: [MyIngress.forward,
MyIngress.drop]

P4PipelineException: value width 10 bits exceeds action 'forward' param
'port' bitWidth 9
```

Read each message and the typo or version-mismatch is usually obvious.

## P4OperationException

The device's PI library returned an explicit rejection on a Write or
Read RPC. Carries:

- `operationType()` — `INSERT` / `MODIFY` / `DELETE` / `READ`.
- `errorCode()` — the gRPC canonical code (`ALREADY_EXISTS`,
  `NOT_FOUND`, `INVALID_ARGUMENT`, …).
- `failures()` — for batch writes, one `UpdateFailure` per rejected
  update with its original batch index. For reads, always empty (reads
  fail wholesale).

<!-- illustrative: concept fragment -->

```java
try {
    sw.insert(entry);
} catch (P4OperationException e) {
    switch (e.errorCode()) {
        case ALREADY_EXISTS -> sw.modify(entry);    // benign — race against another writer
        case NOT_FOUND      -> log("table or scope was removed; investigate");
        case INVALID_ARGUMENT -> log("device-side validation rejected this entry: " + e.getMessage());
        default             -> log("unexpected device error: " + e.errorCode() + " " + e.getMessage());
    }
}
```

For batch writes, `BatchBuilder.execute()` does **not** throw on
per-update failures — it returns a `WriteResult` whose `failures()`
list carries the rejected updates. See [tables.md](tables.md#batches).

## Async paths

`*Async` methods (`insertAsync`, `modifyAsync`, `deleteAsync`, `sendAsync`,
`allAsync`, `oneAsync`) never throw on the calling thread for
problems they detect after returning. Validation errors, schema
problems, RPC failures all complete the returned `CompletableFuture`
exceptionally, with the same exception type the sync method would have
thrown:

<!-- illustrative: concept fragment -->

```java
sw.insertAsync(entry).whenComplete((v, t) -> {
    if (t instanceof P4OperationException op)        rejected(op);
    else if (t instanceof P4PipelineException pp)    schemaError(pp);
    else if (t instanceof P4ConnectionException ce)  cantTalkToDevice(ce);
    else if (t == null)                              ok();
    else                                             unknownTrouble(t);
});
```

This matches the design rule "methods that return a future report
failures through the future, never by throwing on the calling thread".
Sync methods unwrap the future and rethrow.

## See also

- [Connection and arbitration](connection-and-arbitration.md) for
  `P4ConnectionException` / `P4ArbitrationLost` surface details.
- [Pipelines](pipeline.md) for the schema-drift case that surfaces
  `P4PipelineException` from PacketIn dispatch.
- [Tables](tables.md) for `WriteResult` and how batch failures appear
  per-update.
