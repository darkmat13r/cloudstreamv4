package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.ui.APIRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Guards the result-page runtime string produced by [toResultData].
 *
 * Three things are load-bearing here, and the first two were bugs:
 *  - it must come from a translated string resource, never hardcoded English letters;
 *  - it must be computed in minutes, because `duration * 60` overflows [Int] and the
 *    overflow was user-visible as the literal English text "0 mins";
 *  - it must use ONE resource at every magnitude, so no locale renders its own language
 *    at one runtime and English at another.
 */
@RunWith(AndroidJUnit4::class)
class ResultDurationTextTest {
    private val api = object : MainAPI() {}

    private val base: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /** Renders exactly what the result-page TextViews receive, under [tag]'s locale. */
    private fun render(tag: String, minutes: Int?): String? {
        val locale = Locale.forLanguageTag(tag)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        val localised = base.createConfigurationContext(config)

        // UiText.asString formats with Locale.getDefault(), so a faithful render of a
        // device set to this locale has to move both.
        val previous = Locale.getDefault()
        return try {
            Locale.setDefault(locale)
            val response = runBlocking {
                api.newMovieLoadResponse("T", "u", TvType.Movie, "d") {
                    this.duration = minutes
                }
            }
            response.toResultData(APIRepository(api)).durationText?.asString(localised)
        } finally {
            Locale.setDefault(previous)
        }
    }

    private val locales = listOf("en", "ru", "ja", "ar", "apc")
    private val minutes = listOf(1, 45, 59, 60, 61, 120, 142, 1500, 35791395, Int.MAX_VALUE)

    @Test
    fun measureEveryLocale() {
        for (tag in locales) {
            for (m in minutes) {
                Log.i("DURFMT", "$tag | $m | ${render(tag, m)}")
            }
        }
        for (m in listOf(null, 0, -5)) {
            Log.i("DURFMT", "en | $m | ${render("en", m)}")
        }
    }

    @Test
    fun hiddenWhenAbsentOrNonPositive() {
        assertNull(render("en", null))
        assertNull(render("en", 0))
        assertNull(render("en", -5))
    }

    @Test
    fun englishRendersFromTheSharedResources() {
        assertEquals("1 min", render("en", 1))
        assertEquals("45 min", render("en", 45))
        assertEquals("59 min", render("en", 59))
        assertEquals("60 min", render("en", 60))
        assertEquals("61 min", render("en", 61))
        assertEquals("120 min", render("en", 120))
        assertEquals("142 min", render("en", 142))
    }

    /**
     * `duration * 60` overflowed Int and surfaced as the hardcoded English "0 mins".
     * Working in minutes makes the overflow unreachable for every Int.
     */
    @Test
    fun largeRuntimesDoNotOverflow() {
        assertEquals("35791395 min", render("en", 35791395))
        assertEquals("2147483647 min", render("en", Int.MAX_VALUE))
        for (tag in locales) {
            assertNotEquals("0 mins", render(tag, 35791395))
        }
    }

    /**
     * One resource at every magnitude, which is the whole point of the chosen shape.
     *
     * The rejected alternative used `duration_format` under an hour and
     * `next_episode_time_hour_format` over it. That reads better in English, but only 37
     * locale files translate the hour resource against 61 for the minute one, so nine
     * locales — `as`, `bg`, `ckb`, `de`, `hi`, `kn`, `ne`, `zh`, `zh+TW` — would have
     * rendered their own language under an hour and English over it, on the same chip.
     *
     * `zh` is the canary: it translates `duration_format` and does not translate
     * `next_episode_time_hour_format`, so it fails the moment anyone reintroduces a
     * magnitude-dependent branch here.
     */
    @Test
    fun oneResourceAtEveryMagnitude() {
        val english = listOf(45, 142, 1500).map { render("en", it) }
        assertEquals(listOf("45 min", "142 min", "1500 min"), english)

        for (minutes in listOf(45, 142, 1500)) {
            assertNotEquals(
                "zh must not fall back to the English pattern at $minutes minutes",
                render("en", minutes),
                render("zh", minutes),
            )
        }
    }

    /**
     * The point of the fix: locales that translate these resources must no longer be
     * served English. Asserted as "differs from en" so a retranslation upstream cannot
     * break the build over wording we do not own.
     */
    @Test
    fun translatedLocalesNoLongerRenderEnglish() {
        for (tag in listOf("ru", "ja", "ar")) {
            assertNotEquals(tag, render("en", 45), render(tag, 45))
            assertNotEquals(tag, render("en", 142), render(tag, 142))
        }
    }
}
