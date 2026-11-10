---
id: 20261110013014
title: wartable-off-drops-relay
principal: 0.5d
interest: stale War Table until next full sync after re-enabling
hotspot: mod/src/main/java/dev/openintel/wartable/WarTable.java
business_capability: war-table
payoff_trigger: report of missing strokes after toggling War Table back on
quadrant: prudent-deliberate
category: code_quality
ai_authored: true
created: 2026-11-10
---

While the War Table feature is off, incoming relay strokes and syncs are dropped rather than queued. Turning it back on shows only what the next relay sync sends; no resync is requested on enable.
