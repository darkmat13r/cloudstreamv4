package com.lagradost.cloudstream4.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.desktop.EpisodeDownloadDesktop
import com.lagradost.cloudstream4.media.model.DownloadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `KMP-109`: the title page's download cell, alone: its words and verbs for each state, and what each verb calls. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class EpisodeDownloadDesktopTest {

    private fun download(state: DownloadEpisodeState, failure: DownloadFailure? = null) =
        DownloadEpisode(DownloadEpisodeId(7), 1, 2, "The Second", state, 400 * MB, 1_000 * MB, failure = failure)

    private inner class Harness(download: DownloadEpisode?, shown: Boolean, private val unavailable: Boolean = false) {
        var download by mutableStateOf(download)
        var shown by mutableStateOf(shown)
        val calls = mutableListOf<String>()
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg), contentAlignment = Alignment.CenterEnd) {
                        EpisodeDownloadDesktop(
                            download = this@Harness.download,
                            shown = this@Harness.shown,
                            formatBytes = { "${it / MB} MB" },
                            onDownload = { calls += "download" },
                            onPause = { calls += "pause" },
                            onResume = { calls += "resume" },
                            onCancel = { calls += "cancel" },
                            unavailable = this@Harness.unavailable,
                        )
                    }
                }
            }
        }
        private var clock = 0L

        fun settle() = repeat(SETTLE_FRAMES) {
            clock += FRAME_NANOS
            scene.render(clock)
        }

        private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            buildList {
                fun walk(node: SemanticsNode) {
                    add(node)
                    node.children.forEach(::walk)
                }
                walk(owner.rootSemanticsNode)
            }
        }

        /** The verbs, by their labels, left to right. */
        fun verbs(): List<String> = nodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { node.boundsInRoot to it } }
            .filter { (_, labels) -> labels.isNotEmpty() }
            .sortedBy { (bounds, _) -> bounds.left }
            .map { (_, labels) -> labels.single() }

        fun bounds(label: String): Rect = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
        }.boundsInRoot

        fun words(): String = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map(AnnotatedString::text)
        }.joinToString(" | ")

        fun press(label: String) {
            val at = bounds(label).center
            val time = clock / NANOS_PER_MILLI
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary, timeMillis = time)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary,
                timeMillis = time)
            settle()
        }
    }

    @Test
    fun `a refused episode says it is not available here, muted, and offers no verb even while shown (part 2b)`() {
        listOf(false, true).forEach { shown ->
            val harness = Harness(download = null, shown = shown, unavailable = true)
            harness.settle()
            assertEquals(emptyList(), harness.verbs(), "no verb, shown=$shown")
            assertEquals("Not available here", harness.words(), "shown=$shown")
            harness.scene.close()
        }
        // A download the service holds is drawn as it is, whatever this page was refused.
        val held = Harness(download(DownloadEpisodeState.Running), shown = true, unavailable = true)
        held.settle()
        assertEquals(listOf("Pause", "Cancel"), held.verbs())
        held.scene.close()
    }

    @Test
    fun `each state offers its verbs while shown, and each verb calls its own`() {
        val cases = listOf(
            null to listOf("Download" to "download"),
            download(DownloadEpisodeState.Queued) to listOf("Cancel" to "cancel"),
            download(DownloadEpisodeState.Running) to listOf("Pause" to "pause", "Cancel" to "cancel"),
            download(DownloadEpisodeState.Paused) to listOf("Resume" to "resume"),
            download(DownloadEpisodeState.Failed, DownloadFailure.NoUsableLink) to listOf("Retry" to "resume"),
            download(DownloadEpisodeState.Done) to emptyList(),
        )
        cases.forEach { (download, expected) ->
            val harness = Harness(download, shown = true)
            harness.settle()
            val state = download?.state ?: "none"
            assertEquals(expected.map { it.first }, harness.verbs(), "verbs for $state")
            expected.forEach { (label, call) ->
                harness.calls.clear()
                harness.press(label)
                assertEquals(listOf(call), harness.calls, "$label on $state")
            }
            harness.scene.close()
        }
    }

    @Test
    fun `while not shown no verb is drawn, and the words keep their place when they appear`() {
        val harness = Harness(download(DownloadEpisodeState.Running), shown = false)
        harness.settle()
        assertEquals(emptyList(), harness.verbs())
        val before = harness.words()
        assertTrue("Downloading" in before, before)
        harness.shown = true
        harness.settle()
        assertEquals(listOf("Pause", "Cancel"), harness.verbs())
        assertEquals(before, harness.words())
        harness.scene.close()
    }

    @Test
    fun `the state reads as D4's words, and an episode with none has no words`() {
        val expected = mapOf(
            DownloadEpisodeState.Queued to "Queued",
            DownloadEpisodeState.Running to "Downloading",
            DownloadEpisodeState.Paused to "Paused",
            DownloadEpisodeState.Failed to "Failed",
            DownloadEpisodeState.Done to "Downloaded",
        )
        expected.forEach { (state, word) ->
            val harness = Harness(download(state, DownloadFailure.NoUsableLink.takeIf { state == DownloadEpisodeState.Failed }), shown = false)
            harness.settle()
            assertTrue(word in harness.words(), "$state reads ${harness.words()}")
            harness.scene.close()
        }
        val none = Harness(null, shown = false)
        none.settle()
        assertEquals("", none.words())
        none.scene.close()
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 80
        const val MB = 1_000_000L
        const val SETTLE_FRAMES = 8
        const val FRAME_NANOS = 16_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
