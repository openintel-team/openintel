# 0002 — Verify Canary source before executing build code

**Date:** 2026-11-05
**Status:** Accepted

## Context

Grouped Canary builds accept exact commits from multiple maintained branches.
The planner checks commit membership through the GitHub API, but a dynamic
checkout in a separate build job obscures that boundary from Actions analysis.
Dispatched builds can write to the workflow branch's cache scope. Read-only
repository permissions and disabling dependency caching do not remove that
runtime capability from code executing on the runner.

## Decision

Start dispatched builds with the literal main branch and fetch full history.
Specifying a branch without an event SHA prevents checkout from rewinding a
fetched remote ref when the branch advances after dispatch. Pull requests use
GitHub's synthetic merge checkout. Before a dispatched build switches
source, inline workflow code validates a fixed maintained-branch allowlist and
a full hexadecimal commit ID. Git must confirm that commit is an ancestor of
the fully qualified fetched remote branch. Only then may the job check out the
exact commit and execute package scripts or Gradle.

PR builds retain their original merge checkout and PR cache scope. The existing
planner validation, grouped dispatches, dependency caches, and artifact naming
remain in place. Tests execute the actual inline guard against real Git objects.

## Consequences

Dispatched builds can execute only commits in maintained branch history, even
if the upstream matrix is wrong. Tags cannot substitute for a remote branch,
and a moving branch cannot substitute different code for the verified SHA.
Fetching full history costs more than a shallow checkout. A commit removed
from maintained history is intentionally rejected rather than built.

## Alternatives

1. Disable dependency caches: reduces ordinary cache saves but does not remove
   the runner's cache service capability from malicious build code.
2. Build only current branch tips: simpler source selection, but loses exact
   grouped builds for recorded pushes superseded before scheduling.
3. Rely only on planner API validation: retains the current cross-job boundary
   and does not enforce the invariant immediately before code execution.

## Payoff trigger

Revisit full-history fetching if repository growth materially delays Canary
builds; preserve local verification of the exact commit before execution.
