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

    private fun session(loader: SubtitleLoader) = SubtitleSession(listOf(first, second), loader, scope)

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
