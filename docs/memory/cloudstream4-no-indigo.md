---
name: cloudstream4-no-indigo
description: CloudStream colour rules — no indigo, and how to extract a palette from a reference correctly (by role, not by area)
metadata:
  type: feedback
---

Two rules the user has given on colour for CloudStream:

1. **No indigo.** The existing accent `#3D50FA` (`colorPrimary`, `CloudStreamPrimaryColor.NORMAL`)
   is rejected. Brand continuity is not a goal.
2. **Extract palettes by ROLE, not by area.** Find the primary button / primary UI component in the
   reference and use that as primary; use the UI's background as background/surface; derive the rest
   from there.

**Why:** ranking colours by how much of the image they cover (median-cut / dominant-colour
extraction) returns backgrounds and poster artwork and never the button — a primary button is only
~0.1–1% of the pixels. Doing that produced a confident but wrong conclusion in this project, that
"real streaming UI stays under 50% saturation". It was measuring blurred artwork averages. Read by
role instead and real primaries land at **73–98% saturation**.

**How to apply:** UI fills are *flat* — one exact RGB repeated over a contiguous region — while
photographic regions never repeat an exact value. So count exact RGB values (no quantisation),
then: background/surface = the largest flat dark low-chroma values; primary = the highest-chroma
flat value with ~0.1–1% area; ink = the flat near-white. Always crop around the detected primary
and look at it to confirm it is actually the button before using it.

Verified primaries: Animax Play `#06C149`, Online Cinema CTA `#C1FD20`, slothUI play `#4F46E5`,
Reelmark Trailer `#E70207`, Daily UI #88 nav pill `#6D38E0`, PLEX brand `#F15A55`,
Kodi `#FD68BD`, Texas Sunset `#FF9B50`.

The category pattern is **a near-neutral dark shell plus one loud primary used sparingly**;
colour otherwise enters through poster artwork. Current directions: Ember (PLEX coral `#F15A55`
on `#141112`), Signal (lime `#C1FD20` on `#010101`), Aurora (green `#06C149` on `#181A20`).
Part of [[cloudstream4-redesign]].
