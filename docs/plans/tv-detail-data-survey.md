# TV detail page — what real data exists

A read-only survey of `:app`'s result-loading path, done to establish exactly what an Android
`DetailSource` can bind a new Compose TV detail page to. No code was written and no project file
other than this one was changed. No Gradle, emulator or adb was run; every claim below is read off
the source or off the committed ABI dump.

Paths are relative to the worktree root `/Volumes/Projects/CloudStream/wt-tv-detail`. Line numbers
are as of the commit this worktree is on.

The two seam files read first:

- `feature/detail/src/commonMain/kotlin/com/lagradost/cloudstream4/feature/detail/DetailContracts.kt:9-14`
  (`DetailEpisode`), `:23-36` (`TitleDetail`), `:46-54` (`StreamLink`), `:60-69` (`DetailSource`).
- `feature/detail/src/commonMain/kotlin/com/lagradost/cloudstream4/feature/detail/DetailMvi.kt:11-15`
  (`DetailState`), `:18-27` (`DetailLoad`), `:29-37` (`DetailAction`), `:39-41` (`DetailEffect`).

The model for an `:app`-side source is
`app/src/main/java/com/lagradost/cloudstream4/search/ApiRepositorySearchSource.kt` — `internal class`
but still in the ABI dump (`:44-51`), application `Context` in the constructor (`:53-61`), per-round
`Configuration`-derived context for in-app-language display strings (`:207-212`), length-prefixed
opaque id (`:386-387`) and its inverse (`:390-402`).

---

## 1. The load path

### What loads a title

`APIRepository` is **not** in `:app` any more — it is `:core:data`:
`core/data/src/jvmCommonMain/kotlin/com/lagradost/cloudstream3/ui/APIRepository.kt:26`.

`APIRepository.load(url)` (`core/.../APIRepository.kt:95-131`) is the whole of "give me this title's
details":

- wrapped in `safeApiCall` (`:96`), so it **returns** `Resource<LoadResponse>` and does not throw
  (`library/src/commonMain/kotlin/com/lagradost/cloudstream3/mvvm/ArchComponentExt.kt:207-218`
  catches `Throwable` and converts it);
- `withTimeout(getTimeout(api.loadTimeoutMs))` (`:97`), clamped to 5 s … 480 s with a 120 s default
  (`:30-32`, `:61-63`);
- rejects junk data before calling the provider — `isInvalidData` is `""`, `"[]"`, `"about:blank"`
  (`:47-49`, used at `:98`);
- `api.fixUrl(url)` (`:99`), then a **10-minute, 20-entry rolling cache** keyed on
  `Pair(api.name, fixedUrl)` (`:51-59`, `:102-114`, `:118-127`);
- strips blank tags on the way in (`:117`);
- `api.load(fixedUrl)` returning `null` becomes `ErrorLoadingException` inside `safeApiCall`, i.e. a
  `Resource.Failure` (`:115`, `:128`).

The cache is a companion property shared by every instance (`:57`) and is cleared by
`APIRepository.onPluginsLoaded(forceReload)` (`:80-84`), which `:app` subscribes **once** in
`CloudStreamApp.onCreate` — not per instance.

`Resource` is three cases: `Success(value)`, `Failure(isNetworkError, errorString)`, `Loading(url?)`
(`library/.../ArchComponentExt.kt:48-55`).

The provider entry point underneath is `MainAPI.load(url): LoadResponse?`
(`library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt:665-667`) — it throws
`NotImplementedError` by default, which is why the `safeApiCall` wrapper matters.

### Is it callable headlessly?

**`APIRepository.load` is: yes.** It needs no `Context`, no `Activity`, no `Fragment`, no
`ViewModel`. It is a plain `suspend fun` on a class whose only constructor argument is a `MainAPI`
(`core/.../APIRepository.kt:26`). `ApiRepositorySearchSource` already builds them per call
(`app/.../search/ApiRepositorySearchSource.kt:132`) and documents why
(`:63-70`). This is the smallest callable entry point for "give me this title's details":

```
APIHolder.getApiFromNameNull(apiName)  ->  APIRepository(api).load(url)  ->  Resource<LoadResponse>
```

**`ResultViewModel2` is not reusable headlessly.** It is an `androidx.lifecycle.ViewModel`
(`app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt:496`) and every output
is `LiveData` (`:533`, `:537`, `:541`, `:545`, `:549`, `:553`-`:606`, `:771`, `:774`), so reading it
means `observe` on a `LifecycleOwner`. Beyond that it is wired to Android in five ways that a
headless source cannot satisfy:

1. `load(activity: Activity?, …)` takes an `Activity` as its first parameter (`:2654-2662`) and uses
   it for autostart (`:2743`, `:2552-2554`).
2. It reads the process-global `CommonActivity.activity` (imported at `:23`;
   `app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:76`) for chromecast checks
   (`:1402`, `:1459`), the popup title (`:1443`) and the player navigation (`:1602-1607`).
3. Navigation to playback is a `NavController` action on that Activity:
   `activity?.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(generator, index, list))`
   (`:1602-1607`).
4. Every string it emits is a pre-rendered `UiText` needing a `Context` to resolve — `ResultData` is
   24 `UiText` fields out of 29 (`:166-196`).
5. `updateWatchStatus` and `toggleFavoriteStatus` take a `Context?` and are `@MainThread` through
   `checkAndWarnDuplicates` (`:776-779`, `:975-978`, `:1040-1046`).

Its `load` also does four things besides loading, which a new source must decide whether to keep:
`SyncRedirector.redirect` (`:2684-2689`), `applyMeta` (`:2710-2712`), writing the download header
cache (`:2720-2732`) and `loadTrailers` (`:2733-2734`).

`loadSmall(searchResponse)` (`:2615-2652`) is the "show the card's data while the real load runs"
path; it builds a `LoadResponseFromSearch` and calls `postSuccessful(updateEpisodes = false)`.

### Recommendation

An `AndroidDetailSource` should call `APIRepository.load` and map `LoadResponse` itself, not drive
`ResultViewModel2`. Everything in §2–§7 below is reachable from `LoadResponse` plus
`DataStoreHelper`, both of which are headless.

---

## 2. `ResultData` / `LoadResponse` — field by field

`LoadResponse` is the provider-facing interface
(`library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt:1814-1851`), with KDoc for
every property at `:1788-1813`. `ResultData` is `:app`'s already-rendered view model of it
(`app/.../ui/result/ResultViewModel2.kt:166-196`), produced by
`LoadResponse.toResultData(repo)` (`:272-401`).

| Board fact | On `LoadResponse` | On `ResultData` | Verdict |
|---|---|---|---|
| Title | `name: String` (`MainAPI.kt:1815`) | `title: String` (`:174`) + `titleText: UiText` (`:189`, set at `:335`) | exists |
| Provider name | `apiName: String` (`MainAPI.kt:1817`) | `apiName: UiText` (`:181`, set at `:373`) | exists |
| Type | `type: TvType` (`MainAPI.kt:1818`) | `typeText: UiText` only — the 18-arm `when` at `:350-371` resolves it to words; the enum itself is **not** on `ResultData` | exists, shaped differently |
| Year | `year: Int?` (`MainAPI.kt:1820`) | `yearText: UiText?` = `txt(year?.toString())` (`:191`, `:372`) | exists, stringified |
| Plot | `plot: String?` (`MainAPI.kt:1821`) | `plotText: UiText` — **never null**; a blank plot becomes `torrent_no_plot` / `normal_no_plot` (`:180`, `:343-346`) | exists, shaped differently |
| Poster | `posterUrl: String?` (`MainAPI.kt:1819`) | `posterImage = posterUrl ?: backgroundPosterUrl` (`:177`, `:332`) + `posterHeaders` (`:195`, `:333`) | exists |
| Backdrop | `backgroundPosterUrl: String?` (`MainAPI.kt:1833`) | both `backgroundPosterUrl` raw (`:173`, `:347`) and `posterBackgroundImage = backgroundPosterUrl ?: posterUrl` (`:178`, `:334`) | exists |
| Logo (title-replacement image) | `logoUrl: String?` (`MainAPI.kt:1835`) | `logoUrl` (`:179`, `:348`) | exists |
| Duration | `duration: Int?`, **minutes** (`MainAPI.kt:1825`) | `durationText: UiText?` = `duration_format(dur)`, null when `<= 0` (`:186`, `:386`) | exists, minutes only |
| Rating | `score: Score?` (`MainAPI.kt:1823`; `Score` at `MainAPI.kt:919-990`) | `ratingText: UiText?` = `rating_format(score.toStringNull(0.1, 10, 1, false, '.'))` (`:182`, `:374-375`) | exists, pre-formatted |
| Tags / genres | `tags: List<String>?` (`MainAPI.kt:1824`), blanks stripped in `APIRepository.load` (`core/.../APIRepository.kt:117`) | `tags: List<String>` (`:168`, `:337`) — raw, the one list that survives untranslated | exists |
| Age rating | `contentRating: String?` (`MainAPI.kt:1836`) | `contentRatingText: UiText?` (`:183`, `:376`) | exists |
| **Season number** | **yes** — `EpisodeResponse.seasonNames: List<SeasonData>?` (`MainAPI.kt:2237`), `SeasonData(season, name, displaySeason)` (`MainAPI.kt:2227-2231`), and `Episode.season: Int?` (`MainAPI.kt:2558`) | **no field** (`:166-196`) | see correction below |
| **Per-season episode count** | derivable: group `TvSeriesLoadResponse.episodes` (`MainAPI.kt:2690`) or `AnimeLoadResponse.episodes` (`MainAPI.kt:2333`) by `Episode.season` | **no field**; `ResultViewModel2` has it as `_episodesCountText` (`:545`, computed `:2060-2070`) | see correction below |
| Cast (`ActorData`) | `actors: List<ActorData>?` (`MainAPI.kt:1829`); `ActorData(actor, role, roleString, voiceActor)` (`MainAPI.kt:1545-1550`), `Actor(name, image)` (`MainAPI.kt:1534-1537`), `ActorRole` = Main/Supporting/Background (`MainAPI.kt:1524-1528`) | `actors: List<ActorData>?` — **but only when the first actor has a non-blank image** (`:277`, `:339`); otherwise it is nulled and a single `cast_format` sentence is built instead (`:340-342`) | exists, with an all-or-nothing defect |
| Recommendations | `recommendations: List<SearchResponse>?` (`MainAPI.kt:1828`) | **not on `ResultData`**; separate `_recommendations: LiveData<List<SearchResponse>>` (`:565`, posted `:2546`) | exists elsewhere |
| Trailers | `trailers: MutableList<TrailerData>` (`MainAPI.kt:1826`); `TrailerData(extractorUrl, referer, raw, headers)` (`MainAPI.kt:1779-1784`) | **not on `ResultData`**; separate `_trailers: LiveData<List<ExtractedTrailerData>>` (`:549`) | exists elsewhere |
| "No playable source" | `comingSoon: Boolean` (`MainAPI.kt:1830`) — for movies it is literally `dataUrl.isBlank()` (`MainAPI.kt:2454`, `:2515`, `:2535`) | `comingSoon` (`:172`, `:338`) and `noEpisodesFoundText` (`:188`, `:396-399`) | partial — see below |
| Show status | `EpisodeResponse.showStatus: ShowStatus?` (`MainAPI.kt:2234`) | `onGoingText: UiText?` (`:187`, `:387-395`) | exists |
| Next airing | `EpisodeResponse.nextAiring` (`MainAPI.kt:2235`) | `nextAiringEpisode` / `nextAiringDate` (`:192-193`, computed `:279-320`) | exists |
| Sync ids | `syncData: MutableMap<String, String>` (`MainAPI.kt:1831`); readers `getMalId`/`getAniListId`/`getImdbId`/`getTMDbId` (`MainAPI.kt:1913-1934`) | `syncData: Map<String, String>` (`:175`, `:323`) | exists |
| VPN / meta-provider warnings | from the `APIRepository`, not the response | `vpnText` (`:184`, `:377-383`), `metaText` (`:185`, `:384-385`) | exists |
| Unique key | `uniqueUrl: String` (`MainAPI.kt:1838`), used by `LoadResponse.getId()` (`:410-419`) | `url: String` (`:167`, `:336`) — the *url*, not `uniqueUrl` | exists, shaped differently |

