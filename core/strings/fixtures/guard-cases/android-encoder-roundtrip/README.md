# `android-encoder-roundtrip`

The one guard case that is an input which must **pass**. It proves `AndroidStringsXml` is a total inverse of
aapt2's decode: `res/` is compiled, every decoded text is written straight back through the encoder, the result is
compiled again, and the two decoded tables are compared value by value. Nothing else runs — not the resolver, not
the materialiser — so the only thing a red here can mean is the encoder.

`expect.txt` pins `values=2850` and `skipped=16`; the seven files declare 2866 `<string>`/`<plurals>` entries, so
the two counts account for every one of them and the case cannot quietly stop covering something.

## The slice

`res/` is a verbatim copy of six directories of `app/src/main/res` at commit `624e80ce`. It is a **snapshot**: it
is not regenerated when Weblate lands a translation, and the full corpus is guarded by the generator's own oracles,
not here. The six were chosen because between them they hold every measured hazard class and nothing outside them
adds one:

| Directory | What it contributes |
|---|---|
| `values` (+ `donottranslate-strings.xml`) | the multi-line values, every format-specifier shape, the `<b>` value (`device_pin_url_message`, which must come back as plain text), and `battery_dialog_message`'s apostrophes |
| `values-b+sv` | `dns_pref_summary`, whose whole value is one space (unquoted, Android trims it to empty), and the `"sett fram"` family — the values that silently lose their quotes if `"` is not escaped |
| `values-b+kn` | `action_open_play`, the only leading-`@` text in the corpus: quoted it stays text, unquoted it becomes a reference |
| `values-b+fil`, `values-b+tl`, `values-b+zh+TW` | the six cells where the Android and Compose locale models disagree — and `values-b+fil` carries the only three `<` values in the corpus |

**What this slice actually guards, measured.** Deleting each branch of `encode` in turn and re-running this
case alone: only `<` → `&lt;` (a compile error in `values-b+fil`) and `"` → `\"` (a value mismatch in
`values-b+sv`) turn it red. Deleting the `&`, `\`, `>`, LF, TAB or C0 rule leaves it **green**.

That is not a defect in the slice — it is what a corpus of real translations contains. But it means this case
is not on its own a guard for the encoder, and an earlier version of this file claimed it was. The `&` rule has
no case here at all (the corpus's only `&` is `values-b+vi/strings.xml string/tracks`, "Âm thanh & video",
outside the slice), and the `\` rule's only corpus witness anywhere is `values-b+el/battery_dialog_message` —
one Greek string, owned by a translator, whose rewrite would silently leave that rule unguarded.

The per-rule guard is the sibling case **`android-encoder-rules`**, which is synthetic and owned by nobody.
Read its README for which three branches cannot be caught by a round trip at all, and why.

## The 16 skipped entries

They are `@string/` aliases, which aapt2 decodes as a `Reference` and which therefore have no text to re-encode.
The generator flattens them; that is a resolver concern, not an encoder one.

```
values-b+sv/strings.xml      string/action_open_play, string/episode_poster_img_des, string/home_main_poster_img_des
values-b+tl/strings.xml      string/search_poster_img_des
values-b+zh+TW/strings.xml   string/action_open_play
values/donottranslate-strings.xml  plurals/episodes, string/change_providers_img_des, string/default_account, …
```

## If this case goes red

Do not re-record anything — there is nothing here to re-record. The failure names the file, the key, and the value
before and after the round trip. Two perturbations that were run to prove the case is not vacuous:

- dropping the surrounding quotes fails the **compile**: `values-b+fil/strings.xml:18: error: unescaped apostrophe
  in string`, and `AAPT2_FAILED` is `BROKEN`, so it throws in both guard modes.
- dropping only the `"` → `\"` rule compiles cleanly and fails the **comparison**:
  `values-b+sv/strings.xml string/action_mark_watched_up_to_this_episode: Markera som "sett fram" till detta
  avsnitt -> Markera som sett fram till detta avsnitt`.
