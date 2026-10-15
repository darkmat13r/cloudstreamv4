package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.syncproviders.providers.Addic7ed
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi
import com.lagradost.cloudstream3.syncproviders.providers.LocalList
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubSourceApi
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.videoskip.AnimeSkipAuth
import java.util.concurrent.TimeUnit

abstract class AccountManager {
    companion object {
        const val NONE_ID: Int = -1
        val malApi = MALApi()
        val kitsuApi = KitsuApi()
        val aniListApi = AniListApi()
        val simklApi = SimklApi()
        val localListApi = LocalList()

        val openSubtitlesApi = OpenSubtitlesApi()
        val addic7ed = Addic7ed()
        val subDlApi = SubDlApi()
        val subSourceApi = SubSourceApi()
        val animeSkipApi = AnimeSkipAuth()

        var cachedAccounts: MutableMap<String, Array<AuthData>>
        var cachedAccountIds: MutableMap<String, Int>

        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"

        fun accounts(prefix: String): Array<AuthData> {
            require(prefix != "NONE")
            return getKey<Array<AuthData>>(
                ACCOUNT_TOKEN,
                "${prefix}/${DataStoreHelper.currentAccount}"
            ) ?: arrayOf()
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>) {
            require(prefix != "NONE")
            setKey(ACCOUNT_TOKEN, "${prefix}/${DataStoreHelper.currentAccount}", array)
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
            }
        }

        fun updateAccountsId(prefix: String, id: Int) {
            require(prefix != "NONE")
            setKey(ACCOUNT_IDS, "${prefix}/${DataStoreHelper.currentAccount}", id)
            synchronized(cachedAccountIds) {
                cachedAccountIds[prefix] = id
            }
        }

        val allApis = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi),
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi),
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi),
            PlainAuthRepo(animeSkipApi),
            SubtitleRepo(subSourceApi)
        )

        fun updateAccountIds() {
            val ids = mutableMapOf<String, Int>()
            for (api in allApis) {
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
            }
            synchronized(cachedAccountIds) {
                cachedAccountIds = ids
            }
        }

        init {
            val data = mutableMapOf<String, Array<AuthData>>()
            val ids = mutableMapOf<String, Int>()
            for (api in allApis) {
                data.put(api.idPrefix, accounts(api.idPrefix))
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
            }
            cachedAccounts = data
            cachedAccountIds = ids
        }

        // I do not want to place this in the init block as JVM initialization order is weird, and it may cause exceptions
        // accessing other classes
        fun initMainAPI() {
            LoadResponse.malIdPrefix = malApi.idPrefix
            LoadResponse.kitsuIdPrefix = kitsuApi.idPrefix
            LoadResponse.aniListIdPrefix = aniListApi.idPrefix
            LoadResponse.simklIdPrefix = simklApi.idPrefix
        }

        val subtitleProviders = arrayOf(
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi),
            SubtitleRepo(subSourceApi)
        )
        val syncApis = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi),
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi)
        )

        const val APP_STRING = "cloudstreamapp"
        const val APP_STRING_REPO = "cloudstreamrepo"
        const val APP_STRING_PLAYER = "cloudstreamplayer"

        // Instantly start the search given a query
        const val APP_STRING_SEARCH = "cloudstreamsearch"

        // Instantly resume watching a show
        const val APP_STRING_RESUME_WATCHING = "cloudstreamcontinuewatching"

        const val APP_STRING_SHARE = "csshare"

        /**
         * A span as `3d 4h 0m` / `2h 22m` / `45s`, in English unit letters **on purpose**.
         *
         * `I18N-02` asked whether this should emit the translated units the app already owns. It
         * should not, and what decides it is the signature rather than the strings:
         *
         * - It is published: `app/api/classes-jar.api:16485` carries
         *   `secondsToReadable (ILjava/lang/String;)Ljava/lang/String;`. An extension may be
         *   embedding this result in a sentence of its own, or parsing it back; changing the glyphs
         *   is a behaviour change for every caller that the ABI dump cannot see.
         * - `completedValue` is a `String` the caller has already resolved. Translating the body
         *   while that tail stays whatever the caller passed is worse than being uniformly English.
         * - The only `Context` a `companion object` in `:app` can reach is
         *   `com.lagradost.api.getContext()` (`ContextHelper.android.kt:12`), a `WeakReference` whose
         *   own KDoc says not to use it unless absolutely necessary. It yields null once the activity
         *   is collected, so a translated path would quietly fall back to English in the field while
         *   passing every test: `SecondsToReadableTest` and `RunTimeSecondsTest` are JVM tests, where
         *   it is null always, and they pin this exact output.
         * - Coverage is magnitude-dependent, so one function would be translated or not depending on
         *   its argument. Measured over the 62 `values*` directories of
         *   `core/strings/src/commonMain/composeResources`: `next_episode_time_day_format` is in 60
         *   (`lt` and `ti` fall back), `next_episode_time_hour_format` in 59, and a sub-minute span
         *   has no sibling in that family at all — the nearest, `download_time_left_sec_format`,
         *   is in 41.
         *
         * **The translation belongs at the two call sites that show this to a user**, not here:
         * `EpisodeAdapter.kt:303` and `ResultViewModel2.kt:2423`, which already build a `txt(...)`.
         * `next_episode_time_day_format` (`%1$dd %2$dh %3$dm`) does carry day granularity — the
         * entry claimed no resource did — so each site can choose day/hour/minute by magnitude and
         * pass the whole positional resource, which is what `apc` forces: it renders
         * `duration_format` as `دقايق: %d`, number last, so anything that concatenates two
         * separately formatted pieces produces garbage there (`I18N-03`).
         *
         * An overload returning a `UiText` was rejected for the reason `TextUtil.kt:22` already
         * records: an adapter written in `:app` lands in `classes.jar` as new frozen API, and that
         * one would be frozen on a type that is itself deprecated, for a caller that does not need
         * it. `BUG-07` — the `minutes < 0` guard is a minutes-within-the-hour remainder test, not a
         * negativity test — is untouched and stays open.
         */
        fun secondsToReadable(seconds: Int, completedValue: String): String {
            var secondsLong = seconds.toLong()
            val days = TimeUnit.SECONDS
                .toDays(secondsLong)
            secondsLong -= TimeUnit.DAYS.toSeconds(days)

            val hours = TimeUnit.SECONDS
                .toHours(secondsLong)
            secondsLong -= TimeUnit.HOURS.toSeconds(hours)

            val minutes = TimeUnit.SECONDS
                .toMinutes(secondsLong)
            secondsLong -= TimeUnit.MINUTES.toSeconds(minutes)
            if (minutes < 0) {
                return completedValue
            }
            // The leftover seconds are already in secondsLong; show them when they
            // are all there is, so the last minute does not render as "0m".
            if (days == 0L && hours == 0L && minutes == 0L && secondsLong > 0L) {
                return "${secondsLong}s"
            }
            //println("$days $hours $minutes")
            return "${if (days != 0L) "$days" + "d " else ""}${if (hours != 0L) "$hours" + "h " else ""}${minutes}m"
        }
    }
}