### Verifying the audit's row-7a claim

`docs/plans/tv-screen-audit.md:74` says `ResultData` "has actors but no season integer or per-season
count (`A/ui/result/ResultViewModel2.kt:169-170`)".

**Literally correct about `ResultData`, and misleading about the codebase.** Corrections:

1. `ResultData` (`ResultViewModel2.kt:166-196`) indeed has no season field and no episode count. True.
2. The "has actors" half carries a caveat the row does not state: `actors` is non-null **only if the
   first actor has an image** (`:277`, `:339`). A provider that gives 38 names and no photos yields
   `actors = null` and one `cast_format` string (`:340-342`). The board's text-initials avatar
   (7a-ii TEXT ONLY) is not reachable from `ResultData` at all, and the board's own note says so.
3. Season integers **do** exist outside `ResultData`:
   - `EpisodeResponse.seasonNames: List<SeasonData>?` (`MainAPI.kt:2237`), each `SeasonData` carrying
     `season: Int`, `name: String?`, `displaySeason: Int?` (`MainAPI.kt:2227-2231`);
   - `Episode.season: Int?` (`MainAPI.kt:2558`) and `ResultEpisode.seasonIndex` / `season`
     (`app/.../ui/result/ResultFragment.kt:45-46` — `seasonIndex` is the grouping key, `season` is
     the display value);
   - `ResultViewModel2._seasonSelections: LiveData<List<Pair<UiText?, Int>>>` (`:561`), posted from
     the distinct season keys at `:2417-2419`. The `Int` in that pair **is** the season number.
4. Per-season episode count also exists: `currentEpisodes` is
   `Map<EpisodeIndexer(dubStatus, season), List<ResultEpisode>>` (`:510`, `:2422`), so
   `currentEpisodes[indexer].size` is exactly the per-season count. It is posted as
   `_episodesCountText = episode_format(size, episode|episodes)` (`:2060-2070`) — a rendered string,
   not a number, and only for the *currently selected* season.

So the correct statement is: **`ResultData` lacks them; `LoadResponse` and `ResultViewModel2` both
have them, the first as data and the second as pre-rendered text for one season at a time.** A new
`TitleDetail` can carry both as integers without any new provider API.

### Concrete `LoadResponse` subclasses

- `MovieLoadResponse` (`MainAPI.kt:2463-2489`) — `dataUrl: String` is the play payload (`:2470`).
- `TvSeriesLoadResponse` (`MainAPI.kt:2683-2740`) — `episodes: List<Episode>` (`:2690`).
- `AnimeLoadResponse` (`MainAPI.kt:2320-2379`) — `episodes: MutableMap<DubStatus, List<Episode>>`
  (`:2333`), plus `engName` / `japName` / `synonyms` (`:2323-2324`, `:2338`).
- `LiveStreamLoadResponse` (`MainAPI.kt:2414`), `TorrentLoadResponse` (`MainAPI.kt:2268`) — the latter
  carries `magnet` / `torrent` instead of a `dataUrl` (used at `ResultViewModel2.kt:2388`).
- `ResultViewModel2.LoadResponseFromSearch` (`:2624-2642`, in the ABI dump at
  `app/api/classes-jar.api:29943`) — the placeholder built from a search card.

### "No playable source"

There is **no first-class signal**. Three partial ones:

- `comingSoon` (`MainAPI.kt:1830`), which for a movie is `dataUrl.isBlank()` (`MainAPI.kt:2454`,
  `:2515`, `:2535`) — so a movie with nothing to play *does* read as coming soon.
- `noEpisodesFoundText`, non-null only for an empty `TvSeriesLoadResponse` or an
  `AnimeLoadResponse` with no non-empty list (`ResultViewModel2.kt:396-399`) — the board's 7b note
  (`design/TV Detail Player.dc.html`, 7b EMPTY) says a film has none of this and that the signal
  needs inventing, which is correct.
- `APIRepository.isInvalidData(data)` (`core/.../APIRepository.kt:47-49`) is applied only at
  `loadLinks` time (`:220`), i.e. after the user presses play.

`TitleDetail.playData` already encodes "nothing playable" as null
(`DetailContracts.kt:19-21`), so the mapping is `dataUrl.takeIf { !isInvalidData(it) }`.

---

## 3. Episodes

### Produced by

`ResultViewModel2.postEpisodes(loadResponse, mainId, updateFillers)` (`:2207-2442`) — a
`when (loadResponse)` over the five subclasses:

- **Anime** (`:2219-2274`): nested loop over `Map<DubStatus, List<Episode>>`; episode number falls
  back to `index + 1` (`:2226`); id is `mainId + episode + dubIndex*1_000_000 + season*10_000`
  (`:2227-2229`); `getTotalEpisodeIndex` gives the cross-season running number (`:2231-2237`);
  dedup by id via `existingEpisodes` (`:2239-2240`); grouped under
  `EpisodeIndexer(dubStatus, seasonIndex ?: 0)` (`:2265-2269`).
- **TvSeries** (`:2276-2331`): sorted by `season*10_000 + episode` (`:2280-2282`); id is
  `mainId + season*100_000 + episodeIndex + 1` (`:2284-2285`); grouped under
  `EpisodeIndexer(DubStatus.None, seasonIndex ?: 0)` (`:2322-2323`).
- **Movie** (`:2333-2354`), **LiveStream** (`:2356-2377`), **Torrent** (`:2379-2400`): a
  `singleMap` of exactly one `ResultEpisode` with `episode = 0`, `season = null` and **the same id
  as the title** (`:2344`, `:2367`, `:2390` — "HAS SAME ID"). That identity is what makes a movie's
  resume position the title's resume position.
- anything else → empty map (`:2402-2404`).

Episode names are filtered: a name matching `^[eE]pisode [0-9]*$` becomes `null`
(`:650-657`, called at `:2245` and `:2302`).

### What each episode carries

`ResultEpisode` (`app/.../ui/result/ResultFragment.kt:40-65`), `@Serializable`, in the ABI dump at
`app/api/classes-jar.api:29502`:

`headerName`, `name: String?`, `poster: String?`, `episode: Int`, **`seasonIndex: Int?`** (the
grouping season), **`season: Int?`** (the *display* season), `data: String` (the opaque play
payload), `apiName`, `id: Int`, `index: Int`, `position: Long` ms, `duration: Long` ms,
`score: Score?`, `description: String?`, `isFiller: Boolean?`, `tvType: TvType`, `parentId: Int`,
`videoWatchState: VideoWatchState`, `totalEpisodeIndex: Int?`, `airDate: Long?`, `runTime: Int?`
(minutes), `seasonData: SeasonData?`.

