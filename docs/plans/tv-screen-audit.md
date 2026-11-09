> **A static read of the code at `c0548f7ff`, 2026-11-04, by one read-only audit.** Nothing here was run. It is the planning map for the
> owner's 2026-11-04 mandate (whole UI, end to end, with Android UI automation). Each vertical re-checks what it relies on, and marks
> its row done here rather than leaving this to drift. Path aliases `WT/` refer to the repository root. Items it says wait on an owner
> decision are recorded in `docs/tasks/decisions.md` once answered.

# CloudStream Android TV redesign: audit of what exists, as of `c0548f7ff` (2026-11-04)

Static read only. I ran no Gradle, emulator or device, so "exists" means the source says so, not that it runs.

## Path key (all `file:line` citations use these)

| Alias | Absolute path |
|---|---|
| `WT/` | the root of the worktree this audit was made in |
| `A/` | `WT/app/src/main/java/com/lagradost/cloudstream3/` |
| `A4/` | `WT/app/src/main/java/com/lagradost/cloudstream4/` |
| `MA` | `A/MainActivity.kt` |
| `NAV` | `WT/app/src/main/res/navigation/mobile_navigation.xml` |
| `FS/` `FD/` `FH/` `FL/` `FX/` | `WT/feature/{search,detail,home,library,extensions}/src/commonMain/kotlin/com/lagradost/cloudstream4/feature/{search,detail,home,library,extensions}/` |
| `DS/` | `WT/core/designsystem/src/commonMain/kotlin/com/lagradost/cloudstream4/core/designsystem/` |
| `DIM` | `WT/core/theme/src/commonMain/kotlin/com/lagradost/cloudstream4/core/theme/CloudStreamDimens.kt` |
| `DK/` | `WT/desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/` |
| `API` | `WT/app/api/classes-jar.api` |
| `H` / `P` / `K` / `HO` | `WT/docs/plans/screen-implementation-handover.md`, `WT/docs/plans/kmp-migration.md`, `WT/docs/tasks/kmp.md`, `WT/docs/HANDOVER.md` |
| Designs | `design/ in your own worktree, from master, <name>.dc.html` |

`design/` is tracked on master, and **master's copy is the one to build from**, because it carries the `DEC-17` corrections (#135, #136). The untracked `design/` in the primary checkout is an older copy from 2026-10-14.

## Headline facts

1. **No redesign screen is reachable in `:app` on master.**
   - `NAV` still names only old fragments (`NAV:172`, `:293`, `:311`, `:326`, `:441`, `:514`, `:564`).
   - No file in `A/` references `cloudstream4.search` or `cloudstream4.extensions`.
   - `SearchScreenTv` and `DetailScreenTv` have no non-preview caller anywhere. `DK/Main.kt:467` calls `DetailScreenDesktop`.
2. **Only 2 TV screens exist, both in commonMain.** They are `SearchScreenTv` (`FS/tv/SearchScreenTv.kt:69`) and `DetailScreenTv` (`FD/tv/DetailScreenTv.kt:78`). There is no TV Home, Library, Downloads, Onboarding, Extensions, Account or Player.
3. **`:app` only reaches `:feature:search` and `:feature:extensions`** (`WT/app/build.gradle.kts:310,314`). `:feature:detail`, `:feature:home`, `:feature:library` and `:media:subtitle` are not edges.
4. **Android host implementations are nearly absent.** `SearchSource` and `SearchHistoryStore` have Android implementations in `A4/search/`, unused on master. `HomeSource`, `DetailSource` and `LibraryStore` are implemented only on desktop (`DK/home/DesktopHomeSource.kt:33`, `DK/detail/DesktopDetailSource.kt:36`, `DK/library/DesktopLibraryStore.kt:64`).
5. **PR #91 is the only open PR.** It is a draft, not merged, 5 commits ahead of and 59 behind master. It holds `SearchTvFragment` plus a runtime repoint. It has been compiled but never run.
6. **`SidePanel` and `SidePanelHost` have zero callers** outside `:core:designsystem`. `CloudStreamIcons` has zero callers in any non-desktop screen.

## 1. Artboard table

The count from `shotn` labels is **34**: Home 4, Search 4, Suggestions 5, Library 5, Episodes/Downloads 5, Detail/Player 6, Onboarding 5. `Design System v2` and `Components` have none.

Conventions:
- "Not reachable" means no caller on master. Users see the old screen named in the column.
- † marks a fact taken from the design's closing note and not re-checked by me. Everything else was grepped.

