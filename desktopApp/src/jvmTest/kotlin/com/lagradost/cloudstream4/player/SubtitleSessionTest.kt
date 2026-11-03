package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream4.media.model.SubtitleCue
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.media.subtitle.SubtitleParseResult
import com.lagradost.cloudstream4.media.subtitle.SubtitleTimeline
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SubtitleSessionTest {
    private val first = SubtitleTrack(SubtitleSource.Remote("https://a/1"), "one")
    private val second = SubtitleTrack(SubtitleSource.Remote("https://a/2"), "two")
    private val ready = SubtitleLoad.Ready(SubtitleTimeline(listOf(SubtitleCue(0, 1, listOf("x")))), SubtitleParseResult(emptyList(), null), 1)

    private var crashes = 0
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> crashes++ },
    )

    private val spanish = SubtitleTrack(SubtitleSource.Remote("https://a/es"), "Spanish", "es")
    private val english = SubtitleTrack(SubtitleSource.Remote("https://a/en"), "English", "en")
    private val englishToo = SubtitleTrack(SubtitleSource.Remote("https://a/en2"), "English 2", "en")

    private class Saved(var value: String = SubtitlePreference.DEFAULT) : SubtitlePreference {
        val writes = ArrayList<String>()

        override fun language() = value

        override fun remember(language: String) {
            value = language
            writes += language
        }
    }

    private fun session(loader: SubtitleLoader) = SubtitleSession(listOf(first, second), loader, scope)

    private fun arriving(saved: SubtitlePreference, tracks: List<SubtitleTrack> = emptyList()) =
        SubtitleSession(tracks, { ready }, scope, saved)

    private fun SubtitleSession.showing() = (selection.value as? SubtitleSelection.Showing)?.track

    @Test
    fun `a track found after the pick joins the list, at its end, once`() {
        val session = arriving(SubtitlePreference.Never, listOf(spanish))
        session.offer(listOf(spanish, english))
        session.offer(listOf(spanish, english, english))
        assertEquals(listOf(spanish, english), session.tracks.value)
        assertEquals(SubtitleSelection.Off, session.selection.value)
    }

    @Test
    fun `the preferred language turns on when it arrives, in Android's order`() {
        val session = arriving(Saved(), listOf(spanish))
        assertEquals(SubtitleSelection.Off, session.selection.value)
        session.offer(listOf(spanish, englishToo, english))
        assertEquals(english, session.showing())
    }

    @Test
    fun `the preferred language turns on straight away if it is already here`() {
        assertEquals(english, arriving(Saved(), listOf(spanish, english)).showing())
    }

    @Test
    fun `a later arrival never replaces what is on`() {
        val session = arriving(Saved(), listOf(englishToo))
        session.offer(listOf(englishToo, english))
        assertEquals(englishToo, session.showing())
    }

    @Test
    fun `a person's choice is not undone by what arrives after it`() {
        val saved = Saved()
        val session = arriving(saved, listOf(spanish))
        session.select(spanish)
        session.offer(listOf(spanish, english))
        assertEquals(spanish, session.showing())
        assertEquals(listOf("es"), saved.writes)
    }

    @Test
    fun `choosing Off is remembered, and nothing turns on after it`() {
        val saved = Saved()
        val session = arriving(saved, listOf(english))
        session.select(null)
        assertEquals(listOf(SubtitlePreference.NONE), saved.writes)
        session.offer(listOf(english, englishToo))
        assertEquals(SubtitleSelection.Off, session.selection.value)
        assertEquals(SubtitleSelection.Off, arriving(saved, listOf(english)).selection.value, "nor in the next stream")
    }

    @Test
    fun `Off when already off says nothing, so the preferred language can still arrive`() {
        val saved = Saved()
        val session = arriving(saved)
        session.select(null)
        assertEquals(emptyList(), saved.writes)
        session.offer(listOf(english))
        assertEquals(english, session.showing())
    }

    @Test
    fun `a track with no language keeps the language already preferred`() {
        val saved = Saved()
        arriving(saved, listOf(first)).select(first)
        assertEquals(emptyList(), saved.writes)
        assertEquals(SubtitlePreference.DEFAULT, saved.value)
    }

    @Test
    fun `a preferred track that fails does not make the next arrival turn on`() {
        val saved = Saved()
        val session = SubtitleSession(listOf(english), { SubtitleLoad.Failed(SubtitleFailure.Fetch) }, scope, saved)
        assertIs<SubtitleSelection.Failed>(session.selection.value)
        session.offer(listOf(english, englishToo))
        assertEquals(english, assertIs<SubtitleSelection.Failed>(session.selection.value).track)
    }

    @Test
    fun `it starts off`() {
        assertEquals(SubtitleSelection.Off, session { ready }.selection.value)
    }

    @Test
    fun `a chosen track loads and shows`() {
        val session = session { ready }
        session.select(first)
        assertEquals(first, assertIs<SubtitleSelection.Showing>(session.selection.value).track)
    }

    @Test
    fun `a failure is the state and the track remembers it, until it is tried again`() {
        var answer: SubtitleLoad = SubtitleLoad.Failed(SubtitleFailure.NoCues)
        val session = session { answer }
        session.select(first)
        assertEquals(SubtitleFailure.NoCues, assertIs<SubtitleSelection.Failed>(session.selection.value).reason)
        assertEquals(mapOf(first.id to SubtitleFailure.NoCues), session.failures.value)

        session.select(null)
        assertEquals(SubtitleSelection.Off, session.selection.value)
        assertEquals(mapOf(first.id to SubtitleFailure.NoCues), session.failures.value)

        answer = ready
        session.select(first)
        assertIs<SubtitleSelection.Showing>(session.selection.value)
        assertEquals(emptyMap(), session.failures.value)
    }

    @Test
    fun `a slow file cannot replace a later choice`() {
        val slow = CompletableDeferred<SubtitleLoad>()
        val session = session { track -> if (track == first) slow.await() else ready }
        session.select(first)
        assertIs<SubtitleSelection.Loading>(session.selection.value)
        session.select(second)
        slow.complete(SubtitleLoad.Failed(SubtitleFailure.Fetch))
        assertEquals(second, assertIs<SubtitleSelection.Showing>(session.selection.value).track)
        assertEquals(emptyMap(), session.failures.value)
    }

    @Test
    fun `turning it off cancels the fetch`() {
        val slow = CompletableDeferred<SubtitleLoad>()
        val session = session { slow.await() }
        session.select(first)
        session.select(null)
        slow.complete(ready)
        assertEquals(SubtitleSelection.Off, session.selection.value)
    }

    @Test
    fun `choosing what is already on does not fetch it again`() {
        var loads = 0
        val session = session {
            loads++
            ready
        }
        session.select(first)
        session.select(first)
        assertEquals(1, loads)
    }

    @Test
    fun `a loader that throws anyway is a failed track and not a crashed scope`() {
        val session = session { throw IOException("boom") }
        session.select(first)
        assertEquals(SubtitleFailure.Fetch, assertIs<SubtitleSelection.Failed>(session.selection.value).reason)
        assertEquals(0, crashes)
    }
}