So **yes**: both a season number and an episode number are on every episode (`:44-46`), and the
episode also carries its own resume position and duration, read from storage at construction time by
`buildResultEpisode` (`:83-130`, specifically `getViewPos(id)` at `:104` and `getVideoWatchState(id)`
at `:105`).

`ResultEpisode.getWatchProgress()` (`:133-135`) is `(getDisplayPosition()/1000) / (duration/1000)`
with **no zero guard** — a stored `duration` of 0 yields `NaN`. `ApiRepositorySearchSource` documents
and works around the same defect (`app/.../ApiRepositorySearchSource.kt:285-304`).

### How seasons are grouped today

By `EpisodeIndexer(dubStatus, season)` (`ResultViewModel2.kt:504-507`). The selections published are:

- `_seasonSelections` — `(UiText?, Int)` per distinct season (`:561`, `:2417-2419`), the text from
  `seasonToTxt(seasonData, season)` (`:627-644`), which uses `SeasonData.name` alone when
  `displaySeason` is null and `season_format` otherwise, and `no_season` for season `0` (`:628-630`).
- `_dubSubSelections` (`:553`, `:2415`).
- `_rangeSelections` — pages of `EPISODE_RANGE_SIZE` (`:557`, `:2055-2058`; `EpisodeRange` at
  `:150-157`, ranges built at `:666-767`). TV sets `EPISODE_RANGE_SIZE = 50`
  (`app/.../ui/result/ResultFragmentTv.kt:89`).
- `_sortSelections` / `_selectedSorting` — six `EpisodeSortType`s (`:210-217`), with rating and date
  options only offered when some episode actually has them (`:2018-2028`, `:2145-2160`, posted `:2172`).

Only one season's one range is ever in `_episodes` at a time (`:2141`, `:2184`), and
`changeSeason` / `changeRange` / `changeDubStatus` re-post it (`:1880-1898`). Selecting a season also
*persists* the choice: `setDub`, `setResultSeason`, `setResultEpisode` (`:2107-2109`).

### What `ResultFragmentTv` does with them

- Builds an `EpisodeAdapter` with a click callback straight into `viewModel.handleAction`
  (`ResultFragmentTv.kt:430-440`).
- On each `_episodes` emission (`:806-858`): finds the last episode past
  `NEXT_WATCH_EPISODE_PERCENTAGE` or marked `Watched` (`:813-815`), takes the next one as
  `firstUnwatched` (`:817-818`), labels the play button `S{season}:E{episode}` or
  `Episode {n}` (`:821-831`), wires click → `ACTION_CLICK_DEFAULT` and long-click →
  `ACTION_SHOW_OPTIONS` (`:832-845`), reveals the play/episodes buttons once (`:846-851`), then
  `submitList` (`:855`).
- Season / range / dub selectors are `SelectAdaptor` recyclers that hide themselves when there is
  only one option (`:121-134`, `:767-784`), each routed through `handleSelection` (`:101-119`).
- The episode drawer is a slide-over panel toggled by focus (`:234-251`, `:306-322`, `:359-363`).

---

## 4. Bookmark and favourite

### Where the storage is

`DataStoreHelper` (`app/src/main/java/com/lagradost/cloudstream3/utils/DataStoreHelper.kt:71`), an
`object` over `SharedPreferences` keyed `"$currentAccount/$FOLDER/$id"`. It is **not** `SyncAPI`:
`SyncAPI` is the remote-tracker layer, and `AccountManager.localListApi = LocalList()`
(`app/.../syncproviders/AccountManager.kt:26`) is a `SyncAPI` *façade over* `DataStoreHelper`
(`app/.../syncproviders/providers/LocalList.kt`). Every write below sets
`AccountManager.localListApi.requireLibraryRefresh = true` as its only notification
(`DataStoreHelper.kt:521`, `:610`, `:668`, `:674`).

### Exact calls — bookmark (watch status)

| Call | Signature | Line |
|---|---|---|
| read status | `getResultWatchState(id: Int): WatchType` | `DataStoreHelper.kt:783-791` |
| write status | `setResultWatchState(id: Int?, status: Int)` — takes the **`internalId`**, not the enum; `WatchType.NONE` deletes instead of writing | `:774-781` |
| read row | `getBookmarkedData(id: Int?): BookmarkedData?` | `:614-617` |
| write row | `setBookmarkedData(id: Int?, data: BookmarkedData)` | `:608-612` |
| read all | `getAllBookmarkedData(): List<BookmarkedData>` | `:619-623` |
| delete | `deleteBookmarkedData(id: Int?)` — removes both the status key and the data key | `:519-524` |
| all ids | `getAllWatchStateIds(): List<Int>?` | `:507-512` |

`BookmarkedData` (`:366-381`, superclass args `:382-...`) is `bookmarkedTime, id,
latestUpdatedTime, name, url, apiName, type, posterUrl, year, syncData, quality, posterHeaders,
plot, score, tags`.

### Exact calls — favourite

| Call | Signature | Line |
|---|---|---|
| read | `getFavoritesData(id: Int?): FavoritesData?` | `DataStoreHelper.kt:678-681` |
| write | `setFavoritesData(id: Int?, data: FavoritesData)` | `:672-676` |
| remove | `removeFavoritesData(id: Int?)` | `:666-670` |
| read all | `getAllFavorites(): List<FavoritesData>` | `:660-664` |

`FavoritesData` (`:427-442`) is the same shape with `favoritesTime` in place of `bookmarkedTime`.

Favourite is a **boolean derived from presence**: `postFavorites` is
`_favoriteStatus.postValue(getFavoritesData(id) != null)` (`ResultViewModel2.kt:2012-2016`).

### Does a write need a `Context`?

**No.** `DataStoreHelper` imports `setKey` / `getKey` / `removeKey` from
`CloudStreamApp.Companion` (`DataStoreHelper.kt:7-14`), which route through a process-global
`WeakReference<Context>` (`app/.../CloudStreamApp.kt:116-170`). So the calls are context-free — and
**silently no-ops when that weak reference is empty** (`CloudStreamApp.kt:136-142` — `context?.setKey`).

The `Context?` on `ResultViewModel2.updateWatchStatus` (`:776-779`) and `toggleFavoriteStatus`
(`:975-978`) is *not* for storage; it is for the duplicate-warning `AlertDialog` in
`checkAndWarnDuplicates` (`:1040-1046`, `@MainThread` at `:1039`). A headless source can skip it,
at the cost of that de-duplication.

### Flow or get/set?

**Get/set only.** There is exactly one `StateFlow` in the whole file and it is the account selector,
not the library: `selectedAccountNumberFlow` (`DataStoreHelper.kt:170-172`). Bookmark and favourite
have no flow, no `LiveData` and no listener. Change notification is three process-global pushes:

- `MainActivity.bookmarksUpdatedEvent(true)` and `MainActivity.reloadLibraryEvent(true)`
  (`app/.../MainActivity.kt:263`, `:273`), fired from `updateWatchStatus` (`ResultViewModel2.kt:842-845`)
  and `toggleFavoriteStatus` (`:988`, `:1034`);
- the `requireLibraryRefresh` flag polled by `LibraryViewModel`
  (`app/.../ui/library/LibraryViewModel.kt:94`, `:114`).

So a Compose screen must either hold its own `MutableStateFlow` seeded from `get…` and updated on its
own writes, or subscribe `bookmarksUpdatedEvent`. The first is what `ResultViewModel2` effectively
does (`_watchStatus` / `_favoriteStatus`, `:770-771`, `:614-615`).

### The current call site

The task names `ResultFragmentTv.kt:294-295`. Those two lines are
`resultBookmarkButton` and `resultFavoriteButton` inside the **focus-order list** of the
`redirectToPlay` focus listener (`:289-298`) — not the call sites. The real ones are:

- bookmark: `:569-591` — `observe(viewModel.watchStatus)`, a bottom dialog over
  `WatchType.entries`, committing with `viewModel.updateWatchStatus(WatchType.entries[it], context)`
  (`:586`);
- favourite: `:593-630` — `observeNullable(viewModel.favoriteStatus)`, icon swap, then
  `viewModel.toggleFavoriteStatus(context) { newStatus -> … toast … }` (`:604-620`);
- subscribe (emulator-only, `:633`): `viewModel.toggleSubscriptionStatus(context)` (`:643`).

### The complete set of watch-status values

`WatchType` (`app/src/main/java/com/lagradost/cloudstream3/ui/WatchType.kt:7-18`), in the ABI dump at
`app/api/classes-jar.api:25297`:

| Constant | `internalId` | string |
|---|---|---|
| `WATCHING` | 0 | `type_watching` |
| `COMPLETED` | 1 | `type_completed` |
| `ONHOLD` | 2 | `type_on_hold` |
| `DROPPED` | 3 | `type_dropped` |
| `PLANTOWATCH` | 4 | `type_plan_to_watch` |
| `NONE` | 5 | `type_none` |

Six values. `NONE` is "not bookmarked" and is stored as the *absence* of a key
(`DataStoreHelper.kt:774-781`); `fromInternalId(null)` returns `NONE` (`WatchType.kt:16`).

Do **not** confuse it with `SyncWatchType` (`WatchType.kt:20-32`), the remote-tracker enum, whose
`internalId`s differ (`NONE = -1`) and which has `REWATCHING` instead of a local `NONE`.

The TV dialog offers all six in enum order and commits by index (`ResultFragmentTv.kt:581-586`),
which means the board's single bookmark toggle is a narrowing of today's behaviour.

