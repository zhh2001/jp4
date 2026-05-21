---
title: Migration guides
description: Per-release migration guides for jp4 — v0.1 → v1.0 through v1.4 → v1.5. Each guide documents the API surface changes between two adjacent versions, with before/after code snippets for callers updating across the boundary and the v1.x roadmap snapshot captured at that release.
keywords: [jp4, migration, version upgrade, v0.1, v1.0, v1.1, v1.2, v1.3, v1.4, v1.5, release notes]
---

# Migration guides

Per-release migration guides for upgrading a jp4 controller across one or more minor-version boundaries. Each guide documents the API surface changes between two adjacent versions: which methods were added, which behaviour tightened, what (if anything) requires a code change at the call site, and the v1.x roadmap snapshot captured at that release.

The guides are written from the *callers* perspective — what does a controller already running on version X need to change to upgrade to version Y? The answer is "nothing" for every v1.0 → v1.5 transition because the project's SemVer commitment is binding: minor versions add capabilities without removing or breaking the existing surface. Each guide cites the commit hash where each change landed; `git show <hash>` resolves the full diff.

## All guides

| Guide | Released | What it covers |
|---|---|---|
| [v0.1 → v1.0](/migrations/migration-0.1-to-1.0) | 2026-05-08 | Additions (paramInt / paramLong / metadataLong / symmetric fromBytes factories), behaviour tightening (MastershipStatus.toString format, UncheckedIOException for fromFile, null-rejection on accessors, actionable width-overflow messages), documentation polish (thread-safety contracts, null contract, production readiness). The v1.0 cut. |
| [v1.0 → v1.1](/migrations/migration-1.0-to-1.1) | 2026-05-09 | ReadQuery.where for client-side filtering, Connector.preserveRoleOnReconnect for primary-mandatory HA, Mac.ZERO constant. |
| [v1.1 → v1.2](/migrations/migration-1.1-to-1.2) | 2026-05-11 | Packet-ingestion control surface — DropEvent record + Reason enum, P4Switch.onPacketDropped listener, Connector.packetInFilter pre-fan-out filter. |
| [v1.2 → v1.3](/migrations/migration-1.2-to-1.3) | 2026-05-14 | Stream-message dispatch family completes — DigestEvent + P4Switch.onDigest + DigestConfig + P4Switch.enableDigest + IdleTimeoutEvent + P4Switch.onIdleTimeout + TableEntry.idleTimeoutNs. |
| [v1.3 → v1.4](/migrations/migration-1.3-to-1.4) | 2026-05-15 | Per-entity-type read surface completes — counter / meter / register / action-profile member / action-profile group reads with the shared query-builder shape. BMv2 register-read UNIMPLEMENTED limitation surfaced. |
| [v1.4 → v1.5](/migrations/migration-1.4-to-1.5) | 2026-05-15 | Packet replication engine reads — multicast group + clone session via name-less typed query builders. Shared Replica / BackupReplica records (BackupReplica is the first P4Runtime-1.5.0-spec-level type to land in jp4). |

## Reading order

If you're upgrading several versions at once (e.g. v1.1 → v1.5), read each intermediate guide in order — each one is scoped to a single adjacent transition, and concatenating their advice covers the full distance.

If you're starting fresh on v1.5 and just want to know what's new since you last looked, the [v1.4 → v1.5 guide](/migrations/migration-1.4-to-1.5) is the most recent.

## Why this section is English-only

Migration guides document a fixed past API delta — they describe what changed in a specific release and do not get retranslated each release. The guides are linked from both the English and Chinese sidebars; the underlying content is English.

## Related

- [CHANGELOG](https://github.com/zhh2001/jp4/blob/main/CHANGELOG.md) — release-note form of the same information, plus the v1.x roadmap.
- [Getting started](/guides/getting-started) — entry guide for users on the current version (v1.5).
- [API reference](/api/) — categorised navigation of the v1.5 public surface plus the full Javadoc.
