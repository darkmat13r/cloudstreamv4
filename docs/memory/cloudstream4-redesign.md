---
name: cloudstream4-redesign
description: CloudStream 4 cross-platform redesign — scope, priorities and the Claude Design project holding the work
metadata:
  type: project
---

Started 2026-09-12: full redesign of CloudStream for TV, mobile and desktop, drafted in Claude Design
(project "CloudStream 4 — Redesign (TV / Mobile / Desktop)",
https://claude.ai/design/p/a9ab3c83-bcc5-4ef4-854b-846c95da8438).
Local copy, the place to read and update: `design/` at the repo root
(`/Volumes/Projects/CloudStream/app/design`), exported 2026-10-14. Copy of the same files:
https://claude.ai/design/p/c5fd7e44-70f5-41b6-8a48-e850eb88334b ("Project file storage").

Scope decisions the user made:
- **TV first** — the current TV layout is the worst part of the app, so it leads; mobile and desktop follow.
- **Aspirational, not constrained** to what Compose Multiplatform can build today; expensive bits get flagged.
- Features that must get first-class screen real estate (not buried in menus): source/quality
  transparency, extensions & repositories, tracker sync (MAL/AniList/Trakt), downloads & offline,
  and **cross-device syncing** (a new feature the user intends to add, not in the app today).

**Why:** these are what separate CloudStream from Netflix-style apps; a redesign that just copies
Netflix would hide exactly the things that make it worth using.

**How to apply:** when designing any CloudStream screen, ask where source/quality, sync state and
extension health appear on it — they should be visible, not one level down. See [[cloudstream4-no-indigo]].