---

## 5. Resume position

### Storage

One key, `VIDEO_POS_DUR`, per **episode id** — and for a movie the episode id *is* the title id
(`ResultViewModel2.kt:2344`).

| Call | Signature | Line |
|---|---|---|
| read | `getViewPos(id: Int?): PosDur?` | `DataStoreHelper.kt:745-748` |
| write | `setViewPos(id: Int?, pos: Long, dur: Long)` — **silently returns when `dur < 30_000`** | `:683-687` |
| write + resume bookkeeping | `setViewPosAndResume(id, position, duration, currentEpisode, nextEpisode)` | `:693` |

`PosDur(position: Long, duration: Long)` — milliseconds (`DataStoreHelper.kt:236-240`).

`PosDur.fixVisual()` (`:241-251`) is the display snap, not the seek value: `duration <= 0` → `(0,
dur)`; `<= 1 %` → 0; `<= 5 %` → 5 %; `>= 95 %` → full. `ApiRepositorySearchSource` reuses it for the
search card's progress rule and documents why (`app/.../ApiRepositorySearchSource.kt:291-294`).

"Which episode was last watched" is a second key, `RESULT_RESUME_WATCHING`:

- `setLastWatched(parentId, episodeId, episode, season, isFromDownload, updateTime)`
  (`DataStoreHelper.kt:559-580`);
- `getLastWatched(id: Int?): DownloadObjects.ResumeWatching?` (`:592-598`);
- `removeLastWatched(parentId)` (`:587-590`);
- `ResumeWatching(parentId, episodeId, episode, season, updateTime, isFromDownload)`
  (`app/.../utils/downloader/DownloadObjects.kt:175-182`).

Also per title: `getResultSeason(id)` / `getResultEpisode(id)` / `getDub(id)`
(`DataStoreHelper.kt:793-807`, `:765-772`) — the remembered selector positions, read back in
`load` (`ResultViewModel2.kt:2716-2718`).

### How the page reads it

`ResultViewModel2.resume()` (`:2448-2478`):

1. `getLastWatched(currentId)` (`:2450`); bail if its `parentId` disagrees (`:2452`) or `episodeId`
   is null (`:2453`).
2. Find that id among the loaded episodes (`:2456-2457`).
3. `getViewPos(resume.episodeId)` → `ResumeProgress(position/1000, duration/1000,
   txt(resume_remaining, secondsToReadableText((duration - position)/1000)))` (`:2461-2474`).
4. Return `ResumeWatchingStatus(progress, isMovie, result)` (`:2477`) — `ResumeWatchingStatus` at
   `:432-436`, `ResumeProgress` at `:426-430`.

Note `resume()` reads **raw** `position`/`duration`, not `fixVisual()`, whereas
`ResultEpisode.getDisplayPosition()` (`ResultFragment.kt:74-81`) applies the snap. Two different
numbers for the same episode.

`secondsToReadableText` (`:258-270`) is the translated span; its KDoc (`:229-257`) records why it
exists rather than reusing `AccountManager.secondsToReadable` (that one is published ABI at
`app/api/classes-jar.api:16485`).

### The existing resume label on the TV page

`ResultFragmentTv.kt:479-546`. Three strings:

- the **title line** `resultResumeSeriesTitle` — `getNameFull(name, episode, season)`, hidden for a
  movie (`:491-499`);
- the **remaining line** `resultResumeSeriesProgressText.setText(progress.progressLeft)` (`:500`),
  i.e. `resume_remaining` + the readable span;
- the **button label** (`:516-527`): `R.string.resume` for a movie, else
  `"S{season}:E{episode}"`, else `"Episode {n}"`.

So today a movie's button says the bare word **"Resume"** with no clock, and a series' says
`S1:E4` with no episode title — exactly what the board calls out
(`design/TV Detail Player.dc.html`, 7a intro, citing `ResultFragmentTv.kt:516-527`). The progress
bar is `max = progress.maxProgress`, `progress = progress.progress`, both in seconds (`:501-505`).

Press → `viewModel.handleAction(EpisodeClickEvent(storedData.playerAction, resume.result))`
(`:529-536`); long-press → `ACTION_SHOW_OPTIONS` (`:538-543`).

### "Start over" — verifying the design note

The note claims `loadPlayer` keeps `playbackPosition` whenever `sameEpisode` is true and
`startPosition` is null (`CS3IPlayer.kt` ~:274, :289-291), so Start over must pass
`startPosition = 0L`.

**The mechanism is confirmed; the line numbers are slightly off and, more importantly, the
conclusion is incomplete for a fresh navigation.**

`CS3IPlayer.loadPlayer` (`app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:266-319`):

```kotlin
if (sameEpisode) {            // :278
    saveData()                // :279
} else {
    currentSubtitles = subtitle
    playbackPosition = 0      // :282
}

startPosition?.let {          // :285
    playbackPosition = it     // :286
}
```

So: `sameEpisode == true` + `startPosition == null` → `playbackPosition` is untouched (only
`saveData()` runs). `startPosition` is applied unconditionally afterwards, so an explicit `0L` does
override. The note's `:289-291` is actually the `autoPlay` line (`:290`); the relevant lines are
**`:278-287`**.

**But a new detail screen does not call `loadPlayer`.** It navigates to `GeneratorPlayer`, and
`GeneratorPlayer.loadLink` computes the start position itself
(`app/.../ui/player/GeneratorPlayer.kt:529-542`):

```kotlin
startPosition = if (sameEpisode) null else { if (isNextEpisode) 0L else getPos() }   // :534-536
```

`getPos()` is `getViewPos(viewModel.state.generatorState?.id)`, returning `0L` when the duration is
0 or progress is over 95 % (`GeneratorPlayer.kt:247-254`). On a fresh navigation `sameEpisode` is
false and `isNextEpisode` is false (`:187`), so the start position comes **from
`DataStoreHelper`**, not from a caller argument.

And `GeneratorPlayer.newInstance(generator, index, syncData)` has **no start-position parameter**
(`GeneratorPlayer.kt:151-164`).

**Therefore:** "Start over" cannot be implemented by passing `startPosition = 0L` through the
existing navigation, because there is nowhere to pass it. Either
`DataStoreHelper.setViewPos(episodeId, 0, existingDuration)` is written before navigating
(`DataStoreHelper.kt:683-687` — note the `dur < 30_000` early return, so the stored duration must be
reused), or `GeneratorPlayer.newInstance` / `VideoGenerator` gains an explicit start position. The
second is a change to a class in the ABI dump (`app/api/classes-jar.api:27241`). This is an open
decision, not something to settle inside a feature PR.

---

## 6. Trailer

### The data

`LoadResponse.trailers: MutableList<TrailerData>` (`MainAPI.kt:1826`), where
`TrailerData(extractorUrl: String, referer: String?, raw: Boolean, headers: Map<String,String>)`
(`MainAPI.kt:1779-1784`, in the dump at `app/api/classes-jar.api` as
`com/lagradost/cloudstream3/TrailerData`). Providers add them through four `addTrailer` overloads
(`MainAPI.kt:1960-1992`, `:2000-2008`, `:2011-2042`), all of which **no-op when
`LoadResponse.isTrailersEnabled` is false** (`MainAPI.kt:1859`, checked `:1965`, `:2006`, `:2016`).
That flag is a user preference, set by `Context.updateHasTrailers()` reading `show_trailers_key`
(`app/.../utils/AppContextUtils.kt:431-438`).

`extractorUrl` is **not** a playable URL. It must go through `loadExtractor`.

### Resolution

`ResultViewModel2.loadTrailers(loadResponse)` (`:2480-2487`) → `getTrailers(loadResponse, limit = 3)`
(`:2489-2541`): windows the list, `amap`s `loadExtractor(extractorUrl, referer, subCb, linkCb)` over
each, and when the extractor finds nothing *and* `raw` is set, synthesises a single `ExtractorLink`
from the raw URL (`:2513-2526`). The result is
`ExtractedTrailerData(mirros: List<Pair<ExtractorLink, String>>, subtitles)` (`:491-494`), published
as `_trailers: LiveData<List<ExtractedTrailerData>>` (`:549`). It is only called from `load` and only
when `loadTrailers = true` (`:2733-2734`).

### What `ResultTrailerPlayer` does — and what TV actually does

`app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultTrailerPlayer.kt:26` extends
`ResultFragmentPhone`. It is the **phone** result page's *inline* trailer player: it owns a
`ComposeView`-free view-binding player embedded in the result page, with its own fullscreen toggle
(`:177-216`), its own intro "play" overlay (`:249-255`), stubbed-out episode navigation (`:73-76`)
and stubbed-out mirror/track dialogues (`:156-167`). It is **not** reachable from TV and has nothing
to do with the TV trailer button.

The TV page does something much simpler (`ResultFragmentTv.kt:548-567`):

```kotlin
observe(viewModel.trailers) { trailersLinks ->
    context?.updateHasTrailers()                                   // :549
    if (!LoadResponse.isTrailersEnabled) return@observe            // :550
    val extractedTrailerLinks = trailersLinks.flatMap { it.mirros }.map { it.first }   // :551-552
    resultPlayTrailer.isGone = extractedTrailerLinks.isEmpty()     // :554
    resultPlayTrailerButton.setOnClickListener {
        activity.navigate(R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(ExtractorLinkGenerator(extractedTrailerLinks, emptyList()), 0))
    }                                                              // :555-565
}
```

