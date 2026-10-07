# Vendored from Mihon — Apache License 2.0

This directory and `shared/src/androidMain/kotlin/com/mihon/` hold **29 Kotlin files vendored from
[Mihon](https://github.com/mihonapp/mihon)**, licensed under the Apache License 2.0. A verbatim copy
of the licence is in `LICENSE` beside this file; the attribution required when redistributing it is
in the repository's root `NOTICE`.

    Copyright © 2015 Javier Tomás
    Copyright © 2024 Mihon Open Source Project

Mihon has a good Compose settings framework and does not publish it as a library, so the files we
want are forked rather than depended on.

**Every file here is modified**, mostly to build for Kotlin Multiplatform rather than Android only —
and three of them are modified only in the `package` line. Which is which is below, because
"modified" without that detail is not much use to anyone checking the licence.

## Please do not add first-party code to this package

Everything under `com.mihon` is third-party code under a licence that is not this project's own.
New CloudStream code belongs in `com.lagradost.cloudstream4`, even when it sits next to a vendored
widget. Mixing the two makes the boundary this file describes impossible to state.

## Upstream mapping

Measured against Mihon `main` as fetched **2026-10-07**, by fetching each upstream file and diffing
it. `-` counts lines only upstream has, `+` lines only we have. Upstream path prefixes:

| key | prefix |
|---|---|
| **A** | `app/src/main/java/eu/kanade/presentation/` |
| **P** | `presentation-core/src/main/java/tachiyomi/presentation/core/` |
| **C** | `core/common/src/main/kotlin/tachiyomi/core/common/` |

Our paths are relative to this directory, except the two marked *androidMain*.

| Our file | Upstream | lines (ours/up) | − / + |
|---|---|---|---|
| `material/AppBar.kt` | **A**`components/AppBar.kt` | 405 / 449 | 100 / 57 |
| `material/SettingsItems.kt` | **P**`components/SettingsItems.kt` | 474 / 458 | 30 / 46 |
| `material/Scaffold.kt` | **P**`components/material/Scaffold.kt` | 85 / — | 2 / 3 |
| `material/Slider.kt` | **P**`components/material/Slider.kt` | 58 / — | 14 / 15 |
| `material/LabeledCheckbox.kt` | **P**`components/LabeledCheckbox.kt` | 50 / — | 2 / 1 |
| `material/Constants.kt` | **P**`components/material/Constants.kt` | 26 / 26 | **package line only** |
| `material/Pill.kt` | **P**`components/Pill.kt` | 61 / 61 | **package line only** |
| `material/Typography.kt` | **P**`theme/Typography.kt` | 14 / 14 | **package line only** |
| `presentation/Modifier.kt` | **P**`util/Modifier.kt` | 119 / — | 6 / 4 |
| `presentation/Navigation.kt` | **A**`util/`**`Navigator.kt`** | 8 / 75 | partial extraction — keeps `LocalBackPress` only |
| `presentation/settings/Preference.kt` | **A**`more/settings/Preference.kt` | 197 / 188 | 39 / 49 |
| `presentation/settings/PreferenceItem.kt` | **A**`more/settings/PreferenceItem.kt` | 188 / 214 | 46 / 20 |
| `presentation/settings/PreferenceScreen.kt` | **A**`more/settings/PreferenceScreen.kt` | 96 / — | — |
| `presentation/settings/PreferenceScaffold.kt` | **A**`more/settings/PreferenceScaffold.kt` | 49 / 33 | 23 ours-only |
| `presentation/settings/SearchableSettings.kt` | **A**`more/settings/screen/SearchableSettings.kt` | 39 / — | — |
| `presentation/settings/Search.kt` | **A**`more/settings/screen/`**`SettingsSearchScreen.kt`** | 165 / 311 | 233 / 87 — substantial rework |
| `presentation/settings/widget/` × 8 | **A**`more/settings/widget/<same name>.kt` | — | `MultiSelectListPreferenceWidget` +30; rest small |
| `common/preference/PreferenceStore.kt` | **C**`preference/PreferenceStore.kt` | 87 / — | — |
| `common/preference/PreferenceData.kt` | **C**`preference/`**`Preference.kt`** | 71 / 71 | 7 — a rename; our `PreferenceData<T>` is upstream's `Preference<T>` |
| `common/preference/InMemoryPreferenceStore.kt` | **C**`preference/InMemoryPreferenceStore.kt` | 125 / — | — |
| `common/preference/AndroidPreference.kt` *(androidMain)* | **C**`preference/AndroidPreference.kt` | 235 / 236 | 4 / 3 |
| `common/preference/AndroidPreferenceStore.kt` *(androidMain)* | **C**`preference/AndroidPreferenceStore.kt` | 101 / — | — |

The eight widget files are `BasePreferenceWidget`, `EditTextPreferenceWidget`, `InfoWidget`,
`ListPreferenceWidget`, `MultiSelectListPreferenceWidget`, `PreferenceGroupHeader`,
`SwitchPreferenceWidget` and `TextPreferenceWidget`; each maps to the same filename upstream.

Three rows are worth reading twice, because the filename does not tell you where the code came
from: `Navigation.kt` ← `Navigator.kt`, `PreferenceData.kt` ← `Preference.kt`, and `Search.kt` ←
`SettingsSearchScreen.kt`.

## The baseline problem

**No upstream commit SHA was recorded when this was vendored**, so there is no fixed point to diff
against. The tree arrived on 2026-09-08 in `92b3ea92`, inherited from upstream CloudStream, and the
table above compares against whatever `main` held on 2026-10-07 — which moves.

If you update any of these files from upstream, record the commit you took them from, here. That
one line is the difference between a tree someone can audit and a tree someone has to guess at.
