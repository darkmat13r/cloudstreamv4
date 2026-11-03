# Desktop design

What the desktop form factor is, and where each decision came from.

**Owner's direction, 2026-11-02:** use Apple's design principles for layout, UX, UI and spacing. That
answers `screen-implementation-handover.md`, which recorded "Desktop: nothing exists" and said to derive
each form factor from `Design System v2` deliberately. No artboards were drawn. The screens are built and
rendered instead, which Apple's process prefers, so **a designer may still want to review them**.

## What comes from where

| Decision | Source |
|---|---|
| The nine colour roles, and no accent (`DEC-13`) | `design/Design System v2.dc.html` |
| Type sizes: 40, 32, 24, 16, 16, 12, 12, 12 | the desktop column of the same file's §03 |
| Every `dp` on the 4dp step | `.claude/rules/dimension-scale.md` |
| Icons from one pack, through `CloudStreamIcons`, never drawn | `.claude/rules/icons.md`, `DEC-11` |
| Layout, hierarchy, spacing, motion, interaction | Apple's principles, the `apple-design` skill |

The font stays the theme's. Apple's advice to prefer the system font loses to the design system's measured
line boxes.

## How it differs from TV, and why

| TV | Desktop | Why |
|---|---|---|
| Fixed 960×540 dp | 1280×800, resizable, minimum 800×520 | Flexibility |
| Focus lands on the first action | The search field holds focus, and nothing else takes it | Typing is the point. A search that fails must not swallow the next keystrokes |
| `Browse home` on the idle screen | Words only | Purpose: home is in the window's bar, and the caret is already the action |
| Horizontal rails | An adaptive grid, sections per source | A mouse wheel scrolls one way |
| Home's rails | One line of cards per row, opened downward by the row's own `Next` | The same, and every row stays in reach; the button stays in the row's header, so it does not move when pressed |
| Top-level rail | Home and Search as two text tabs above the screen | Two destinations do not earn a sidebar, and there is no icon pack yet |
| Title revealed on focus | Title always visible | A pointer has no focus to reveal it with |
| White ring on the search field | A brighter hairline; the ring stays for what Tab lands on | Restraint: the caret already says it |
| Source list replaces the page | A panel from the right over a dimmed page, leaving the same way | Spatial consistency; a click outside dismisses it |
| Episodes behind a selector | On the page | A window has the room |
| Full-width player scrim | Floating controls, at most 720 wide | The picture stays clear, and play stays near its timeline |
| Tap to seek | The track follows the pointer 1:1, and seeks once on release | Direct manipulation; a seek is a network request |

## Spacing

`DesktopDimens`: window margin 24, between sections 32, within a group 16, related items 8. Prose stops at
640, however wide the window. A list sits 12 further out than its column, so a row's **text** lines up with
its header and only the hover fill passes it.

## Icons

`DesktopIconButton` is the glyph-only button: the same fill, hover and press as `DesktopButton`, 36 square so the
two line up. A glyph beside words is 16 and says nothing to a screen reader, because the words do. A glyph alone is
20, and its description comes from `:core:strings`. The primary button stays the one filled white; a control inside
something that already has a fill is `Quiet`, with no fill until the pointer arrives.

The search field's clear button never takes focus, so Tab goes past it and pressing it leaves the caret in the field.
Escape is the keyboard's way to do the same.

## Motion

`DesktopMotion`: a critically damped spring with a 0.3 s response, so no overshoot. A spring starts from the
value on screen, so an interrupted hover never jumps. Feedback arrives on press, not release. Only a card's
art scales, because scaling words makes them shimmer.

## Not done

- **Reduced motion** is not read from macOS, so springs always run. They are small and non-vestibular apart
  from the panel.
- **Translucent materials.** Compose has no backdrop blur without a new dependency, so the player's controls
  are a near-opaque surface.
- **Nav sidebar:** two tabs do the job for now. It arrives with Library.
- **Fullscreen** from the player, and **Cmd+F**.
- **The player's seek buttons are mislabelled for a screen reader.** They say `previous` and `next`, the nearest
  strings there are. A string saying what they do moves `core/strings/oracle-baseline` (`DEC-11`).
- **Back and the seek glyphs do not mirror** in a right-to-left layout.
- **16:9 thumbnails are cropped into 2:3 cards in search**, which can cut off burned-in text. Search results do
  not report an image's shape. A main page does (`isHorizontalImages`), so home's rows draw 16:9 where asked.
- The idle body says "one rail per source", and desktop draws a grid. It is the shared string, and changing
  it moves `core/strings/oracle-baseline`.