**A trailer button can be wired without building a new player.** The whole path is: a list of
resolved `ExtractorLink`s → `ExtractorLinkGenerator(links, emptyList())` →
`GeneratorPlayer.newInstance(generator, 0)` → `navigate(R.id.global_to_navigation_player)`. All four
already exist. The only work a new screen needs is somewhere to resolve the trailers from
(`getTrailers` is `private` on `ResultViewModel2`, `:2489`) and the visibility rule
(`isTrailersEnabled` plus a non-empty mirror list).

---

## 7. Play

### How the TV page starts playback for an episode

Three button paths, all identical in shape:

| Button | Call | Line |
|---|---|---|
| Resume | `viewModel.handleAction(EpisodeClickEvent(storedData.playerAction, resume.result))` | `ResultFragmentTv.kt:529-536` |
| Play series | `viewModel.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, firstUnwatched))` | `:832-839` |
| Play movie | `viewModel.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, ep))` | `:680-684` |
| Any episode row | `viewModel.handleAction(episodeClick)` from `EpisodeAdapter` | `:430-440` |
| Long-press (all) | `EpisodeClickEvent(ACTION_SHOW_OPTIONS, …)` | `:538-543`, `:685-690`, `:840-845` |

`storedData.playerAction` is `EpisodeAdapter.getPlayerAction(context)`
(`app/.../ui/result/ResultFragment.kt:294`), a user preference.

`handleAction` just launches `handleEpisodeClickEvent` on `viewModelScope`
(`ResultViewModel2.kt:1354-1357`). Then:

- `ACTION_CLICK_DEFAULT` (`:1457-1470`) → chromecast if connected, else `getPlayerAction(ctx)`,
  recursing with that action. **Needs the global `activity`** (`:1458`).
- `ACTION_PLAY_EPISODE_IN_PLAYER` (`:1586-1609`) is the one that opens the player:

```kotlin
val list = HashMap<String, String>(currentResponse?.syncData ?: emptyMap())   // :1587
val generator = generator ?: return                                          // :1588
val index = generator.videos.indexOfFirst { it.id == click.data.id }         // :1591
if (currentResponse?.type == TvType.CustomMedia) { generator.generateLinks(...) }  // :1593-1600
else activity?.navigate(R.id.global_to_navigation_player,
        GeneratorPlayer.newInstance(generator, index, list))                 // :1602-1607
```

The `generator` is a `RepoLinkGenerator` built in `postEpisodeRange` (`:2116-2125`) over **all
episodes of the selected dub status, across seasons, sorted by season** (`:2120-2124`), or over the
single movie episode (`:2116-2117`). `RepoLinkGenerator(episodes, page = loadResponse)` is at
`app/.../ui/player/RepoLinkGenerator.kt:24-27`; `generateLinks` at `:43-50` with a per-`(apiName, id)`
cache at `:30-31`, `:53-62`.

The nav action is `R.id.global_to_navigation_player`, a global action; the TV result node also
declares `action_navigation_results_tv_to_navigation_player`
(`app/src/main/res/navigation/mobile_navigation.xml:528-534`) which this path does not use.

**So a new screen routes "play" to the same existing path by:** holding a `RepoLinkGenerator` over
its episode list, finding the offset of the chosen episode, and calling
`activity.navigate(R.id.global_to_navigation_player, GeneratorPlayer.newInstance(generator, index, syncData))`.
Nothing else is required.

`ResultViewModel2` also has an in-page link loader for the non-player actions:
`loadLinks(result, isVisible, sourceTypes, clearCache, isCasting)` (`:1305-1352`), which builds a
*temporary* `RepoLinkGenerator(listOf(result))` (`:1312`) and publishes
`LinkProgress(links.size, subs.size)` as it goes (`:1316-1320`, `:421-424`), returning
`LinkLoadingResult(links, subs, syncData)` (`:438-442`, `:1347-1351`). That is the closest existing
analogue to `DetailSource.links`, and it is what the loading bottom-sheet observes
(`ResultFragmentTv.kt:728-760`).

### `startPosition` / `startAction` / `startValue`

There is **no `startPosition`** anywhere in this path. The three bundle keys are
`startAction`, `startValue` and (from a `ResumeWatchingResult`) `season` / `episode`.

`ResultFragment.newInstance` (`app/.../ui/result/ResultFragment.kt:147-184`) — two overloads, both
writing the same private keys (`:138-145`):

| key | from `newInstance(card, startAction, startValue)` | from `newInstance(url, apiName, name, startAction, startValue)` |
|---|---|---|
| `"url"` | `card.url` (`:151`) | `url` (`:177`) |
| `"apiName"` | `card.apiName` (`:152`) | `apiName` (`:178`) |
| `"name"` | `card.name` (`:153`) | `name` (`:179`) |
| `"season"` / `"episode"` | only when the card is a `ResumeWatchingResult` (`:154-159`) | never |
| `"startAction"` | `startAction` (`:160`) | `startAction` (`:180`) |
| `"startValue"` | only when non-null (`:161-162`) | always (`:181`) |
| `"restart"` | `true` (`:165`) | `true` (`:182`) |

`Fragment.getStoredData()` (`:281-316`) reads them back into
`StoredData(url, apiName, name, showFillers, dubStatus, start, playerAction, restart)` (`:232-241`):

- `showFillers` from `show_fillers_key` (`:287-288`);
- `dubStatus` from `getApiDubstatusSettings()` (`:289-291`);
- `playerAction` from `getPlayerAction(context)` (`:294`);
- `restart` is **consumed** — read then set false (`:296-299`), so a re-created fragment does not
  reload;
- `start` = `AutoResume(startAction, id = startValue, episode, season)` (`:301-314`; `AutoResume` at
  `ResultViewModel2.kt:159-164`), and `startValue` / `startAction` are **removed from the arguments**
  on the way out (`:306-307`).

`ResultFragmentTv.onBindingCreated` then calls
`viewModel.load(activity, url, apiName, showFillers, dubStatus, start)` when
`restart || !hasLoaded()` (`:264-272`), and `handleAutoStart(activity, autostart)` runs at the end of
a successful load (`ResultViewModel2.kt:2743`, implementation `:2552-...`). `START_ACTION_RESUME_LATEST = 1`
and `START_ACTION_LOAD_EP = 2` (`ResultFragment.kt:27-28`); `startValue` is an **episode id** for
the latter.

`AppContextUtils.loadResult(url, apiName, name, startAction = 0, startValue = 0)`
(`app/.../utils/AppContextUtils.kt:711-741`) is the entry everything else uses: it sets the Kitsu
poster flag (`:728-734`) and then, on the UI thread, navigates to
`getResultsId()` — `global_to_navigation_results_tv` on TV, `…_phone` otherwise (`:703-709`) — with
`ResultFragment.newInstance(url, apiName, name, startAction, startValue)` (`:736-741`).

---

## 8. Untrusted input

Everything a provider returns is third-party output. `.claude/rules/dependency-security.md` requires
it to be sanitized before reaching a WebView or an HTML parser.

### What is provider-supplied

From `LoadResponse` (`MainAPI.kt:1814-1838`): `name`, `url`, `apiName`, `plot`, `tags`,
`posterUrl`, `backgroundPosterUrl`, `logoUrl`, `contentRating`, `posterHeaders` (**arbitrary HTTP
headers**), `syncData` (values used as tracker ids), `trailers[].extractorUrl` / `.referer` /
`.headers`, `recommendations[]` (a whole `SearchResponse` each), `actors[].actor.name` /
`.actor.image` / `.roleString`, `duration`, `year`, `score`.

From `Episode` (`MainAPI.kt:2553-2565`): `data` (the opaque play payload), `name`, `posterUrl`,
`description`, `date`, `runTime`, `season`, `episode`.

From the extractors: every `ExtractorLink` url, name, referer and header, and every `SubtitleFile`.

Error text too: `Resource.Failure.errorString` (`ArchComponentExt.kt:52`) is a provider's own
exception message, and `ResultFragmentTv` prints it verbatim beside the url (`:951-952`).

### Where `:app` sanitizes, and where it does not

**Does not — the plot goes straight into an HTML parser.**

- `ResultFragmentTv.kt:883` → `resultDescription.setTextHtml(d.plotText)` →
  `app/.../utils/TextUtil.kt:148-157` → `str.html()` →
  `AppContextUtils.getHtmlText` → `HtmlCompat.fromHtml(text, FROM_HTML_MODE_LEGACY)`
  (`app/.../utils/AppContextUtils.kt:133-135`, `:137-147`). The only guard is a `try/catch` around
  the parse (`:138-146`). No tag allowlist, no stripping.
- The same plot is re-parsed into the expand dialog: `d.plotText.asString(ctx).html()`
  (`ResultFragmentTv.kt:894`).
- Episode synopses take the same route: `builder.setMessage(description.html())`
  (`ResultFragmentTv.kt:794`), with `description` straight off `Episode.description`.
- Phone does the same (`ResultFragmentPhone.kt:980`), as does the preview sheet
  (`MainActivity.kt:1534`).

`HtmlCompat.fromHtml` does not execute script, but it does build `URLSpan`s from `<a href>` and
honours inline styling, so a provider controls clickable targets and the rendered text's appearance.
Nothing here is a WebView, so this is a presentation-integrity issue rather than script execution —
but it is an HTML parser fed unsanitized third-party text, which the rule names explicitly.

