---
id: 20261110013014
title: feature-gating-source-text-tests
principal: 1d
interest: unknown
hotspot: mod/src/test/java/dev/openintel/chat/ChatChannelFilterTest.java
business_capability: testing
payoff_trigger: regression in chat filter-off rendering or liveClientSmoke coverage expansion
quadrant: prudent-deliberate
category: testing
ai_authored: true
created: 2026-11-10
---

Chat-filter-off behaviour and several feature gates are verified only by source-text or headless checks; no in-game or liveClientSmoke coverage toggles features and confirms rendering stops and state clears. GPU buffer release in WorldOverlayRenderer.release() is likewise untested.