| File + id | Screen, state | Compose code | Source set | In `:app` today | Note's prerequisites, and whether the facts exist |
|---|---|---|---|---|---|
| TV Home 3a | Shell focus contract | No rail composable. Tokens only, `DIM:147-153` | commonMain (tokens) | No. Old XML rail at `WT/app/src/main/res/layout/activity_main_tv.xml:27-58` | Note lists none. **Mismatch:** token rail 76dp vs XML 62dp (`WT/app/src/main/res/values/dimens.xml:22`) |
| TV Home 3b | Rail focused, expanded overlay | None | none | No. Focus-expand is commented out, `MA:1778-1825` | None listed. Items undecided, see §5 C12. No Live TV, Movies or TV Shows destination in `NAV` |
| TV Home 3c | Hero + Continue watching | **Built since, `KMP-41`:** `FH/tv/HomeHeroTv.kt`, `FH/tv/HomeContinueTv.kt`. At the audit: no TV. `FH/desktop/HomeScreenDesktop.kt:85` is a different form factor, with no hero | commonMain, desktop-only caller `DK/home/DesktopHomeHost.kt:66` | Desktop only. Old: `A/ui/home/HomeFragment.kt` | None listed. Missing: `HomeItem` holds only id, title, poster (`FH/HomeContracts.kt:43`), so no hero meta or per-card source and quality. Resume exists only in old `A/ui/home/HomeViewModel.kt:63-108` |
| TV Home 3d | Rails, trailing See all | No TV. Desktop as above. `SeeAllCard` exists, `DS/card/SeeAllCard.kt:51` | commonMain, desktop caller | Desktop only | None listed. Rows, paging and partial exist (`FH/HomeMvi.kt:30,53,67,80`) |
| TV Search 4a | Results, one rail per provider | `FS/tv/SearchResultRails.kt:61` | commonMain | Not reachable; #91 only | No See all (no such code in the file). Grouping exists, `FS/SearchContent.kt` |
| TV Search 4b | Partial, failure in chip row | `FS/tv/SearchChromeRow.kt:101` + `FailureBanner` | commonMain | Not reachable; #91 only | Per-provider list reduced to answered, total, detail (`FS/SearchContracts.kt:121-126`). Provider chips absent, `FS/tv/SearchChromeRow.kt:95-98` |
| TV Search 4c | History | `FS/tv/SearchRecentColumn.kt:56` | commonMain | Not reachable; #91 only | `whenLabel` exists (`FS/SearchHistory.kt:25`). Per-row remove has an action but nothing emits it (`FS/SearchMvi.kt:98`, `FS/tv/SearchRecentColumn.kt:40-43`) |
| TV Search 4d | Source picker panel | None. Sources chip is `onClick = {}` (`FS/tv/SearchChromeRow.kt:82`) | none | No | `SearchSource` is read-only (`FS/SearchContracts.kt:86`). No selection write seam |
| TV Search Suggestions 8a | Suggestions while typing | None | none | No. Old: `A/ui/search/SearchViewModel.kt:98`, `A/ui/search/SearchSuggestionApi.kt` | Note: needs `UiState`, not an Idle/Suggesting/Results enum; focus order over the full result set. No suggestion type in `:feature:search`. Dedup and per-suggestion counts missing |
| TV Search Suggestions 8b | Suggestions, nothing matches | None | none | No | Same as 8a |
| TV Search Suggestions 8c | Search, no results | `SearchScreenTv` + `NoResults` (`FS/SearchMvi.kt:50`) | commonMain | Not reachable; #91 only | Status exists. Type filter half of the action missing (`FS/tv/SearchChromeRow.kt:95-98`) |
| TV Search Suggestions 8d | Quick Search panel, answering | None | none | No. Old: `A/ui/quicksearch/QuickSearchFragment.kt`, `NAV:441` | `quickSearch` seam exists (`FS/SearchContracts.kt:97`), streaming into a panel does not |
| TV Search Suggestions 8e | Quick Search, empty | None | none | No | Same as 8d |
| TV Library 5a | Grid, WatchType tabs, chrome | No TV. `FL/desktop/LibraryScreenDesktop.kt:84` | commonMain, desktop caller `DK/library/LibraryHost.kt:52` | Desktop only. Old: `NAV:173` | Per-page counts missing (`A/syncproviders/SyncAPI.kt:133`, title + items only). Item facts exist in old model (`SyncAPI.kt:179-182`) but not in `LibraryCard` (`FL/LibraryMvi.kt:36`). **`:feature:library` has no provider concept** (`FL/LibraryContracts.kt:47,145`) |
| TV Library 5b | Provider unreachable, CACHED | No TV | none on TV | No | `lastSyncedUnixTime` has 0 hits. Failure untyped (`A/ui/library/LibraryViewModel.kt:109`). UI drops it (`A/ui/library/LibraryFragment.kt:472-473`). `LibraryStatus` has no Offline or Partial (`FL/LibraryMvi.kt:49-58`) |
| TV Library 5c | Empty page | No TV. `LibraryStatus.Empty` exists | commonMain state | Desktop only | Tab counts as 5a |
| TV Library 5d | Sort panel | No TV | none | No | `ListSorting` is at `A/ui/library/LibraryViewModel.kt:19`. No sort in `:feature:library`. A disabled row needs `ListRow` support (see §3) |
| TV Library 5e | Provider panel | No TV | none | No | Sync state and `lastSynced` missing, as 5b |
| TV Episodes Downloads 6a | Episode panel | No. `DetailScreenTv` puts a list on the page instead (`FD/tv/DetailScreenTv.kt:68`) | commonMain, but not this design | No. Old: `A/ui/result/ResultFragmentTv.kt` | `ResultEpisode` has no `downloadState` (`A/ui/result/ResultFragment.kt:40`). `_episodes` never gets `Failure` (`A/ui/result/ResultViewModel2.kt:1951-2212,2618,2665`, failures go to `_page` at `:2673,:2693,:2705`). TV passes `false` for download support (`ResultFragmentTv.kt:432-433`) |
| TV Episodes Downloads 6b | Episode row states | None | none | No | Rate exists but notification-only (`A/utils/downloader/DownloadManager.kt:691`). Event is a bare `Triple` (`:211,:735`). Queue index (`DownloadQueueManager.kt:42-48`). Typed reason missing (`DownloadObjects.kt:184-191`) |
| TV Episodes Downloads 6c | Downloads, titles + meter | None, no `:feature:downloads` | none | No. Old: `A/ui/download/DownloadFragment.kt`, `NAV:327` | **Storage data exists:** `A/ui/download/DownloadViewModel.kt:57-61,419`. Multi-delete exists at `:433` |
| TV Episodes Downloads 6d | One series, three verbs per row | None | none | No. Old: `NAV:452` | Same as 6b. Failed has no re-entry edge†. Row verbs need a `ListRow` trailing focus stop |
| TV Episodes Downloads 6e | Empty + offline + storage full | None | none | No | No connectivity flow: only one-shot `A/utils/AppContextUtils.kt:622`. Shortfall blocked on typed reason. **Past 50 MB a failure is recorded as complete** (`DownloadManager.kt:192,565`) |
| TV Detail Player 7a | Show detail | **Partial:** `FD/tv/DetailScreenTv.kt:78`. No rail, bookmark, favourite or cast (`:65-71`) | commonMain | Not reachable. Old: `NAV:514`. `:feature:detail` not an `:app` edge | `ResultData` has actors but no season integer or per-season count (`A/ui/result/ResultViewModel2.kt:169-170`). `TitleDetail` lacks all of it (`FD/DetailContracts.kt:23`). Resume label†. Per-person image mode, actor id† |
| TV Detail Player 7b | Movie detail | **Partial:** same | commonMain | Not reachable | Start over needs explicit `startPosition = 0L`†. Download control is new. No "no playable source" signal† |
| TV Detail Player 7a-ii | Detail scrolled, More like this | None | none | No | Recommendations not in `TitleDetail`. Full-cast panel "not yet drawn" (design's own words) |
| TV Detail Player 7c | Link loading | **Reduced:** `LinkList` inside `DetailScreenTv`. Streaming exists in the contract (`FD/DetailContracts.kt:68`) | commonMain | Not reachable. Old: spinner in `A/ui/player/GeneratorPlayer.kt` | Grep finds no `SourceId`, `onSourceDiscovered` or `ResolutionFailure`. `MainAPI.loadLinks` has no hook (`WT/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt:692-698`). Provider failure flattened (`WT/core/data/src/jvmCommonMain/kotlin/com/lagradost/cloudstream3/ui/APIRepository.kt:214-222`). **Mostly blocked on merged, published code** |
| TV Detail Player 7d | Player HUD | Desktop only: `DK/player/PlayerScreen.kt:539`, bound to `VlcPlayer` (`:109-110`) | jvmMain in `:desktopApp` | Desktop only. Old: `NAV:564`, `A/ui/player/FullScreenPlayer.kt` | Buffered position: `IPlayer` has no getter, value is at `A/ui/player/live/LivePreviewTimeBar.kt:26`. Codec gated (`GeneratorPlayer.kt:2005`). Next episode free (`PlayerGeneratorViewModel.kt:273`). No-pause panel, auto-hide, time bar focus† |
| TV Detail Player 7e | Source, subtitle, audio panels | Desktop only: `DK/player/SourcePicker.kt:78`, `DK/player/SubtitlePicker.kt`, `DK/player/AudioTracks.kt:79` | jvmMain | Desktop only | Track overrides exist (`CS3IPlayer.kt:348,376`). Active subtitle known (`:557`). Hidden links count† |
| TV Onboarding Extensions 8a | First run, no sources | None. No `:feature:setup` | none | No. Old: `NAV:592,623,648,666,684` | `PREBUILT_REPOSITORIES` is read and never written (`A/plugins/RepositoryManager.kt:100-101`). **Three rows have no data source.** No progress or cancel (`RepositoryManager.kt:193`). `NotConfigured` outcome missing |
| TV Onboarding Extensions 8a-ii | First run, index unreachable | None | none | No | Same, plus connectivity |
| TV Onboarding Extensions 8b | Extensions installed | None. `DK/extensions/ExtensionReviewScreen.kt` is a different surface | none | No. Old: `NAV:199` | `RepositoryData` holds 3 fields (`A/ui/settings/extensions/ExtensionsViewModel.kt:22-26`). `PluginData` 5 fields (`A/plugins/PluginManager.kt:79-85`). `isOutdated` not surfaced (`PluginManager.kt:229`). Unreachable vs empty not distinguished (`RepositoryManager.kt:183`) |
| TV Onboarding Extensions 8c | Browse a repository | None | none | No. Old: `NAV:230` | Contract types exist unused (`FX/ExtensionInstall.kt`). `AndroidExtensionRuntime.install` refuses (`A4/extensions/AndroidExtensionRuntime.kt:69-79`). Min-app-version missing (`RepositoryManager.kt:59,163,170`) |
| TV Onboarding Extensions 8d | Account select, Simkl pairing panel | None. No `:feature:account` | none | No. Old: `A/ui/account/AccountSelectActivity.kt`, `NAV:694` | Simkl flow exists (`A/syncproviders/providers/SimklApi.kt:58`, `A/ui/settings/SettingsAccount.kt:156`). OAuth-only providers (`MALApi.kt:42`, `AniListApi.kt:44`). Delete profile bug† (`A/ui/account/AccountViewModel.kt:73`). Skip check (`AccountSelectActivity.kt:76`) |

**Added after this audit:** `TV Home Extras` 3e to 3m (`DS-13`) draws what §2 lists as not drawn for Home: the bookmarks row and filter, the Random button and the `None` source (the owner ruled Random out as a source), a details panel, and manual reload. Nothing is built; the file's closing note lists each board's prerequisites.

## 2. Feature gap list

"Shared" means a commonMain implementation exists. Items that have a desktop or commonMain counterpart are marked as such.

| Old feature | Evidence | Shared? | Drawn on TV? | Home |
|---|---|---|---|---|
| **Home** | | | | |
| Random button | `A/ui/home/HomeFragment.kt:815-816` | No | Not drawn | `:feature:home` |
| Bookmarks row + watch-status filter | `A/ui/home/HomeParentItemAdapterPreview.kt:247`, `HomeViewModel.kt:139` | No | Not drawn | `:feature:home` |
| Continue watching, remove from it | `HomeViewModel.kt:63-113` | Desktop analog in `:feature:library` | Drawn 3c (remove not drawn) | `:feature:home` + a resume seam |
| Hero carousel from `LoadResponse` | `HomeViewModel.kt:147-152,314` | No | Drawn 3c | `:feature:home` |
| Expanded row sheet | `HomeFragment.kt:136`, `HomeViewModel.kt:280` | Partly (`RowMoreRequested`) | Trailing See all only; destination not drawn | `:feature:home` |
| TV channels integration | `A/utils/TvChannelUtils.kt:24` | No | Not drawn | stays in `:app` |
| **Search** | | | | |
| Voice search | `A/ui/search/SearchFragment.kt:253-274` | No | Not drawn; owner dropped it for now (PR #91 body) | none |
| Type filter chips + provider sheet | `SearchFragment.kt:205` | No | Drawn 4d, 8c | `:feature:search` |
| Suggestions | `A/ui/search/SearchViewModel.kt:98` | No | Drawn 8a/8b | `:feature:search` |
| Metadata long-press | `A/ui/search/SearchHelper.kt:17` | No | Not drawn | `:feature:search` |
| Expand/paging | `SearchViewModel.kt:124` | No | See all drawn, destination not | `:feature:search` |
| **Quick search** | | | | |
| Whole panel (`pushSearch`) | `A/ui/quicksearch/QuickSearchFragment.kt:59-71`, callers `HomeViewModel.kt:474`, `ResultFragmentTv.kt:944` | Seam only | Drawn 8d/8e | `:feature:search` |
| **Library** | | | | |
| Sync provider switch | `A/ui/library/LibraryViewModel.kt:68` | No | Drawn 5e | `:feature:library`, new provider seam |
| Sort | `LibraryViewModel.kt:74` | No | Drawn 5d | `:feature:library` |
| Text filter | `A/ui/library/LibraryFragment.kt:150-164` | No | Not drawn | `:feature:library` |
| Random | `LibraryFragment.kt:109-121` | No | Not drawn | `:feature:library` |
| Opener type | `LibraryFragment.kt:63` | No | Not drawn | `:feature:library` |
| **Result** | | | | |
| Bookmark, favourite | `ResultFragmentTv.kt:294-295` | No | Drawn 7a | `:feature:detail` |
| Subscribe | `ResultFragmentTv.kt:633`, emulator only | No | Deliberately not drawn (7a note) | owner decision |
| Trailer | `ResultFragmentTv.kt:293`, `A/ui/result/ResultTrailerPlayer.kt` | No | Button drawn; player not | `:feature:detail` |
| Recommendations + source filter | `ResultFragmentTv.kt:156-172` | No | Drawn 7a-ii | `:feature:detail` |
| Cast | `ResultViewModel2.kt:169` | No | Drawn; full-cast panel not | `:feature:detail` |
| Dub + range selectors | `ResultFragmentTv.kt:311-313,371-376` | No | Range drawn 6a, dub not found on a board | `:feature:detail` |
| Episode action menu: reload, mark watched, cast, batch download | `A/ui/result/EpisodeAdapter.kt:45-66` | No | **Not drawn** | `:feature:detail` |
| Tracking: score, status, episodes | `A/ui/result/SyncViewModel.kt:129-182` | No | **Not drawn on detail** | `:feature:detail` |
| Metadata backfill | `ResultViewModel2.kt:1706` | No | PARTIAL state specified on 7a | `:feature:detail` |
| **Player** | | | | |
| ExoPlayer engine | `A/ui/player/CS3IPlayer.kt:133`, `:1058` | Contracts only | n/a | see below |
| Source priority, quality profiles | `A/ui/player/source_priority/*`, `GeneratorPlayer.kt:1313` | No | **Not drawn** (H:246-248) | new `:feature:player` |
| Online subtitle search + file load | `GeneratorPlayer.kt:668,884` | Desktop picker only | Row drawn, destination not | `:feature:player` |
| Subtitle offset | `A/ui/player/SubtitleOffsetItemAdapter.kt`, `CS3IPlayer.kt:537` | Parser logic in `:media:subtitle` | Not drawn | `:feature:player` |
| Skip intro | `GeneratorPlayer.kt:2063-2070` | No | Drawn 7d | `:feature:player` |
| Speed | `FullScreenPlayer.kt:165` | No | Drawn 7d, panel not | `:feature:player` |
| Next-episode autoplay | `GeneratorPlayer.kt:1678` | No | Up-next readout drawn | `:feature:player` |
| PiP | `A/ui/player/PlayerPipHelper.kt` | `PipState` contract, unused | Not drawn | `:feature:player` |
| Gestures | `A/ui/player/PlayerGestureHelper.kt` | No | N/A on TV | touch only |
| Torrent | `A/ui/player/Torrent.kt` | No | Not drawn | `:media:torrent` (planned) |
| Chromecast | `A/utils/CastHelper.kt`, `A/ui/ControllerActivity.kt` | No | Not drawn | `:media:cast` (planned) |
| External players | `ResultViewModel2.kt:1658-1661` | No | Not drawn | `:feature:detail` |
| Offline player | `A/ui/player/DownloadedPlayerActivity.kt` | No | Not drawn | `:feature:player` |
| Live helper | `A/ui/player/live/` | No | Not drawn | `:feature:player` |
| **Download** | | | | |
| Lists | `A/ui/download/DownloadViewModel.kt:209`, `DownloadChildFragment.kt` | No | Drawn 6c/6d | **new `:feature:downloads`** |
| Multi-delete | `DownloadViewModel.kt:433` | No | Entry chips drawn | same |
| Queue screen + reorder | `A/ui/download/queue/*`, `DownloadQueueAdapter.kt:267` | No | **Queue board not drawn** (only the button on 6c/6e) | same |
| Open local file, play from URL | `DownloadFragment.kt:234-238` | No | Button drawn, dialog not | same |
| **Settings** | | | | |
| Seven Compose screens | `A/ui/settings/Settings*Screen.kt` | Compose, but in `:app` on Mihon widgets and Material | **Not drawn** (H:246) | new `:feature:settings` (P:267) |
| Backup, restore, logcat | `A/ui/settings/SettingsUpdates.kt:23-33`, `A/ui/settings/logcat/` | No | Not drawn | same |
| Provider test | `A/ui/settings/testing/`, `NAV:574` | No | Not drawn | same or stays |
| Self-update | `MA:1983`, P:O9 | No | Not drawn | `:feature:settings` capability |
| Extensions, plugins lists | `A/ui/settings/extensions/*` | Contracts only | Drawn 8b/8c | `:feature:extensions`, add `tv/` |
| Plugin details | `A/ui/settings/extensions/PluginDetailsFragment.kt` | No | **Not drawn** | `:feature:extensions` |
| Add-repo dialog | note cites `ExtensionsFragment.kt:255-329`† | No | "Enter a repository URL" drawn as a button only | `:feature:extensions` |
| **Account** | | | | |
| Select profile | `A/ui/account/AccountSelectActivity.kt` | No | Drawn 8d | **new `:feature:account`** |
| Edit dialog, PIN | `A/ui/account/AccountHelper.kt:46,221` | No | "Manage profiles" drawn, editor not | same |
| Biometric | `MA:145-149` | No | Not drawn | same |
| Kitsu credential form | `A/ui/settings/SettingsAccount.kt:276` | No | **"Owed screen"** per 8d note | same |
| OAuth login on TV | `SettingsAccount.kt:393` | No | Drawn as unavailable | same |
| **Setup** | | | | |
| Language, provider language, media, layout | `A/ui/setup/*` | No | **Only the extensions step is drawn** (8a) | **new `:feature:setup`** |
| **Subtitles** | | | | |
| Style editor | `A/ui/subtitles/SubtitlesFragment.kt:66` | `CaptionStyle` contract only | "Subtitle settings" row drawn, editor not | `:media:subtitle` + `:feature:player` |
| Chromecast subtitles | `A/ui/subtitles/ChromecastSubtitlesFragment.kt` | No | Not drawn | `:feature:player` |
| **Other** | | | | |
| In-app WebView | `NAV:239` | No | Not drawn | owner decision |

### Android player host

- **`:media:video`, `:media:audio`, `:media:download`, `:media:torrent` and `:media:cast` do not exist.** `WT/settings.gradle.kts` includes only `:media:model` and `:media:subtitle`.
- **`cloudstream.kmp.android-only` exists but no module applies it.** P:631-632 orders `:media:video` last.
- **ExoPlayer lives in `:app`.** Media3 and nextlib are `:app` dependencies (`WT/app/build.gradle.kts:250,254`). `CS3IPlayer` implements `IPlayer`. Both are published (`API:26697`, `API:27412`), as are `GeneratorPlayer` (`API:27240`) and `PlayerView` (`API:27846`), so moving them is an ABI event.
- **`:media:model` contracts are not wired on Android.**
  - `StreamRequestAdapter` says "Nothing calls this yet" (`A/ui/player/StreamRequestAdapter.kt:28`).
  - A file grep finds `PlayerCommand`, `NowPlaying` and similar named only in `GeneratorPlayer.kt` among `:app` files; I did not read those uses.
- **Desktop player cannot be reused as written.** `PlayerScreen.kt:109-110` is bound to `VlcPlayer`, in jvmMain.
- **Recommendation (not established by running anything):** first Android host stays in `:app`. A new Compose HUD in commonMain drives a thin `:app` adapter over `IPlayer`/`CS3IPlayer`, with `PlayerView` in an `AndroidView`. That avoids moving published classes and leaves `:media:video` as a later refactor. Whether `CS3IPlayer` attaches cleanly outside `AbstractPlayerFragment` (`A/ui/player/AbstractPlayerFragment.kt:42-56,120`) needs a read in that vertical's design step.

## 3. Design-system gaps

**Exists and is TV-ready:**
- `tvFocusRing` and `tvFocusLift` (`DS/focus/TvFocusRing.kt:83,117,145`).
- `SidePanel` and `SidePanelHost` (`DS/panel/SidePanel.kt:122`, `DS/panel/SidePanelHost.kt:82`), with focus trap, restore, BACK and scrim. No callers yet.
- `PrimaryTextButton`, `GhostTextButton`, `IconCell`, `Chip`, `ChipRow`, `PosterCard`, `BackdropCard`, `SeeAllCard`, `ListRow`, `RailHeader`, `Badge`, `StatusDot`, `ProgressRule`, `SelectionMarker`, `Hairline`.
- `UiState`, `UiStateHost`, `StateView`, `FailureBanner`, `StateActionButton`, the skeletons.
- `CloudStreamIcons`.

> Built since this audit, by `DS-08`: the status pill, segmented control, below-art episode and continue cards, avatar,
> storage meter, download progress row and the missing icons. `DEC-17` settled the failure colour: hollow neutral.
> Built since this audit, by `DS-09`: `NavRail`. Android TV shows it since `KMP-33`, and the XML rail is its rollback.

**Missing from `Components.dc.html`:**

| Component | Evidence | Needed by |
|---|---|---|
| **NavRail** (collapsed + expanded overlay) | Not in `DS/`; only tokens at `DIM:147-153` | 3a, 3b, all rail boards |
| **Status pill** (PROBING, READY, FAILED, PLAYING) | Only `StatusDot`, a square | 7c, 8b, 5e |
| **Segmented control** | Not in `DS/` | Merged / By source |
| **Episode card and continue card with text below the art** | `MediaCard` title is an overlay inside the card (`DS/card/PosterCard.kt` KDoc). 3c draws art, rule, title and subtitle under it | 3c, Components §02 |
| **ListRow trailing focus stop** | `ListRow` has one `selectable` (`DS/rows/ListRow.kt:78`). KMP-12 avoided nesting a stop (`K:1386`). Design requires it | 6d, 7c, 8b, 8c, 4c |
| **Disabled-but-focusable button** | `DS/target/TextButton.kt:55,99` not focusable. Components: "stays focusable" | Loading screens |
| **Disabled row with a reason, still a stop** | `ListRow` passes `enabled` to `selectable` | 5d, 8c row 5 |
| **Panel slide animation** | No `Animated` or `slide` in `DS/panel/*`. Components: 240 ms | every panel |

**Used on boards but not in the kit, also absent:**
- Avatar, 80dp (7a note says it is a new component).
- Text field: `QueryField` is private (`FS/tv/SearchChromeRow.kt:111`).
- Storage meter, download progress row.
- Seek and transport controls (desktop-only).
- Hero block.
- Region banner: `FailureBanner` is sized for a chip row, 6e and 7a need other placements.
- Icons with no `CloudStreamIcons` entry: Library, Live TV, Movies, TV, Cast, Audio, Speed, Lock, Star, Profile, Offline (`DS/icon/CloudStreamIcons.kt:55-93`).

**Conflicts inside the system (need a ruling, not code):**
- `SidePanel` KDoc requires Apply and Cancel (`DS/panel/SidePanel.kt:83-91`). 7e says "no Apply and no Cancel". 4d says "changes apply on Done".
- `StatusDot.Failed` is drawn in `live` (`DS/primitive/StatusDot.kt:27`). `Components` says the pill is achromatic and failed is hollow. The Episodes note instead sanctions one failure marker in `live`. The 7c note says whether one marker means one role or one instance is "the only open question on this cluster".
- `ProgressRule` is 2dp, `MediaCardDefaults.ProgressRuleHeight` is 4dp (`DS/primitive/ProgressRule.kt`, `DS/card/MediaCardDefaults.kt:36`). This is H §8.4, still open.
- `ChipRowSpacing` 24dp vs CSS 12dp (`K:1332-1344`, `HO:294`).

**Owed on the boards, with no ruling needed:**
- `.chipn` on `TV Detail Player` is still 20/24. `TV Onboarding Extensions` says it must become 24/32, because a 24 box centred in the 48-tall chip lands at +12, off the 8px step. Its `must be propagated` note stays until this is done.
- **`TV Search Suggestions` 8b with the keyboard up.** The board assumes no on-screen keyboard. On the device the open keyboard covers 8b's headline and body while the user types, and only the chip row's `Search all N sources` shows (`KMP-47`, measured). For the owner's next design pass: where 8b's message goes while the keyboard is up. No change in code until then (app-10, 2026-11-06).

## 4. Proposed queue

### Constraints that shape the queue

- **Repoint mechanism.** XML cannot vary by layout because `navigation_search` and friends serve phone too.
  - #91 swaps the class at runtime in `MainActivity` with `setClassName` and leaves `NAV` unchanged.
  - ~~**`navigation_results_tv` (`NAV:514`) is the one node that can be repointed in XML**, because `getResultsId` already branches on `TV or EMULATOR` (`A/utils/AppContextUtils.kt:704-708`). `EMULATOR` layouts change too.~~ **Wrong, corrected by `KMP-38`.** The branch is on `TV or EMULATOR`, so an XML repoint of that node moves the EMULATOR layout as well — and `EMULATOR` must not change. `Globals.layoutId` holds one value (`A/ui/settings/Globals.kt:24-32`), so `isLayout(TV)` excludes `EMULATOR` and the runtime `setClassName` form is the only mechanism that repoints TV alone. **There is no node that can be repointed in XML.** Q21 and Q22 inherit this.
- **Rail geometry.** `SearchScreenTv` assumes a 76dp rail (`FS/tv/SearchScreenTv.kt:156`). The host margin is 62dp (`MA:560-565`). So content starts at about 86dp rather than the artboard's 100dp, a 14dp miss. Inferred from code, not measured.
- **Hot files that collide across parallel PRs:**
  - `WT/app/build.gradle.kts:310-314`.
  - The one line per vertical at `MainActivity` near `:1668` (#91's site).
  - `API`: any new `:app` class adds lines (#91 +67, `K:992`). It is generated by `:app:dumpExtensionApi`; never hand-edit.
  - All strings: 62 authored files, 62 generated files, `WT/core/strings/oracle-baseline/`, and `API` (+N `R$string` fields, `K:2538`).
- **Merged-jar scope.** `:app`, `:library`, `:core:common`, `:core:network`, `:core:data` and `:core:model` are merged (P:227-242). `:feature:*`, `:core:designsystem` and `:media:*` are not. Published: `SyncAPI$Page` (`API:17059`), `RepositoryData` (`API:31320`), `PluginData` (`API:15880`), `DownloadStatus` (`API:35446`). A new constructor param on any of them breaks ABI (P:142), so prefer sidecar types.
- **Gates.** Four local gates per PR, serially (`HO:224-248`). Parallel means parallel edits, with builds serialized.
- **UI tests.**
  - The catalog has no `compose-ui-test`, UIAutomator, Robolectric or Roborazzi (`WT/gradle/libs.versions.toml`).
  - Espresso and `ext-junit` exist (`:100-101`).
  - Desktop tests drive screens offscreen through `ImageComposeScene` (`HO:46-47`), so TV screens in commonMain could get JVM layout tests with no new dependency, if placed in `:desktopApp`'s test set or a module with `compose.desktop.currentOs` (as `WT/core/designsystem/build.gradle.kts` does).
  - A `Television_4K` AVD exists in `~/.android/avd`. Whether it boots or satisfies #91's "real TV" condition is not established.

### The queue

Columns: Strings = yes/no, estimated from a spot check of `values/strings.xml` keys. Jar = touches merged code or `API`.

| # | Vertical | Artboards | Modules touched | `:app` repoint (node) | Jar | Strings | Blockers, decisions | Depends on |
|---|---|---|---|---|---|---|---|---|
| **Q0** | Owner decisions batch (no code) | all | none | no | no | no | See the decisions list below | none |
| **Q1** | Test infrastructure | none | catalog, build-logic | no | no | no | Dependency approval (no `DEC` id yet); `DEC-10` Roborazzi | Q0 |
| **Q2** ✅ done, `KMP-35` | `ConnectivityFlow` seam | offline on every board | new interface in a non-merged module; Android impl (`A/utils/AppContextUtils.kt:622` is the one-shot today) | no | adds `API` lines if impl is in `:app` | no | Module choice: `:core:common` is frozen-API (P:188-191) | none |
| **Q3** | DS batch A: panel slide, disabled-focusable, trailing focus stop, disabled-row reason, public text field | prereq for 4d, 5d, 6d, 7c, 8b, 8c | `:core:designsystem`, `:feature:search` (text field) | no | no | no | Panel commit ruling, focusable-disabled ruling | Q0 |
| **Q4** | Land search (#91) | 4a, 4b, 4c, 8c | `:app`, `:feature:search` (`posterHeaders`) | Yes, `navigation_search` at runtime | yes (+67) | no per #91 | Needs a device. `DEC-13` loss stated in PR. Merge state against master not checked | Q1 for tests |
| **Q5** ✅ done, `KMP-33` | Rail + shell alignment | 3a, 3b | `activity_main_tv.xml`, new TV dimen override, MA | no (shell) | no new class likely | Yes if new labels | **Owner decision: rail items.** Compose rail not possible before Phase N without an activity change | Q0 |
| **Q6** ✅ done, `KMP-42` | Search 4d + type chips | 4d, full 8c | `:feature:search`, `A4/search/` | no | yes | small | `SearchSource` write seam; Q3 | Q3, Q4 |
| **Q7** ✅ done, `KMP-47` | Search suggestions | 8a, 8b | `:feature:search`, `A4/search/` | no | yes | yes | Wrap `SearchSuggestionApi` rather than move it (P:360). TMDB key already ships in jar | Q4 |
| **Q8** ✅ done, `KMP-48` | Quick Search panel | 8d, 8e | `:feature:search`, `A4/search/` | Yes, `navigation_quick_search` (`NAV:441`) at runtime, or swap callers | yes | small | Q3 | Q4 |
| **Q9** ✅ done, `KMP-37` | Home rails | 3d | `:feature:home/tv/`, `A4/home/` (model on `DK/home/DesktopHomeSource.kt:33`), `app/build.gradle.kts` | Yes, `navigation_home` (start destination, `NAV:6,293`) | yes | yes for a few | `HomeSourceMemory` impl | Q1, Q5 geometry |
| **Q10** ✅ done, `KMP-41` | Home hero + continue, and watch progress on Home's cards | 3c | `:feature:home`, `A4/home/`, DS below-art card, `BackdropCard` | no | yes | Few | Resume seam; per-card source/quality missing → owner: reduce or add | Q9 |
| **Q11** (**done**, `KMP-34`) | Library grid, local provider | 5a, 5c | `:feature:library/tv/`, `A4/library/` | Yes, `navigation_library` (`NAV:173`) | yes | Some | **Model decision:** `LibraryStore` has no provider; `:app` is `SyncAPI`. New seam needed | Q1 |
| **Q12** (5e and the provider button done, `KMP-49`; 5d and the sort button done, `KMP-50`; 5b and offline done, `KMP-51`) | Library sort + provider panels + unreachable | 5b, 5d, 5e | same | no | sidecar | Some | `lastSynced`, page counts (published `Page`), typed failure | Q3, Q11 |
| **Q13** ✅ done, `KMP-36` | Download facts, sidecar | enables 6b–6e | `:app` downloader (`A4/download/DownloadFacts.kt`, a flow rather than an event; downloader behaviour unchanged), `:media:model` `DownloadProgress` | no | yes, additive | no | **Corrections, measured:** `DownloadProgress` already existed, with rate, ETA and state. "Recorded as complete" past 50 MB is narrower than it reads, and the same truncation also happens under 50 MB; a full disk hangs instead of failing. All three fixed by `BUG-35`, see `KMP-36` | none |
| **Q14** ✅ done, `KMP-40` | Downloads list | 6c, 6e | **new `:feature:downloads`**, `A4/download/` | Yes, `navigation_downloads` (`NAV:327`) | yes | yes | P:O8. Queue and Play-from-URL undrawn | Q2, Q13 |
| **Q15** ✅ done, `KMP-43` | Downloads one series | 6d, 6b | `:feature:downloads` | Yes, `navigation_download_child` (`NAV:452`) | yes | Some | Q3 trailing stop | Q14 |
| **Q16** ✅ done, `KMP-73` | Account select | 8d | **new `:feature:account`**, `A4/account/` | Activity, not a fragment (`NAV:694`) | yes | yes | Delete-profile bug is a precondition for Manage profiles. Kitsu panel undrawn | Q3 |
| **Q17** | Repository index source | enables 8a | model + `:app` | no | possibly | no | **Owner decision: what is seeded.** Also a `DEC-03`-type distribution question, my inference | Q0 |
| **Q18** | First run | 8a, 8a-ii | **new `:feature:setup`**, `:feature:extensions` | Yes, setup nodes, routing at `A/ui/setup/SetupFragmentLanguage.kt:76-87` | yes | yes | Other setup steps undrawn: draw or keep old fragments | Q2, Q17 |
| **Q19** | Extensions installed + browse | 8b, 8c | `:feature:extensions/tv/`, adapter | Yes, `navigation_settings_extensions`, `navigation_settings_plugins` (`NAV:199,230`) | yes | yes | `install` refuses by `F1`. Progress needs a new path. Plugin settings on Android keep `openSettings` (P:O5) | Q3, Q17 |
| **Q20** ✅ done, `KMP-38` | Detail page | 7a, 7b, 7a-ii | `:feature:detail` (extend `TitleDetail`), `A4/detail/` | **Not in XML — runtime `setClassName`.** `getResultsId` branches on `TV or EMULATOR` (`A/utils/AppContextUtils.kt:703-709`), so repointing the node in XML moves EMULATOR too | yes, +157 additive | none added | Season integer and per-season count exist after all (`SeasonData`, `Episode.season`); cast is per-person; Full cast and See all dropped (`CardSize`'s constructor is `internal`); Subscribe still omitted. **Playback records no `setLastWatched`** — see `KMP-38` | Q1 |
| **Q21** | Episode panel | 6a | `:feature:detail`, Q3 | no | yes | Few | `_episodes` failure, batched download state | Q13, Q20 |
| **Q22** | Link loading, reduced | 7c without per-source failure | `:feature:detail`, `A4/detail/` | no | yes | Some | Built on existing `DetailSource.links` flow; drawn rows reduced, tell owner | Q20 |
| **Q23** | Link loading, full | 7c | `:library` (hook), `:core:data`, `:app` | no | **yes, published** | Some | `SourceId`, `ResolutionFailure`, per-source timeout: additive hook on `MainAPI`, needs owner | Q22 |
| **Q24** ✅ done, `KMP-64` | Player HUD | 7d | **new `:feature:player`** (commonMain HUD), `:app` host | Yes, `navigation_player` (`NAV:564`), runtime | yes | Some | `getBufferedPosition` addition to published `IPlayer` needs a default. Auto-hide and pop behaviours | Q1, Q2 |
| **Q25** ✅ done, `KMP-66` | Playback panels | 7e | `:feature:player`, `:app` | no | yes | Some | Style editor, online subs, quality profile undrawn (H:247) | Q3, Q24 |
| **Q26** | Undrawn boards | queue, profile editor, settings re-skin, subtitle styles, plugin details | designs first, per H:93-100 | later | later | later | `DS-01`, `DS-05` for settings | owner |

Order follows H:121-123 (search, home, library, downloads, account + setup, extensions, result, player). The foundation items Q1–Q3 go first as "cheapest blockers".

### Decisions needed

**Existing:**
- `DEC-01`: CI off, so local gates only.
- `DEC-02`: fonts. Check before distribution.
- `DEC-03`: distribution.
- `DEC-09`: `BaseComposeFragment`. Not triggered while glue stays in `:app`.
- `DEC-10`: Roborazzi.
- `DEC-11`: answered; TV screens not yet using it.
- `DEC-13`: accent lost per adopted screen; each PR must state it.
- `DS-01`, `DS-05`: settings.
- P:O5, P:O7, P:O8, P:O9.
- H §8.1 (10sp caption), H §8.2 (rail spelling).

`DEC-14` and `DEC-15` are desktop-scoped.

**New, no id yet:**
1. Android UI test dependencies.
2. Rail items and geometry.
3. Recommended repository index source.
4. Panel commit rule.
5. `live` for failure markers.
6. Chip spacing.
7. Subscribe on TV.
8. What "real TV" means for #91, and whether `Television_4K` counts.
9. Whether new strings can batch.

### Parallel execution

**Fan out edits only; serialize builds and merges.**

| Group | Run together | Why safe | Collision to manage |
|---|---|---|---|
| A | Q2, Q3, Q13 | Different modules | None significant |
| B, after Q1 and Q4 | Q9 (home), Q11 (library), Q14 (downloads), Q16 (account), Q20 (detail) | Each has its own feature dir and `A4/<x>/` | `app/build.gradle.kts`, `MainActivity` line, `API`, strings |
| C | Q6, Q7, Q8 | **Do not parallelize**: all edit `FS/tv/SearchChromeRow.kt`, `FS/SearchMvi.kt`, `FS/SearchContracts.kt` | Serial |
| D | Q24 and Q22 | `:feature:player` vs `:feature:detail` | `:app` hot files |

**Two cheap PRs eliminate most of the collisions:**
1. A scaffold PR with all `:app` module edges and one `A4/tv/TvDestinations.kt` holding the runtime swaps, merged before group B. Each vertical then edits only its one line there.
2. A strings batch PR per wave, so feature PRs add none. `API` still needs a re-dump on rebase.

**Strings caveat.** A new key is expensive to review: 62 authored files, machine translation, oracle-baseline, and `API`. `I18N-08` also forbids injected copy, so words must come from `:core:strings` directly. Spot-check: many words exist (`continue_watching:146`, `resume:579`, `trailer:778`, `synopsis:754`, `audio_tracks:74`, `download_queue:200`, `search_state_no_results_action:603`, `player_source_*:459-462`). Not found: "Free up space", "offline" words, "storage" meter wording, "Play from URL" as named (the one key grep was exact-match). Each vertical must verify per board.

## 5. Contradictions

| # | Claim | Reality | Evidence |
|---|---|---|---|
| C1 | "28 artboards across 6 files" | 34 across 7. H contradicts itself | H:21,98 vs H:219, `shotn` counts |
| C2 | "Screen composable → `feature/x/androidMain`" | Screens are commonMain, no `:feature:*` has androidMain | H:116 vs P:275, `ls WT/feature/*/src` |
| C3 | Repoint one node in `mobile_navigation.xml` | #91 swaps at runtime in `MainActivity`; `NAV` untouched. Only results has a TV node | H:118, P:643, HO:379 vs #91, `NAV:311,514` |
| C4 | Glue in androidMain in a `cloudstream4` package, not merged | #91's glue sits in `:app` and enters `API` (+67). It imports `MainActivity` and `BaseComposeFragment`, so a feature module cannot host it as written | P:278,642, P:237 vs K:992, #91 |
| C5 | `:feature:*` use `kmp.feature` | No such plugin; they use `cloudstream.kmp.compose` | P:237 vs `WT/feature/search/build.gradle.kts`, `WT/build-logic/convention/src/main/kotlin` |
| C6 | `:core:strings`, `:core:http`, `:core:ui`, `:core:model`, `:core:data`, `:core:storage`, `:media:model`, `:media:subtitle` "planned" | All exist | P:231-236 vs `WT/settings.gradle.kts` |
| C7 | "`:feature:search`: no screens yet, nothing in `:app` is wired" | Screens exist; `:app` implements seams | `WT/feature/search/build.gradle.kts:4` vs `FS/tv/`, `A4/search/` |
| C8 | #91 "keeps" `ApiRepositorySearchSource`, `DataStoreSearchHistory`; `SearchSource` impl unchecked | Both on master, #91 only modifies them | K:1074-1075,1418 vs `git log` `fa1ffc1de` |
| C9 | `tv/SearchTvCopy.kt` holds every word | File does not exist; removed by `I18N-08` | K:1093,1419 vs `FS/tv/`, `WT/docs/tasks/i18n.md:522` |
| C10 | "Icon pack undecided", "`DEC-11` unanswered" | Decided 2026-11-02 | `FD/tv/DetailScreenTv.kt:65`, `FS/tv/SearchChromeRow.kt:54`, H §8.3 vs `WT/docs/tasks/decisions.md:302` |
| C11 | "Links arrive all at once" | Contract is a growing `Flow` | `FD/tv/DetailScreenTv.kt:71` vs `FD/DetailContracts.kt:68` |
| C12 | Rail spec | TV Home 152/376 with Search, Home, Live TV, Movies, TV Shows, Library. Components 96/320 with a list including Downloads. Old 62dp with Home, Search, Library, Downloads, Settings. 7b's Offline relies on a rail Downloads cell. No drawn list carries Settings | `TV Home:65,78,475`, `Components:190,571-577`, `WT/app/src/main/res/menu/bottom_nav_menu.xml:4-22` |
| C13 | Focus ring "4dp stroke, 4dp offset, 8dp bleed" | Tokens are 2, 2, 4 | H:174, `DS/focus/TvFocusRing.kt:26-28` vs `DIM:45,48,58`; K:1355 records only the rules file |
| C14 | "Chip 40 visual in a 96 cell" | Token and TV boards use 48 px, 24dp | `Components` §01 vs `DIM:99`, `TV Search` cause 4 |
| C15 | "Master at `26acd8f8`", "source and audio selectors not done", "source not remembered" | Master `c0548f7ff`; KMP-29, KMP-30 and KMP-32 did them | HO:25,71-72 vs K:3000,3040,3076 |
| C16 | "Detail: the TV detail page" works on desktop | Desktop calls `DetailScreenDesktop`; `DetailScreenTv` has no caller | HO:38 vs `DK/Main.kt:467` |
| C17 | "Do not start screens first"; Phase A order through `:core:notification`, `:media:video` | Superseded by owner's desktop-first (`HO:30`). Those modules don't exist while four features do | H:78-82 vs `WT/settings.gradle.kts` |
| C18 | "Desktop — nothing" | Desktop screens built, with an owner direction doc | H:250 vs H:96-97, `WT/docs/plans/desktop-design.md` |
| C19 | Each new string must go in 62 dirs (brief) | Tree is lean, nothing I found enforces it. `search_state_retry` is in 1 dir, `cancel` in 53. It is a practice from owner's "should be translated" | HO:155, `WT/docs/tasks/i18n.md:553`, grep counts |
| C20 | 6c: "engine never reads free space" reads as a blocker | True of `utils/downloader/`, but the ViewModel does read it, so the meter's data exists | `TV Episodes Downloads` 6c vs `A/ui/download/DownloadViewModel.kt:419` |
| C21 | Design note line numbers | Drifted: `failedStatus` :558→`:565`; `loadLinks` :1265→`:1231`; `MainAPI.loadLinks` :685→`:692`; `resultSubscribe` :629→`:633`; `ActorData` :1524→`:1545`. The named `hasDownloadSupport` is a positional `false` at `ResultFragmentTv.kt:433` | Above |

## Could not establish

- Anything at runtime: focus traversal, whether screens render on Android, 14dp rail miss as measured, `Television_4K` boot.
- Whether #91 merges cleanly (I avoided `git merge-tree` since it writes objects).
- `SearchViewModel` and `HomeViewModel` import and `R.string` counts at H:121-123 and P:168-169 were not re-measured.
- Facts marked † are the design note's. String needs per vertical are estimates. `ListSorting` entry count was not counted.
- `K` was read in sections, not in full (KMP-12, parts of others). `WT/docs/tasks/defects.md` and `WT/docs/tasks/infrastructure.md` were outlined only.
- GitHub MCP failed to connect; I used `gh` for #91 instead.
