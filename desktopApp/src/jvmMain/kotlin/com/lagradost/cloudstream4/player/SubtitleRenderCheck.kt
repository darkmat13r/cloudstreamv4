package com.lagradost.cloudstream4.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.detail.DesktopDetailSource
import com.lagradost.cloudstream4.detail.DesktopDetailViewModels
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.bestFirst
import com.lagradost.cloudstream4.extensions.SandboxedExtensions
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.search.DesktopSearchSource
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat

/**
 * `--check-subtitle-render <out> <width>x<height> <video> <cues>`, or `... plugin:<provider>:<query>`, which
 * waits for the plugin to finish, or with `--first-link` takes the first link it offers, as someone clicking it would.
 * With `--keep-searching` as well the search carries on behind that link and nothing is chosen by hand, so it is the
 * preferred language arriving that has to put a cue on screen.
 *
 * With `--best-first` instead, it is the app's own start (`KMP-39`): the real `DetailViewModel` waits as the window
 * does and plays the best link found, and the screen moves on by itself from any that fail, through more silent hosts,
 * until one plays. It prints which qualities were found, chosen, failed and played; never a URL. With `--break-first`
 * too, the link it chose is swapped for a public address that answers 404, so the real host fails and moving on is
 * what has to make it play.
 *
 * The real screen over the real sandboxed libVLC, in an offscreen scene: frames from the media host, a track
 * chosen with real key events through the real list, PNGs of what came out. It exists because the window
 * cannot be driven from a locked session, and a test cannot say whether a cue meets a real frame on time.
 *
 * **Silent, provably:** `--no-audio`, and the sandbox refuses the host every audio service. See
 * [VlcPlayer.createOrNull]. A plugin's separate audio stream is not even passed on.
 *
 * With a video whose frames carry their own number in a strip along the top (the command is in `KMP-25`), it
 * also measures how far the shown cue's clock is from the picture, through play, pause, seek and resume.
 *
 * @return 0 when a cue was on screen and, with a coded video, within [SYNC_BOUND_MILLIS] at the 95th percentile.
 */
internal fun runSubtitleRenderCheck(args: List<String>): Int {
    System.setProperty("java.awt.headless", "true")
    val out = File(args[0]).apply { mkdirs() }
    val (width, height) = args[1].split('x').map(String::toInt)
    val target = args[2]
    if (!target.startsWith(PLUGIN_PREFIX)) {
        val player = VlcPlayer.createOrNull(silent = true) ?: return NO_PLAYER
        val cues = File(args[3])
        val track = SubtitleTrack(SubtitleSource.Local(cues.path), label = cues.name)
        val link = StreamLink("check", null, player.stage(File(target)), null, null, null, listOf(track))
        val loader = SubtitleLoader { HttpSubtitleLoader.parse(cues.readBytes(), null, null) }
        return Run(player, link, loader, out, width, height, coded = true).execute(trackRow = 1)
    }
    val (provider, query) = target.removePrefix(PLUGIN_PREFIX).split(':', limit = 2)
    return runBlocking {
        val extensions = SandboxedExtensions()
        try {
            extensions.loadInstalled()
            val source = extensions.providers.firstOrNull { it.name == provider } ?: return@runBlocking NO_LINK
            val first = source.search(query, 1).items.firstOrNull() ?: return@runBlocking NO_LINK
            val id = DesktopSearchSource.encodeId(source.name, first.url)
            val details = source.details(first.url)
            val data = details.dataUrl ?: details.episodes.firstOrNull()?.data ?: return@runBlocking NO_LINK
            if (BEST_FIRST_ARGUMENT in args) {
                return@runBlocking bestFirst(id, data, out, width, height, broken = BREAK_FIRST_ARGUMENT in args)
            }
            val answers = DesktopDetailSource().links(id, data)
            if (KEEP_SEARCHING_ARGUMENT in args) {
                val found = MutableStateFlow(emptyList<StreamLink>())
                val search = launch(Dispatchers.IO) { answers.collect { found.value = it } }
                val link = found.first { it.isNotEmpty() }.first()
                println("render: picked the first link with ${link.subtitles.size} tracks")
                val player = VlcPlayer.createOrNull(silent = true) ?: return@runBlocking NO_PLAYER
                val run = Run(
                    player, link.copy(audioUrl = null), HttpSubtitleLoader(), out, width, height, coded = false,
                    preference = InMemoryEnglish, arriving = found.map { it.first().subtitles },
                )
                return@runBlocking withContext(Dispatchers.Default) { run.executeArriving() }.also { search.cancel() }
            }
            val answer = if (FIRST_LINK_ARGUMENT in args) answers.firstOrNull() else answers.lastOrNull()
            val link = answer?.firstOrNull() ?: return@runBlocking NO_LINK
            val row = link.subtitles.indexOfFirst { it.languageTag?.startsWith("en") == true }.coerceAtLeast(0) + 1
            println("render: ${link.subtitles.size} tracks, choosing row $row, link ${link.qualityLabel}")
            val player = VlcPlayer.createOrNull(silent = true) ?: return@runBlocking NO_PLAYER
            Run(player, link.copy(audioUrl = null), HttpSubtitleLoader(), out, width, height, coded = false).execute(row)
        } finally {
            extensions.close()
        }
    }
}