**For comparison, `:feature:detail`'s desktop source already strips it:**
`DesktopDetailSource.withoutMarkup()` replaces `<br>` with a space, drops every other tag and
collapses runs of whitespace (`desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/detail/DesktopDetailSource.kt:79-80`,
documented `:75-78`). A Compose detail page renders `String`, not `Spanned`, so the same treatment is
the natural choice — and it is the *safer* one.

**Does sanitize or bound:**

- **Urls into the player**: `APIRepository.isInvalidData` rejects `""`, `"[]"`, `"about:blank"`
  before `loadLinks` (`core/.../APIRepository.kt:47-49`, `:220`), and `api.fixUrl` normalises the
  load url (`:99`).
- **Time**: every provider call is inside `withTimeout` with a clamped budget
  (`core/.../APIRepository.kt:61-63`, `:97`, `:138`, `:151`, `:168`, `:222`).
- **Throws**: `safeApiCall` converts any `Throwable` into a `Resource.Failure`
  (`ArchComponentExt.kt:207-218`); `loadLinks` catches and logs (`core/.../APIRepository.kt:225-228`);
  trailer extraction catches per trailer (`ResultViewModel2.kt:2530-2533`).
- **Tags**: blanks filtered at load (`core/.../APIRepository.kt:117`); the episode-name regex drops
  `"Episode 12"`-style filler (`ResultViewModel2.kt:650-657`).
- **Error strings**, in the new search source only: first line, 60 chars, grouped by reason
  (`app/.../ApiRepositorySearchSource.kt:223-235`). `ResultFragmentTv.kt:951-952` does none of this.
- **Image headers**: the new search card merges the app user-agent first and lets provider headers
  override it (`app/.../search/SearchTvFragment.kt:233-243`). The old result page just passes
  `d.posterHeaders` through (`ResultFragmentTv.kt:879`, `:912`, `:918`).
- **Desktop only**: `SandboxPolicy.isPublicHttpUrl` refuses non-public link targets
  (`DesktopDetailSource.kt:122-123`). **There is no Android equivalent** — `:app` hands provider urls
  to the player unchecked.

### What a new source owes

1. Strip markup from `plot` and every `Episode.description` before they leave the source, as desktop
   does. Do not hand HTML to Compose and do not reintroduce `HtmlCompat`.
2. Bound every string the page renders in a fixed box — actor names, episode names, tags, the error
   detail. The board's geometry is fixed-size on purpose (7a PARTIAL note).
3. Treat `posterHeaders` as data: merge, never replace, the app's own headers.
4. Keep provider error text out of prose. `ApiRepositorySearchSource.detailOf`
   (`:223-235`) is the precedent, and `DetailLoad.Failed.detail` is the matching field
   (`DetailMvi.kt:26`).
5. Never log a provider url — `DesktopDetailSource` has `withoutUrls()` for exactly this
   (`DesktopDetailSource.kt:92-94`, reason at `:52-54`).

---

## 9. The published-API cost

`app/api/classes-jar.api` is the committed ABI of `app/build/classes.jar`, the jar third-party
extensions compile against (`app/api/classes-jar.api:1-2`). The gate is `:app:checkExtensionApi`,
which is deliberately **not** part of `check`
(`build-logic/convention/src/main/kotlin/cloudstream.extension-api-jar.gradle.kts:82-85`).

The dump covers **everything under `com/lagradost/`**, not just `cloudstream3`:

```kotlin
val extensionApiDumpPackages = listOf("com/lagradost/")
```
(`cloudstream.extension-api-jar.gradle.kts:328`)

It reads raw `ACC_PUBLIC` rather than Kotlin metadata, so a Kotlin `internal class` is still a public
JVM class and still lands in the dump (`:62-64`, and the search source's own KDoc at
`app/.../ApiRepositorySearchSource.kt:44-51`).

### Which classes named above are in the dump

| Class | In the dump | Line |
|---|---|---|
| `com/lagradost/cloudstream3/ui/result/ResultViewModel2` | yes | `app/api/classes-jar.api:29832` |
| … `$Companion` / `$EpisodeIndexer` / `$LoadResponseFromSearch` / `$WhenMappings` | yes | `:29919`, `:29929`, `:29943`, `:30018` |
| … ten `$$inlined$sortedBy$N` comparators | yes | `:30024`-`:30064` |
| `…/ui/result/ResultViewModel2Kt` (file facade) | yes | `:30069` |
| `…/ui/result/ResultData` | yes | `:29436` |
| `…/ui/result/ResultEpisode` (+ `$$serializer`, `$Companion`) | yes | `:29502`, `:29583`, `:29595` |
| `…/ui/result/ResultFragment` (+ `$StoredData`) | yes | `:29600`, `:29614` |
| `…/ui/result/ResultFragmentKt` | yes | `:29648` |
| `…/ui/result/ResultFragmentPhone` | yes | `:29658` |
| `…/ui/result/ResultFragmentTv` | yes | `:29762` |
| `…/ui/APIRepository` (+ 4 nested) | yes | `:24851`, `:24881`, `:24892`, `:24908`, `:24916` |
| `…/utils/DataStoreHelper` (+ `$BookmarkedData`, `$FavoritesData`, `$PosDur`, `$ResumeWatchingResult`, `$SubscribedData`, `$LibrarySearchResponse`, serializers) | yes | `:32370`, `:32492`, `:32582`, `:32725`, `:32761`, `:32841`, `:32672` |
| `…/ui/WatchType` (enum) + `$Companion` | yes | `:25297`, `:25313` |
| `…/ui/player/GeneratorPlayer` | yes | `:27241` |
| `…/mvvm/Resource` + `$Success` / `$Failure` / `$Loading` | yes | `:15696`-`:15732` |
| `…/cloudstream3/LoadResponse`, `ActorData`, `TrailerData`, `Episode`, `SeasonData`, `Score` | yes | grep each by name; all present |
| `…/utils/BaseComposeFragment` | yes | `:32227` |
| `com/lagradost/cloudstream4/search/ApiRepositorySearchSource` (**`internal`**) | **yes** | `:36996`, plus five `access$…` synthetics at `:37003`-`:37007` |
| `com/lagradost/cloudstream4/search/ApiRepositorySearchSourceKt` + `SearchResultTarget` + an inlined comparator | yes | `:37013`, `:37014`, `:37020` |
| `com/lagradost/cloudstream4/search/ComposableSingletons$SearchTvFragmentKt` | yes | `:37025` |
| `com/lagradost/cloudstream4/search/DataStoreSearchHistory` (+ inlined `getKey` TypeReferences) | yes | `:37031`, `:37042`, `:37046` |
| `com/lagradost/cloudstream4/feature/detail/*` | **no** — `:feature:detail` is not merged into `classes.jar` (it declares no `src/*/kotlin/com/lagradost/cloudstream3` directory; see `cloudstream.extension-api-jar.gradle.kts:18-21`) | — |

### Would a new `internal class` in `app/src/main/java/com/lagradost/cloudstream4/detail/` add lines?

**Yes.** The precedent is exact: `ApiRepositorySearchSource` is `internal`
(`app/.../ApiRepositorySearchSource.kt:72`), lives in `com.lagradost.cloudstream4.search`, and is in
the dump at `app/api/classes-jar.api:36996` with five extra synthetic accessor lines. `cloudstream4`
is inside `com/lagradost/`, which is the whole of `extensionApiDumpPackages`
(`cloudstream.extension-api-jar.gradle.kts:328`), and `internal` is irrelevant to the dumper
(`:62-64`).

Expect, for an `AndroidDetailSource` plus a `DetailTvFragment`:

- the class line and every `public` member, including the `override`s — which **cannot** be
  `internal`, since Kotlin forbids narrowing an override (noted at
  `app/.../ApiRepositorySearchSource.kt:50-51`);
- one `access$…$p` / `access$…` synthetic per private member a lambda or nested class touches;
- a `…Kt` file facade if the file declares any top-level member;
- `$$inlined$…` copies for each inline-function call site (`sortedBy`, `getKey`);
- a `ComposableSingletons$…Kt` class for the fragment's Compose lambdas (cf. `:37025`);
- `R` field names if any new string is added to `:app` (`cloudstream.extension-api-jar.gradle.kts:60-61`).

None of these are *breaking* changes — additions normally are not
(`cloudstream.extension-api-jar.gradle.kts:78-80`) — but `:app:dumpExtensionApi` must be re-run and
the diff reviewed and committed in the same PR. **`$stable`**: a Compose-compiled class moving into a
module without the Compose compiler silently loses `public static final int $stable`
(`CLAUDE.md`, `cloudstream.extension-api-jar.gradle.kts:56-57`). Nothing here moves a class between
modules, so the trap does not apply — but if any part of this work relocates a class out of `:app`,
scan with `javap` before claiming it is signature-neutral.

---

## 10. The fragment host

### What `BaseComposeFragment` gives you

`app/src/main/java/com/lagradost/cloudstream3/utils/BaseComposeFragment.kt:17-43` —
`abstract class BaseComposeFragment : Fragment(), Screen`:

- `onCreateView` returns `createComposeView(inflater, container, savedInstanceState)` (`:18-22`);
- `onViewCreated` and `onConfigurationChanged` both call
  `fixSystemBarsPadding(view, padLeft = isLayout(TV or EMULATOR), padBottom = isLandscape())`
  (`:24-42`);
- the subclass implements `Screen.Content()`.

`createComposeView` (`core/settings/src/androidMain/kotlin/com/lagradost/cloudstream4/compose/ComposeFragment.kt:21-51`)
builds the `ComposeView` with `DisposeOnViewTreeLifecycleDestroyed` (`:26`), reads theme / primary
colour / layout from `rememberAppSettings()` (`:29-33`), wraps in `CloudStreamTheme` (`:35-38`), and
provides `LocalBackPress` from the activity's `OnBackPressedDispatcher` plus
`DeviceLayout.LocalLayout` (`:39-48`).

