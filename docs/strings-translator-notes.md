# Translator notes, recovered from `app/src/main/res`

The strings flip regenerates `app/src/main/res/values*/strings.xml` from the authored Compose tree
(`I18N-07`, PR 4). A generated file carries no comments, so the **172 XML comments** that lived in
those 63 files are about to go. 127 were instructions to translators and 45 were commented-out
resources.

Most of the instructions were duplicated into every locale by whatever tool copied the base file —
"TRANSLATE, BUT DON'T FORGET FORMAT" appears 18 times and says the same thing each time. What was
actually being said is about **ten keys**, and that is what this file keeps. It is a record of the
knowledge, not of the markup.

Measured with a script over the committed res at `1254d2bd`, before anything was deleted.

---

## The keys the comments were about

### `player_speed_text_format` — *"TRANSLATE, BUT DON'T FORGET FORMAT"* (18 copies)

The literal text may be translated; the format specifier must survive intact.

### `app_dub_sub_episode_text_format` — three separate warnings

- *"FORMAT MIGHT TRANSLATE, WILL CAUSE CRASH IF APPLIED WRONG"* (14 copies)
- *"KEYS DON'T TRANSLATE"* (4 copies, in `cs`, `pt-BR`, `tl`, `vi`)
- a link to [concatenating strings in XML](https://newbedev.com/concatenate-multiple-strings-in-xml)

The sharpest warning in the corpus, and the one most worth not losing: this value is assembled
from parts, and a translator who moves or renames a key crashes the app rather than producing odd
text. `I18N-05` already records three translations that are broken in exactly this family of ways.

### `result_poster_img_des` — two notes

- *"IS NOT NEEDED TO TRANSLATE AS THEY ARE ONLY USED FOR SCREEN READERS AND WONT SHOW UP TO NORMAL USERS"* (15 copies)
- *"FORMAT MIGHT TRANSLATE, WILL CAUSE CRASH IF APPLIED WRONG"*

Note the first claim is **wrong about who reads it**: a content description is exactly what a
screen-reader user hears, so it is the only text they get for that element. "Not needed" meant
"low priority", and it reads as "nobody sees this". Worth correcting rather than preserving.

### `subtitles_example_text` — the pangram (14 copies)

> Example text (pangram) can optionally be translated; if you do, include all the letters in the
> alphabet, see
> [Pangram](https://en.wikipedia.org/w/index.php?title=Pangram&oldid=225849300) ·
> [The quick brown fox…](https://en.wikipedia.org/wiki/The_quick_brown_fox_jumps_over_the_lazy_dog)

It is the subtitle-style preview, so it has to exercise the alphabet, not merely mean something.

### `movies` / `movies_singular` — *"plural"* / *"singular"* (14 copies each)

Two sibling keys whose names do not make the distinction obvious at the point of translation.
These are `<string>` resources, not a `<plurals>`; the comment existed because that is surprising.

### `example_password`, `login_format` — *"account stuff"* (13 copies)

A section header, not an instruction.

### Single-site notes

| Key | Comment | Where |
|---|---|---|
| `biometric_authentication_title` | For Biometrics | `values` |
| `update_plugins_manually` | Manual Update Button | `values` |
| `confirm_before_exiting_title`, `show` | confirm exit dialog | `values` |
| `disable`, `none` | `============` (a divider, 15 copies) | 15 dirs |
| `app_name` | "For true intellectuals" | `values-b+qt` — a joke in the pseudo-locale |

---

## Home's bookmarks and offline words (`TV Home Extras`, 2026-11-06)

- **`home_bookmarks_no_status_headline`**, *"No status chosen"*: the **watch status** of a bookmark (Watching,
  Plan to Watch, On-Hold, Dropped, Completed, which are `type_watching` and the rest), not a connection or a download
  status. Shown when the person has cleared the filter chip above the bookmarks rail.
- **`home_bookmarks_no_status_body`**: *"one"* is one of those status chips, which sit above this line.
- **`home_offline_source_headline`**, *"%1$s needs a connection"*: `%1$s` is the name of the extension's source, as the
  source names itself (`Source A`), never translated.
- **`home_bookmarks_count`**: a count in the monospaced line beside *"You are offline"*. Written in capitals in English
  because that line is; a script without case keeps its own form.

- **`home_source_none_subtitle`**, *"NO RAILS · YOUR ROWS STAY"*: the line under **None** in the list of sources. *Rails*
  are the rows of titles a source sends; *your rows* are Continue watching and the bookmarks, which are kept on the device
  and stay when no source is chosen. Capitals as for `home_bookmarks_count`.
- **`home_none_headline`**, **`home_none_body`**: shown when **None** is the chosen source and nothing is kept on the
  device. *Continue watching* is the name of that row (`continue_watching`).

## Commented-out resources (45)

Translations left behind when a key was deleted from the base. They are dead, and measurably so:
`need_storage` is commented out in **all 18** files that mention it, `values/strings.xml`
included, and `grep` over `app/src` finds no reference to it in any Kotlin, Java or layout file.
The resource does not exist.

| Key | Locales it lingers in |
|---|---|
| `need_storage` | 18 files — `values` plus `ar cs el fr hi hr in mk nl no pt-BR qt tl tr vi zh zh-TW` |
| `app_name`, `episode_sync_settings`, `subtitles_remove_captions`, `subtitles_remove_bloat` | `it`, `ro` |
| `skip_type_op`, `skip_type_ed`, `skip_type_intro`, `skip_type_mixed_op`, `skip_type_mixed_ed` | `it` |
| `options` | `el` |
| a block of subtitle and player settings keys | `ml` |

Nothing here needs doing. It is listed so the next person to notice `need_storage` in a diff does
not have to work out whether it was ever live.

---

## Where this guidance goes from now on

After the flip, `app/src/main/res` is generated and is not the place to write anything for a
human. Guidance about a specific key belongs:

- **in the code that uses it**, as a KDoc on the call site, for anything about format arguments;
- **in `docs/tasks/i18n.md`**, for defects in the translation data (`I18N-05` is the existing list);
- **here**, if it is advice to whoever next edits a value and belongs to no single call site.

The three keys worth carrying forward as real guards rather than prose are
`app_dub_sub_episode_text_format`, `player_speed_text_format` and
`next_episode_time_hour_format` — all format strings, all already known to be mistranslated
somewhere (`I18N-05`).
