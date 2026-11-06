# Group push Canary builds implementation plan

**Goal:** Build every maintained-branch push and group related backports for free.
**Architecture:** Lightweight push events, a serialized default-branch coordinator,
GitHub check scheduling receipts, and exact-SHA matrix builds.
**Tech stack:** GitHub Actions and Python standard library.
**Spec:** docs/adr/0001-group-push-canaries.md, implementing the approved chat design
and the subsequent choice of free local matching.

## Constraints and review focus

- No Copilot calls, AI credentials, new package dependency, or paid runner.
- Preserve PR/manual builds, per-version artifacts, and existing checks.
- Never lose a superseded push event, or trust an arbitrary ref supplied in a manifest.
- Invalid matching data must separate builds; interrupted dispatch must be recoverable.
- Concurrent pushes, duplicate dispatch, missing commits, merge patches, and late
  backports require explicit handling.

## Tasks

- [x] Add failing standard-library tests for matching, partition validation, receipt
  recovery, duplicate dispatch, pagination, and exact commit manifests.
- [x] Implement the shared coordinator/plan helper and the two trigger workflows.
- [x] Extend the build workflow inputs and guard automatic build duplicates.
- [x] Document the grouping rule, recovery, and manual controls in each branch.
- [x] Run helper tests, workflow lint, relay tests, and all four Gradle builds;
  review the final diff and verify attribution before publication.

## Validation

- 24 helper tests passed in every maintained checkout.
- Workflow actionlint validation passed.
- The actual map-parity commits were recognized as one four-branch group.
- Relay tests and all four Java 25 Gradle builds passed.
- Independent review found API history/rate-limit issues; incremental checkpoints
  and unfiltered pagination fixed them, with regression tests and a follow-up review.