### What a sibling `DetailTvFragment` needs

Read off `SearchTvFragment` (`app/src/main/java/com/lagradost/cloudstream4/search/SearchTvFragment.kt:60-195`):

1. **Declaration.** `class DetailTvFragment : BaseComposeFragment()` (`:60`).
2. **ViewModel.** `by viewModels { viewModelFactory { initializer { … } } }` with the
   **application** context captured, not the fragment's (`:64-69`) — the source outlives the fragment
   for as long as the ViewModel does. `DetailViewModel` additionally takes an `id` and a
   `CoroutineScope` (`feature/detail/.../DetailViewModel.kt:22-29`), so the factory must pass
   `viewModelScope`-equivalent; it loads in `init` (`:37-39`).
3. **Lifecycle hooks actually used by the sibling.**
   - `onViewCreated` → `super` then one-shot argument handling (`:75-78`);
   - `onResume` → subscribe `afterPluginsLoadedEvent` and re-post a state action (`:81-85`);
   - `onStop` → unsubscribe (`:87-90`);
   - `onDestroyView` → `hideKeyboard(view)` then `super` (`:92-95`).
   `ResultFragmentTv` adds two more worth copying: `updateUIEvent += ::updateUI` in `onCreateView`
   and `-=` in `onDestroyView` (`ResultFragmentTv.kt:76-93`), and
   `activity?.setNavigationBarColorCompat(R.attr.primaryBlackBackground)` in `onResume`
   (`ResultFragmentTv.kt:194-198`).
4. **Arguments.** Already enumerated in §7 — `ResultFragment.newInstance` bundles `"url"`,
   `"apiName"`, `"name"`, `"startAction"`, `"startValue"`, `"restart"`, and (from a
   `ResumeWatchingResult` only) `"season"` / `"episode"`
   (`ResultFragment.kt:138-184`). Read them with
   `ResultFragment.getStoredData()` (`:281-316`), which is an extension on `Fragment`, is already
   public, and also resolves `showFillers`, `dubStatus` and `playerAction` from preferences. It
   consumes `restart` and removes `startAction`/`startValue` as a side effect (`:296-299`, `:306-307`),
   so call it **once**.
   The id `DetailSource` wants is the `(apiName, url)` pair, which is exactly what
   `encodeId` already produces (`app/.../ApiRepositorySearchSource.kt:386-387`).
5. **Compose host.** Override `Content()`:
   `val state by viewModel.state.collectAsState()`, then the screen inside
   `CloudStreamTheme { Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { … } }`
   (`SearchTvFragment.kt:179-194`). Note `createComposeView` already applies a `CloudStreamTheme`
   from `:core:theme`'s other entry point; `SearchTvFragment` nests a second one from
   `com.lagradost.cloudstream4.core.theme` (`:40`, `:185`) — follow that, it is the live precedent.
6. **Effects → navigation.** A `LaunchedEffect(viewModel) { viewModel.effect.collect { onEffect(it) } }`
   inside `Content()` (`:182-184`), with `onEffect` a plain fragment method doing the Android work
   (`:158-177`). For search that is `decodeId(effect.id)` then
   `loadResult(url, apiName, name)` (`:160-166`). For detail the two effects to route are
   `DetailEffect.Play` (`DetailMvi.kt:40`) → the §7 `GeneratorPlayer` navigation, and whatever new
   effect carries "open another title" → `loadResult` again.
7. **Dialogs stay in the fragment.** `SearchTvFragment` intercepts one action, shows an
   `AlertDialog`, and forwards to the ViewModel only on confirm (`:113-156`). The bookmark
   `showBottomDialog` (`ResultFragmentTv.kt:580-587`) is the detail-page equivalent.
8. **Posters.** `AsyncImage` with a Coil `ImageRequest` carrying `USER_AGENT` plus the provider's own
   headers, built in a `remember` keyed on context/url/headers
   (`SearchTvFragment.kt:229-250`).
9. **Nav registration.** No new graph node. `openSearchOnTvScreen()` rewrites the existing node's
   class name on the inflated graph (`SearchTvFragment.kt:205-208`), with the reasoning at
   `:197-204`: a nav graph has no per-layout resource qualifier because `isLayout(TV)` is a runtime
   preference. The detail equivalent is
   `(findNode(R.id.navigation_results_tv) as? FragmentNavigator.Destination)?.setClassName(DetailTvFragment::class.java.name)`,
   against the node currently naming `ResultFragmentTv`
   (`app/src/main/res/navigation/mobile_navigation.xml:512-520`). That node also owns the two actions
   to quick-search and to the player (`:521-534`), which are preserved by a class-name swap.

---

## What the boards draw that no data supports

Board source: `design/TV Detail Player.dc.html` (boards 7a, 7a-ii, 7b, 7c, 7d, 7e). Each gap below is
something the drawn page states and the code cannot currently answer.

**Shape only — the data exists but not in `TitleDetail`:**

1. **Season number as an integer, and per-season episode count.** Board 7a draws `Season 1` and
   `Episodes · 10` as two separate controls. The numbers exist on `LoadResponse`
   (`Episode.season`, `MainAPI.kt:2558`; `SeasonData`, `:2227-2231`) and in the ViewModel
   (`_seasonSelections`, `ResultViewModel2.kt:561`; `currentEpisodes[indexer].size`, `:2060`), but
   `TitleDetail` has no season concept at all (`DetailContracts.kt:23-32`) and `ResultData` has no
   field (`:166-196`). **Extend `TitleDetail`; no new provider API needed.**
2. **Cast.** `ActorData` exists (`MainAPI.kt:1545-1550`) and is not in `TitleDetail`.
3. **Recommendations** ("More like this", 7a-ii). `LoadResponse.recommendations` exists
   (`MainAPI.kt:1828`), carried on a separate `LiveData` (`ResultViewModel2.kt:565`), absent from
   `TitleDetail`.
4. **Trailer.** `trailers` exists (`MainAPI.kt:1826`); `TitleDetail` has no trailer field.
5. **Backdrop, logo, score, content rating, tags, show status, next airing, duration.** All on
   `LoadResponse` (`MainAPI.kt:1819-1836`, `:2234-2235`); none on `TitleDetail`.
6. **Resume state.** `DataStoreHelper.getViewPos` / `getLastWatched` exist
   (`DataStoreHelper.kt:745`, `:592`); nothing in `:feature:detail` models a resume position.
7. **Bookmark and favourite.** `DataStoreHelper` get/set exist (§4); no `DetailAction`, no
   `DetailState` field, no seam method (`DetailMvi.kt:29-37`, `DetailContracts.kt:60-69`).

**Genuinely absent — no data behind it anywhere:**

8. **"Cast 38 · TMDB" — the provenance of the cast list.** Nothing records where actors came from.
   `applyMeta` fills `actors` from `SyncAPI.SyncResult.actors`
   (`ResultViewModel2.kt:1722`; `SyncResult` at `app/.../syncproviders/SyncAPI.kt:102-131`), which is
   whichever tracker answered — MAL, AniList or Simkl — never named, and never TMDB specifically.
9. **A per-actor image/no-image decision.** Today the whole list is image-or-text, decided from the
   **first** actor alone: `hasActorImages = actors?.firstOrNull()?.actor?.image?.isNotBlank() == true`
   (`ResultViewModel2.kt:277`), and on false `actors` is nulled entirely (`:339`). Board 7a-ii TEXT
   ONLY draws one text-initials avatar beside six photos, which the current data model cannot
   express. The board's own note says so.
10. **An actor identity.** Board 7a-ii FULL CAST implies a person you can open. `Actor` is
    `(name, image)` only (`MainAPI.kt:1534-1537`) — no id, no url.
11. **A voice-actor column.** `ActorData.voiceActor` exists (`MainAPI.kt:1549`) and is drawn in FULL
    CAST, but no screen reads it and the panel is "specified here, not yet drawn" by the board's own
    admission.
12. **"No playable source" for a movie.** Board 7b EMPTY states the gap and it is real:
    `noEpisodesFoundText` is non-null only for series and anime
    (`ResultViewModel2.kt:396-399`). `comingSoon` (`MainAPI.kt:2454`) is the nearest thing and means
    something else to a provider.
13. **"Still loading details from AniList" (7a PARTIAL).** No state says metadata backfill is in
    flight. `setMeta` is pushed in from outside (`ResultViewModel2.kt:1846`) and has exactly **one**
    caller — `ResultFragmentPhone.kt:1137`. **`ResultFragmentTv` never calls it**, so today the TV
    page gets no backfill at all. A PARTIAL state would need both the plumbing and a signal.
14. **"Resume from 1:24:07" (7b).** The seconds exist (`ResumeProgress.progress`,
    `ResultViewModel2.kt:2471`); the clock format does not — the TV button says the bare word
    `R.string.resume` for a movie (`ResultFragmentTv.kt:518`). Formatting work, not missing data.
15. **"Start over" (7b).** No argument exists to pass a zero start position:
    `GeneratorPlayer.newInstance` has none (`GeneratorPlayer.kt:151-164`) and the start position is
    computed internally from `DataStoreHelper` (`GeneratorPlayer.kt:247-254`, `:534-536`). See §5 —
    this needs a decision, either a storage write or a new player argument.
