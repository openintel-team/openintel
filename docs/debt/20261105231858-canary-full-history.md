---
id: 20261105231858
title: canary-full-history
principal: unknown
interest: unknown
hotspot: .github/workflows/build-mod.yml
business_capability: Canary source verification
payoff_trigger: Repository growth materially delays full-history Canary checkouts
quadrant: prudent-deliberate
category: infrastructure
ai_authored: true
created: 2026-11-05
---

ADR 0002 uses full-history checkout so the build job can verify an exact commit against the fetched maintained branch before executing it. This deliberately adds checkout cost to preserve grouped builds of superseded branch tips. Revisit fetch optimization only if repository growth materially delays builds, retaining the same ancestry boundary.