/** Through the window's own path from a press of Play: the wait, the best link, and moving on from failures. */
private suspend fun bestFirst(
    id: String,
    data: String,
    out: File,
    width: Int,
    height: Int,
    broken: Boolean,
): Int = coroutineScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Every link without its separate audio stream, consistently, so the player's test for one stream still holds.
    fun List<StreamLink>.silent() = map { it.copy(audioUrl = null) }
    val model = DetailViewModel(id, DesktopDetailSource(), scope, DesktopDetailViewModels.BEST_LINK_WAIT)
    val asked = TimeSource.Monotonic.markNow()
    model.onAction(DetailAction.PlayRequested(data))
    val play = withTimeoutOrNull(BEST_FIRST_LIMIT) { model.effect.filterIsInstance<DetailEffect.Play>().first() }
    if (play == null) {
        scope.cancel()
        return@coroutineScope NO_LINK
    }
    val found = model.state.map { (it.links as? DetailLoad.Loaded)?.value.orEmpty().silent() }
    val atStart = found.first()
    println(
        "render: played ${play.link.quality}p after ${asked.elapsedNow().inWholeMilliseconds} ms, best of " +
            "${atStart.size} found then: ${atStart.bestFirst().take(QUALITIES_SHOWN).map { it.quality }}",
    )
    val first = if (broken) play.link.copy(url = BROKEN_URL, audioUrl = null) else play.link.copy(audioUrl = null)
    val player = VlcPlayer.createOrNull(silent = true) ?: return@coroutineScope NO_PLAYER.also { scope.cancel() }
    val switcher = SourceSwitcher(first)
    val run = Run(
        player, first, HttpSubtitleLoader(), out, width, height, coded = false,
        links = found, openPlayer = { VlcPlayer.createOrNull(silent = true) }, switcher = switcher,
    )
    withContext(Dispatchers.Default) { run.executeBestFirst() }.also { scope.cancel() }
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
private class Run(
    private val player: VlcPlayer,
    private val link: StreamLink,
    private val loader: SubtitleLoader,
    private val out: File,
    private val width: Int,
    private val height: Int,
    private val coded: Boolean,
    preference: SubtitlePreference = SubtitlePreference.Never,
    private val arriving: Flow<List<SubtitleTrack>> = emptyFlow(),
    links: Flow<List<StreamLink>> = emptyFlow(),
    openPlayer: (() -> VlcPlayer?)? = null,
    private val switcher: SourceSwitcher = SourceSwitcher(link),
) {
    private val clock = PlaybackClock()
    private val start = System.nanoTime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = SubtitleSession(link.subtitles, loader, scope, preference)
    private val scene = ImageComposeScene(width, height, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                PlayerScreen(
                    player, "Check", link, onBack = {}, clock = clock, session = session, arrivingSubtitles = arriving,
                    links = links, openPlayer = openPlayer, switcher = switcher,
                )
            }
        }
    }
    private var lastCoded = -1
    private val samples = HashMap<String, MutableList<Long>>()
    private var phase = "start"

    /**
     * Waits for a link to play: the clock passing [PLAYED_MILLIS], whichever link it is, with the screen moving on from
     * any that fail. @return 0 when one did.
     */
    fun executeBestFirst(): Int {
        pump(until = { clock.positionMillis() > PLAYED_MILLIS }, seconds = BEST_FIRST_PLAY_SECONDS)
        val played = clock.positionMillis() > PLAYED_MILLIS
        shot(if (played) "best-first-playing" else "best-first-failed")
        println(
            "render: started on ${link.quality}p; moved on ${switcher.fellThrough?.serial ?: 0} times; " +
                "failed ${switcher.failed.map { it.quality }}; now on ${switcher.current.quality}p; " +
                "position=${clock.positionMillis()} ms; played=$played",
        )
        scene.close()
        scope.cancel()
        // Closing the scene released whichever host was playing. This is the first, in case the screen never left it.
        player.release()
        return if (played) 0 else FAILED
    }

    /** @return 0 when a track that was not there at the pick has turned itself on and put a cue on screen. */
    fun executeArriving(): Int {
        // The link's own list: the session may have gained some while libVLC was starting.
        val atPick = link.subtitles.size
        pump(until = { player.frame.value != null }, seconds = 30)
        val framed = player.frame.value != null
        pump(until = { session.selection.value is SubtitleSelection.Showing }, seconds = ARRIVING_SECONDS)
        // Past the HUD and the first pictures a stream opens on, then to a cue, then long enough for the screen's own
        // tick to have drawn it: the lookup below answering yes says nothing about the picture yet.
        pumpFor(HUD_GONE_MILLIS)
        pump(until = ::cueOnScreen, seconds = ARRIVING_SECONDS)
        pumpFor(CUE_DRAWN_MILLIS)
        val onScreen = cueOnScreen()
        val turnedOn = when (val selection = session.selection.value) {
            is SubtitleSelection.Showing -> selection.track.label
            is SubtitleSelection.Loading -> "loading ${selection.track.label}"
            is SubtitleSelection.Failed -> "failed ${selection.track.label}: ${selection.reason}"
            SubtitleSelection.Off -> "nothing"
        }
        val shown = onScreen && framed
        shot(if (shown) "arrived" else "arriving-failed")
        println(
            "render: tracks $atPick at the pick, ${session.tracks.value.size} now, frames=$framed, " +
                "turned on: $turnedOn, cue-on-screen=$onScreen, position=${clock.positionMillis()} ms",
        )
        scene.close()
        scope.cancel()
        player.release()
        return if (shown && atPick == 0) 0 else FAILED
    }

    fun execute(trackRow: Int): Int {
        pump(until = { player.frame.value != null }, seconds = 30)
        if (player.frame.value == null) {
            println("render: no frame arrived")
            scene.close()
            scope.cancel()
            player.release()
            return NO_FRAME
        }
        println("render: first frame, ${player.frame.value?.width}x${player.frame.value?.height}")
        shot("0-before-choosing")

        press(Key.C)
        pumpFor(PICKER_SETTLE_MILLIS)
        shot("1-picker")
        repeat(trackRow) {
            press(Key.DirectionDown)
            pumpFor(KEY_GAP_MILLIS)
        }
        shot("2-picker-moved")
        press(Key.Enter)
        pumpFor(PICKER_SETTLE_MILLIS)

        phase = "play"
        // Past the HUD's timeout, so this frame shows cues where they sit with no controls.
        pumpFor(HUD_GONE_MILLIS)
        if (!coded) pump(until = ::cueOnScreen, seconds = 90)
        val onScreen = cueOnScreen()
        shot("3-playing-no-hud")

        moveMouse()
        pumpFor(HUD_SHOWN_MILLIS)
        shot("4-hud-lifted")
        pumpFor(HUD_GONE_MILLIS)

        phase = "paused"
        val pauseAt = clock.positionMillis()
        press(Key.Spacebar)
        pumpFor(PAUSE_MILLIS)
        val drift = clock.positionMillis() - pauseAt
        shot("5-paused")
        pumpFor(PAUSE_MILLIS)
        val held = clock.positionMillis() - pauseAt - drift
        println("render: pause moved the clock by $drift ms as it took effect, then $held ms in ${PAUSE_MILLIS}ms")

        phase = "resumed"
        press(Key.Spacebar)
        pumpFor(RESUME_MILLIS)
        shot("6-resumed")

        phase = "seek"
        val before = clock.positionMillis()
        press(Key.DirectionRight)
        scene.render(System.nanoTime() - start)
        val instant = clock.positionMillis() - before
        println("render: clock moved $instant ms on the same frame as a +${PlayerScreenDefaults.SkipMillis} ms seek")
        val seekStart = System.nanoTime()
        var frameMoved: Long? = null
        val beforeCoded = lastCoded
        while ((System.nanoTime() - seekStart) / NANOS_PER_MILLI < SEEK_WATCH_MILLIS) {
            tick()
            if (frameMoved == null && abs(lastCoded - beforeCoded) > SEEK_FRAMES) {
                frameMoved = (System.nanoTime() - seekStart) / NANOS_PER_MILLI
            }
        }
        if (coded) println("render: picture followed the seek after $frameMoved ms")
        shot("7-after-seek")
        phase = "after-seek"
        pumpFor(RESUME_MILLIS)

        val status = player.status.value
        println(
            "render: position=${status.positionMillis} length=${status.lengthMillis} failed=${status.failed} " +
                "cue-on-screen=$onScreen selection=${session.selection.value::class.simpleName}"
        )
        var worst = 0L
        for ((name, values) in samples.toSortedMap()) {
            values.sort()
            if (values.isEmpty()) continue
            val p50 = values[values.size / 2]
            val p95 = values[(values.size * P95).toInt().coerceAtMost(values.lastIndex)]
            println("render: sync $name n=${values.size} |clock-picture| p50=$p50 p95=$p95 max=${values.last()} ms")
            if (name == "play" || name == "resumed" || name == "after-seek") worst = maxOf(worst, p95)
        }
        scene.close()
        scope.cancel()
        player.release()
        val synced = !coded || worst <= SYNC_BOUND_MILLIS
        return if (onScreen && synced && !status.failed) 0 else FAILED
    }

    /** What the screen is drawing right now: the same lookup at the same clock. */
    private fun cueOnScreen(): Boolean {
        val showing = session.selection.value as? SubtitleSelection.Showing ?: return false
        return showing.timeline.activeAt(clock.positionMillis()).isNotEmpty()
    }

    private fun press(key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }

    /** Twice, because a scene's first pointer event is an arrival and not a move. */
    private fun moveMouse() {
        scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f, height / 2f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f + 1f, height / 2f))
    }

    private fun pumpFor(millis: Long) {
        val until = System.nanoTime() + millis * NANOS_PER_MILLI
        while (System.nanoTime() < until) tick()
    }

    private fun pump(until: () -> Boolean, seconds: Int) {
        val limit = System.nanoTime() + seconds * NANOS_PER_SECOND
        while (!until() && System.nanoTime() < limit) tick()
    }

    private fun tick() {
        scene.render(System.nanoTime() - start)
        if (coded) sample()
        Thread.sleep(TICK_MILLIS)
    }

    /** Whenever a new picture shows, how far the clock the cues read from is from that picture's own time. */
    private fun sample() {
        val image = player.frame.value ?: return
        val number = decode(image)
        if (number == lastCoded) return
        lastCoded = number
        val pictureMillis = number * MILLIS_PER_SECOND / VIDEO_FPS
        samples.getOrPut(phase) { mutableListOf() } += abs(clock.positionMillis() - pictureMillis)
    }

    private fun decode(image: ImageBitmap): Int {
        val row = IntArray(image.width)
        image.readPixels(row, startX = 0, startY = CODE_ROW, width = image.width, height = 1)
        var value = 0
        for (bit in 0 until CODE_BITS) {
            val pixel = row[bit * CODE_BLOCK + CODE_BLOCK / 2]
            if ((pixel shr GREEN_SHIFT) and BYTE_MASK > LUMA_THRESHOLD) value = value or (1 shl bit)
        }
        return value
    }

    private fun shot(name: String) {
        val image = scene.render(System.nanoTime() - start)
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "no PNG" }
        File(out, "$name-${width}x$height.png").writeBytes(data.bytes)
    }

    private companion object {
        const val ARRIVING_SECONDS = 60
        const val CUE_DRAWN_MILLIS = 300L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MILLIS_PER_SECOND = 1000
        const val TICK_MILLIS = 8L
        const val PICKER_SETTLE_MILLIS = 800L
        const val KEY_GAP_MILLIS = 150L
        const val HUD_GONE_MILLIS = 5_000L
        const val HUD_SHOWN_MILLIS = 800L
        const val PAUSE_MILLIS = 1_500L
        const val RESUME_MILLIS = 3_000L
        const val SEEK_WATCH_MILLIS = 3_000L
        const val SEEK_FRAMES = 120
        const val P95 = 0.95
        const val VIDEO_FPS = 30
        const val CODE_ROW = 8
        const val CODE_BITS = 16
        const val CODE_BLOCK = 40
        const val GREEN_SHIFT = 8
        const val BYTE_MASK = 0xFF
        const val LUMA_THRESHOLD = 128
    }
}

private const val PLUGIN_PREFIX = "plugin:"
private const val FIRST_LINK_ARGUMENT = "--first-link"
private const val KEEP_SEARCHING_ARGUMENT = "--keep-searching"
private const val BEST_FIRST_ARGUMENT = "--best-first"
private const val BREAK_FIRST_ARGUMENT = "--break-first"

/** Public, so the URL policy lets the host try it, and reserved for examples, so it serves no video. */
private const val BROKEN_URL = "https://example.com/kmp-39-no-such-video.mp4"
private val BEST_FIRST_LIMIT = 120.seconds
private const val BEST_FIRST_PLAY_SECONDS = 120
private const val PLAYED_MILLIS = 3_000L
private const val QUALITIES_SHOWN = 8
private const val SYNC_BOUND_MILLIS = 100L

/** What a fresh install has, without writing to the real preference from a check. */
private object InMemoryEnglish : SubtitlePreference {
    override fun language() = SubtitlePreference.DEFAULT

    override fun remember(language: String) = Unit
}

private const val NO_PLAYER = 2
private const val NO_LINK = 3
private const val NO_FRAME = 4
private const val FAILED = 1