16. **"Download" in the movie action row, with Queued / Downloading 42 % / Downloaded in one
    288 box (7b).** The download system exists and the TV page wires it for **episode rows**
    (`ResultFragmentTv.kt:437-439`) but there is no title-level download control and no download-state
    field on anything in `:feature:detail`.
17. **The whole of 7c "Link loading".** Board draws, per extractor, a named row with a state, a
    quality, a failure reason and a cached/fresh label, plus the aggregate counters
    ("18 sources · 4 answered", "1 × Cloudflare challenge (403)", "1 × timed out at 120 s",
    "48 s elapsed · 120 s provider budget", "Wi-Fi profile · 4 links hidden", "BEST LINK SO FAR").
    All that exists is `LinkProgress(linksLoaded: Int, subsLoaded: Int)`
    (`ResultViewModel2.kt:421-424`, posted `:1318`) — two counts, no identities, no per-source state,
    no reasons, **no total**, because `loadLinks` is a single opaque `Boolean` return
    (`core/.../APIRepository.kt:214-229`; `MainAPI.loadLinks` at `MainAPI.kt:692-699`) and the only
    structure is the `ExtractorLink` callback. "48 s elapsed / 120 s budget" is computable from
    `getTimeout(api.loadLinksTimeoutMs)` (`core/.../APIRepository.kt:61-63`) but is not surfaced.
    `StreamLink` (`DetailContracts.kt:46-54`) models a *successful* link only; there is no failed-row
    type at all.
18. **"replayed cache rows are labelled, never faked as fresh" (7c).** `RepoLinkGenerator` has a
    `(apiName, id)` cache (`RepoLinkGenerator.kt:30-31`, `:53-62`) but nothing distinguishes a cached
    link from a fresh one downstream.
19. **7d player-HUD readouts** — "1080p · AVC · English 5.1", the DOWNLOADED badge, "Up next · S1:E5
    <title>", "Skip intro", the buffered/played three-band track. Out of scope for a detail page, but
    none of it is on `TitleDetail` or `StreamLink` either.
20. **7e selector verbs** — "Switching keeps your position: resumes at 42:13 · brief rebuffer".
    No data models the position-preservation guarantee.

---

## Smallest safe seam

### Recommendation

An `internal class AndroidDetailSource` in
`app/src/main/java/com/lagradost/cloudstream4/detail/AndroidDetailSource.kt`, modelled on
`ApiRepositorySearchSource` and reusing its id codec:

```kotlin
internal class AndroidDetailSource(
    private val context: Context,          // application context — see ApiRepositorySearchSource.kt:53-61
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DetailSource {
    override suspend fun details(id: String): TitleDetail
    override fun links(id: String, data: String): Flow<List<StreamLink>>
}
```

`details(id)` — entirely headless:

1. `decodeId(SearchResultId(id))` → `SearchResultTarget(apiName, url)`
   (`app/.../search/ApiRepositorySearchSource.kt:390-402`). **Reuse it**, do not invent a second
   format; the KDoc at `:368-385` gives the reason the format is what it is. If `:feature:detail`
   should not depend on the search id, lift `encodeId`/`decodeId` into a shared `:app` file in the
   same PR rather than copying them.
2. `APIHolder.getApiFromNameNull(apiName) ?: APIHolder.getApiFromUrlNull(url)`
   (as `ResultViewModel2.load` does, `:2671`) → throw on null.
3. `APIRepository(api).load(url)` (`core/.../APIRepository.kt:95`). Build the repository per call,
   for the staleness reason at `app/.../ApiRepositorySearchSource.kt:63-70`.
4. `Resource.Failure` → throw (the seam reports failure by throwing,
   `DetailContracts.kt:56-58`); `Resource.Success` → map.
5. Map `LoadResponse` → `TitleDetail` directly. **Do not** call `toResultData`
   (`ResultViewModel2.kt:272`): every field it produces is a `UiText` and the type/plot/duration
   decisions it bakes in are XML-layout decisions.
6. Resolve the handful of display strings that genuinely need `Resources` — the type label, the
   quality label — through a per-call `labelContext()`, copied from
   `app/.../ApiRepositorySearchSource.kt:207-212` (the `Locale.setDefault` reasoning at `:194-206`
   applies identically here).
7. Group episodes by season **in the source**, from `Episode.season` /
   `EpisodeResponse.seasonNames`, rather than reproducing `postEpisodes`' id arithmetic
   (`ResultViewModel2.kt:2218-2405`). But **do** reuse the stable episode id scheme if the page needs
   to read resume positions — `getViewPos` is keyed on exactly those ids
   (`buildResultEpisode`, `ResultFragment.kt:104`).
8. Strip markup from `plot` and each `description`, as
   `DesktopDetailSource.withoutMarkup()` does (`DesktopDetailSource.kt:79-80`).

`links(id, data)` — the one part that is **not** cleanly headless:

`RepoLinkGenerator.generateLinks` is callback-shaped and lives in `:app`'s player package
(`RepoLinkGenerator.kt:43-50`), and it takes `List<ResultEpisode>` (`:25`), which means a
`ResultEpisode` has to be built. The honest options are:

- **(a)** build a one-episode `RepoLinkGenerator(listOf(resultEpisode))` and wrap
  `generateLinks` in a `callbackFlow`, emitting the accumulated list — mirroring
  `ResultViewModel2.loadLinks` (`:1305-1352`) and `DesktopDetailSource.toStreamLinks`
  (`DesktopDetailSource.kt:102-119`), including the "append only, never reorder" guarantee that
  `DetailSource.links`' KDoc promises (`DetailContracts.kt:62-67`);
- **(b)** call `APIRepository.loadLinks(data, isCasting, subtitleCallback, callback)` directly
  (`core/.../APIRepository.kt:214-229`) and skip `RepoLinkGenerator` entirely — simpler, headless,
  but loses the generator's cache and the `canSkipLoading` behaviour.

**(b) is the smaller seam** and is what "smallest safe" means here; (a) is the one that matches
today's behaviour. Either way, note that Android has **no `SandboxPolicy` equivalent** — desktop
refuses non-public link targets (`DesktopDetailSource.kt:122-123`) and `:app` does not, so the
`StreamLink` KDoc's "the host has already refused any URL it will not play"
(`DetailContracts.kt:39`) is not true on Android today. That is a decision to raise, not to
quietly assume.

### What it should call

| Need | Call | Where |
|---|---|---|
| decode the id | `decodeId(SearchResultId)` | `app/.../search/ApiRepositorySearchSource.kt:390` |
| find the provider | `APIHolder.getApiFromNameNull` / `getApiFromUrlNull` | used at `ResultViewModel2.kt:2671` |
| load the title | `APIRepository(api).load(url)` | `core/.../APIRepository.kt:95` |
| in-app-language labels | per-call `createConfigurationContext` | pattern at `ApiRepositorySearchSource.kt:207-212` |
| season names | `EpisodeResponse.seasonNames` + `seasonToTxt` | `MainAPI.kt:2237`; `ResultViewModel2.kt:627-644` |
| stable episode ids | the arithmetic in `postEpisodes` | `ResultViewModel2.kt:2227-2229`, `:2284-2285` |
| resume position | `DataStoreHelper.getViewPos(id)` (+ `fixVisual()` for display only) | `DataStoreHelper.kt:745`, `:241` |
| last watched episode | `DataStoreHelper.getLastWatched(parentId)` | `DataStoreHelper.kt:592` |
| watch status | `getResultWatchState(id)` / `setResultWatchState(id, WatchType.x.internalId)` + `setBookmarkedData` | `DataStoreHelper.kt:783`, `:774`, `:608` |
| favourite | `getFavoritesData(id)` / `setFavoritesData` / `removeFavoritesData` | `DataStoreHelper.kt:678`, `:672`, `:666` |
| notify the library | `MainActivity.bookmarksUpdatedEvent(true)`, `reloadLibraryEvent(true)` | `MainActivity.kt:263`, `:273` |
| links | `APIRepository.loadLinks(...)` or a one-episode `RepoLinkGenerator` | `core/.../APIRepository.kt:214`; `RepoLinkGenerator.kt:43` |
| play | `GeneratorPlayer.newInstance(generator, index, syncData)` + `navigate(R.id.global_to_navigation_player)` | `GeneratorPlayer.kt:151`; `ResultViewModel2.kt:1602-1607` |
| trailer | `loadExtractor` per `TrailerData` → `ExtractorLinkGenerator(links, emptyList())` → same navigation | `ResultViewModel2.kt:2489-2541`; `ResultFragmentTv.kt:555-565` |
| open another title | `AppContextUtils.loadResult(url, apiName, name)` | `AppContextUtils.kt:711` |

### Things it should not do

- Do not take an `Activity`. Navigation belongs in the fragment's `onEffect`
  (`SearchTvFragment.kt:158-177`), not in the source.
- Do not hold an Activity `Context` — a `DetailSource` outlives the fragment for as long as the
  ViewModel does (`ApiRepositorySearchSource.kt:59-61`).
- Do not emit `UiText`. Resolve to `String` in the source, as `SearchResultItem` does
  (`ApiRepositorySearchSource.kt:252-262`).
- Do not touch `ResultViewModel2`, `ResultFragmentTv` or `ResultFragmentPhone`. They stay live until
  the detail vertical is complete, and `.claude/rules/no-unsolicited-changes.md` applies.
- Do not pass HTML to Compose.
- Re-run `./gradlew :app:dumpExtensionApi` and commit the diff in the same PR (§9).
