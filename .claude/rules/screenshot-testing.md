# Screenshot Tests — every feature, every state that matters

**Every feature ships with screenshot tests for its load-bearing states.** A feature is not done
when it renders once on your machine; it is done when a change that alters its rendering fails
the build.

The suite lives in each module's `androidDeviceTest`, as test functions named `screenshot...` that call
`rule.assertScreenshot("the-state")` from `:core:devicetest`. It runs on the `Television_720p` emulator. References are in
`src/androidDeviceTest/resources/screenshots/`. How to run it is in `docs/HANDOVER.md`, "Screenshot tests".

Reference images are committed. A diff is a review artefact: someone looks at it and either
approves the change or fixes the regression. Re-recording to make red go away is the one thing
this rule exists to prevent.

## Current status — read before you promise this in a PR

**It renders and it guards (`INF-11`).** `captureToImage` on a device, with no new dependency. The owner asked for
Compose screenshot tests and `DEC-10` records the choice. `com.android.compose.screenshot:0.0.1-alpha16` is still blocked: it renders
nothing on AGP 9.1.1 and Gradle 9.4.1 and cannot see KMP modules. `:core:screenshot` holds its unused cases, and `core/screenshot/build.gradle.kts`
has that evidence. Do not add cases there.

**No tolerance, because none was measured.** The same six cases on separate emulator boots differed in 0 pixels, with a largest
channel delta of 0. A one-step change to one channel of `live` failed the cases that show it.

**It is not part of `check`**, and CI has no emulator. It runs in the device gate, so a PR says whether it ran.

**What it cannot catch:**

- Anything outside the window's surface: the keyboard, a `SurfaceView`, video.
- A `Dialog` or `Popup`, which is a second window, makes `onRoot` throw. It fails loudly and is not yet supported.
- Motion. It holds the settled frame, and infinite animations are cancelled by the rule's clock.
- Other devices. References are one AVD image at 1280x720, so a new system image is a re-record with every diff read.
- The boards' fonts. The theme passes no Manrope, so these show the platform font. Compare layout and colour with a board, not type.

## What earns a case

Add one when a state is **load-bearing**:

- a failure that must stay legible — the whole point of the redesign's state work
- a disabled control that must not read as enabled at three metres
- a state whose geometry is easy to break silently: a text stack whose height must stay a
  multiple of 16dp to centre on the step, a chip whose count line box must divide cleanly
- a token whose contrast matters
- the empty and offline variants of any surface that has them

Do **not** add a case for every permutation. A suite nobody reads the diffs of is worse than no
suite — it trains people to re-record without looking.

## Writing a case

A test function in the module's `androidDeviceTest`, beside the screen's other device tests:

- Name it `screenshot` plus the state, and name the image for the state: `screenshotHistoryFocusOnARow`, `search-history-focus-row`.
- Put focus on something with `DpadDriver`. The ring is half of what a TV screen shows.
- A fake at the module's seam, and no network. Posters are `solidPoster(...)`.
- Draw the screen the way the app does, with the rail beside it if the shell puts one there, or its x will not match the board.
- Capture the window unless a case truly is one component. `assertScreenshot(name, node)` keeps the ring; a bare `node.captureToImage()` loses it,
  because the ring is entirely outside the node's bounds.

## Review checklist

- Does every new feature have at least its failure and empty states covered?
- Is each case a state someone would want to be told about, or is it permutation filler?
- Did the diff get looked at, or was it re-recorded?
