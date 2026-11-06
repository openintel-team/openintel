---
id: 20261105220612
title: canary-grouping-heuristics
principal: unknown
interest: unknown
hotspot: .github/scripts/canary.py
business_capability: Canary build grouping
payoff_trigger: A false group, repeated missed backports, or checkpoint expiry recovery materially delaying builds
quadrant: prudent-deliberate
category: release
ai_authored: true
created: 2026-11-05
---

ADR 0001 chooses conservative local title/file/patch matching to guarantee no AI service charges. The 90-second collection window can miss late backports, and expired-checkpoint recovery enumerates the push-event history. Revisit matching on observed classification errors and revisit checkpoint retention if expiry recovery delays scheduling.
