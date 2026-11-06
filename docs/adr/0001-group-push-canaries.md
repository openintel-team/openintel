# 0001 — Group push Canary builds without AI services

**Date:** 2026-11-05
**Status:** Accepted

## Context

Direct pushes currently do not build. Maintained Minecraft branches often receive
version adaptations of the same change in quick succession. Maintainers want one
named build run per related update, with no paid AI usage.

## Decision

A lightweight push workflow records each branch tip. A coordinator running trusted
default-branch code collects those events after 90 seconds, including superseded
pending coordinator events. It groups different branches only when normalized
commit titles and changed files agree and patch similarity is strong, or when
stable patch IDs agree. Ambiguous changes build separately. Group membership is
complete-link (every pair must match), never transitive chaining.

GitHub check runs are scheduling receipts per branch and commit. Receipts contain
the exact group manifest before dispatch; interrupted dispatches are reconciled
against build run names. A scheduled sweep retries interrupted scheduling. The
build validates the manifest and suppresses duplicate dispatches by batch ID.
Only the coordinator has Actions/check write permissions. Build jobs retain read
permissions. No AI service, additional secret, or paid runner is used.

## Consequences

Version adaptations can share a title and artifact page without sharing a commit
SHA. Late backports get a separate run. Every recorded pushed tip is considered,
including tips superseded before a coordinator starts; commits within one push
are covered by its final tree. Matching favors separate builds over false groups.
The event log and check receipts survive runner loss. A refreshed artifact stores
a history watermark and unresolved backlog; completed history is not checked on
every sweep. If the artifact expires, recovery falls back to the push event log.
The 90-second window is collection time after a runner starts, not a wall-clock
latency guarantee. Actions artifact storage still follows repository billing limits.

## Alternatives

1. Copilot semantic grouping: handles more divergent backports, but its free-only
   availability could not be guaranteed for this organization.
2. Explicit shared commit trailers: exact grouping, but requires authors to attach
   metadata and does not automatically recognize existing backport conventions.
3. Build every push independently: simplest, but scatters one update's artifacts.

## Payoff trigger

Revisit matching if maintainers observe a false group or repeated missed backports.
Revisit artifact-backed checkpoints if expiry recovery or large backlogs materially delay builds.
