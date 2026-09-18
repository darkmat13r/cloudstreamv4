# `android-encoder-rules`

A **synthetic** `check=roundtrip` case: 27 values, one or more per branch of `AndroidStringsXml.encode`,
existing only to make that branch fail if it is deleted.

It sits beside `android-encoder-roundtrip`, which is a snapshot of six real `app/src/main/res` directories.
That case proves the encoder handles the corpus *as it is today*. This one proves it handles the rules
*as they are written*, and it is the one that keeps doing so when a translation changes — nothing here is
owned by a translator.

## Why a corpus slice was not enough

The reason is measured, by deleting each branch of `encode` in turn and re-running the guards:

| Branch | Deleting it | Caught by the corpus slice? |
|---|---|---|
| `&` → `&amp;` | **RED** | no — the corpus's only `&` is `values-b+vi`, outside the slice |
| `<` → `&lt;` | **RED** | yes (`values-b+fil`) |
| `\` → `\\` | **RED** | only via `values-b+el/battery_dialog_message`, the corpus's **one** literal backslash |
| `"` → `\"` | **RED** | yes (`values-b+sv`) |
| C0 → `\uXXXX` | **RED** | no |
| `>` → `&gt;` | GREEN | — see below |
| LF → `\n` | GREEN | — see below |
| TAB → `\t` | GREEN | — see below |

Two of those rows are the point of this file. The `&` rule had **no** case at all, and the `\` rule had exactly
one, in a Greek string that Weblate owns: if a translator ever rewrote it, a named rule of the encoder would have
become unguarded across the whole build with nothing going red.

## The three branches that do not bite, and why that is correct

They are not dead code, and they should not be deleted. They are simply not *load-bearing for value fidelity*,
which is the only thing a round trip can measure:

- **`>` → `&gt;`** — a bare `>` is well-formed XML character data. The escape is defensive (it matters only
  next to `]]`), so a round trip cannot distinguish it from doing nothing.
- **LF → `\n`** and **TAB → `\t`** — inside `"…"` aapt2 preserves whitespace verbatim, so a literal newline
  decodes back to a literal newline. What these rules buy is the file *shape*: one element per line, no
  significant whitespace left in the XML text where a re-serialiser could reach it.

**And that is the gap this case cannot close.** The harness runs `aapt2 compile --legacy` directly. A real build
runs AGP's resource merger first, which re-serialises XML — that is why `FindingKind.CR_CHARACTER_REFERENCE`
exists at all. So a rule whose job is to survive *the merger* rather than *aapt2* is invisible here. If the LF or
TAB rule is ever changed, this case will stay green and the evidence has to come from a real
`:app:mergePrereleaseDebugResources`, not from here.

## The values

Each is written in its already-encoded Android form, so the file doubles as a worked example of the encoder's
output. Beyond the eight branches it covers the hazards that motivated quoting in the first place — a bare
apostrophe, a leading `@` and a leading `?`, a lone space, doubled interior spaces, a whitespace-only value and
an empty one — plus things that must pass through untouched: NBSP, RLM, an astral emoji, `%1$s`/`%%`, and the
text `&amp; is text`, which exists to catch double-encoding.

`expect.txt` pins `values=27`, `skipped=0`. Nothing here is a `@string/` reference, so unlike the corpus slice
there is nothing for the decoder to skip — a non-zero `skipped` means a value stopped being what it was
written to be.
