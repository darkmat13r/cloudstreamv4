package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.detail.isPublic
import com.lagradost.cloudstream4.feature.extensions.sandbox.RemoteSubtitle
import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.home.DesktopHomeSource
import com.lagradost.cloudstream4.media.model.CuePlacement
import com.lagradost.cloudstream4.media.model.SubtitleSource
import com.lagradost.cloudstream4.media.model.SubtitleTrack
import com.lagradost.cloudstream4.player.HttpSubtitleLoader
import com.lagradost.cloudstream4.player.SubtitleLoad
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * `CloudStream --check-extensions [query]`: the whole path, with no window.
 *
 * Loads what is installed, then asks every provider to search, opens the first result and asks for
 * its links. It is how to answer "does this plugin actually work" for a specific plugin, because
 * registering is not working: a provider can load and then fail on its first request.
 *
 * An extension with scan warnings and no Allow for its exact file is printed as held and does not run. There is
 * no flag that changes that, on purpose: a check that bypassed the hold would be the easy way round it.
 *
 * @return the process exit code: 0 when at least one provider returned results.
 */
fun runExtensionCheck(query: String, verbose: Boolean = false): Int = runBlocking {
    val extensions = SandboxedExtensions()
    try {
        extensions.loadInstalled().lines().forEach(::println)
        var working = 0
        for (provider in extensions.providers) {
            val outcome = check(provider, query, verbose)
            if (outcome.foundResults) working++
            println("check: ${provider.name} — ${outcome.summary}")
            println("check: ${provider.name} — ${checkHome(provider)}")
            if (verbose) {
                provider.hostOutput.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("Picked up") }
                    .take(MAX_LOG_LINES)
                    .forEach { println("check:   | ${it.take(MAX_LOG_CHARS)}") }
            }
        }
        println("check: $working of ${extensions.providers.size} providers returned results for \"$query\"")
        // Names, which are all a plugin's connections show from outside: traffic stays end to end.
        extensions.contactedHosts().forEach { (file, hosts) ->
            println("check: $file connected to ${hosts.size} hosts: ${hosts.sorted().take(MAX_HOSTS_SHOWN).joinToString(" ")}")
        }
        if (working > 0) 0 else 1
    } finally {
        extensions.close()
    }
}

/**
 * `CloudStream --scan-extensions`: convert and scan everything installed, and run none of it.
 *
 * @return 0 when nothing was blocked.
 */
fun runExtensionScan(): Int = runBlocking {
    val report = SandboxedExtensions().scanInstalled()
    report.lines().forEach(::println)
    if (report.loads.any { it.scan?.blocked == true || it.scan == null }) 1 else 0
}

/** The first section of the main page, through the path the Home screen uses. Not counted as "working". */
private suspend fun checkHome(provider: SandboxedProvider): String {
    if (!provider.hasMainPage) return "home: no main page"
    val source = DesktopHomeSource(providers = { listOf(provider) })
    return when (val reply = source.page(HomeSourceId(provider.name), section = 0, page = 1)) {
        is HomePageReply.Loaded -> {
            val titles = reply.rows.sumOf { it.items.size }
            "home ok · ${reply.rows.size} rows, $titles titles, next page ${reply.hasNext}"
        }
        else -> "home FAILED: $reply"
    }
}

private class CheckOutcome(val foundResults: Boolean, val summary: String)

/** Each stage reports alone, because "search works and opening fails" is a different plugin problem. */
private suspend fun check(provider: SandboxedProvider, query: String, verbose: Boolean): CheckOutcome {
    val page = try {
        provider.search(query, 1)
    } catch (failure: Exception) {
        return CheckOutcome(false, "search FAILED: ${failure.message}")
    }
    val first = page.items.firstOrNull() ?: return CheckOutcome(false, "search ok · 0 results")
    val found = "search ok · ${page.items.size} results"
    val details = try {
        provider.details(first.url)
    } catch (failure: Exception) {
        return CheckOutcome(true, "$found · open FAILED: ${failure.message}")
    }
    val opened = "$found · opened \"${first.name}\" (${details.type}, ${details.episodes.size} episodes)"
    val data = details.dataUrl ?: details.episodes.firstOrNull()?.data
        ?: return CheckOutcome(true, "$opened · no playable data")
    return try {
        val asked = TimeSource.Monotonic.markNow()
        var firstLink: Duration? = null
        var shown = 0
        val links = provider.linksAsTheyArrive(data).onEach { answer ->
            if (firstLink == null && answer.links.isNotEmpty()) firstLink = asked.elapsedNow()
            // The plugin's own quality numbers for the links new since the last line, so when the best one arrived
            // can be read off. Numbers only: a link's URL can carry a token.
            val qualities = answer.links.drop(shown).map { it.quality }
            shown = answer.links.size
            if (verbose) println("check:   links ${answer.links.size} +$qualities, subtitles ${answer.subtitles.size} at ${asked.elapsedNow().inWholeMilliseconds} ms")
        }.last()
        val hosts = links.links.map { it.url.substringAfter("://").substringBefore('/') }.distinct()
        val subtitles = checkSubtitles(links.subtitles, verbose)
        val first = firstLink?.let { " · first link at ${it.inWholeMilliseconds} ms" }.orEmpty()
        val ended = links.stoppedEarly?.let { " · stopped early: $it" }.orEmpty()
        val public = withContext(Dispatchers.IO) { links.links.map { it.isPublic() } }
        val playable = public.count { it }
        // In arrival order, an x after each the player may not open: what it would have had to choose from, and when.
        if (verbose) println("check:   qualities ${links.links.mapIndexed { i, link -> "${link.quality}${if (public[i]) "" else "x"}" }}")
        CheckOutcome(
            true,
            "$opened · ${links.links.size} links, $playable the player may open ${hosts.take(3)}, " +
                "${links.subtitles.size} subtitles$first$ended$subtitles",
        )
    } catch (failure: Exception) {
        CheckOutcome(true, "$opened · links FAILED: ${failure.message}")
    }
}

/**
 * What opening a subtitle track would show: one track (English where there is one), or with [verbose] all of
 * them. Through the real fetch and parser, so it answers "would this appear". Prints sizes and counts, never
 * a URL or a cue's words: a URL can carry a token, and the words are not this report's business.
 */
private suspend fun checkSubtitles(subtitles: List<RemoteSubtitle>, verbose: Boolean): String {
    if (subtitles.isEmpty()) return ""
    val preferred = subtitles.firstOrNull { it.languageTag?.startsWith("en") == true } ?: subtitles.first()
    val chosen = if (verbose) subtitles else listOf(preferred)
    val loader = HttpSubtitleLoader()
    return buildString {
        for (subtitle in chosen) {
            val track = SubtitleTrack(
                source = SubtitleSource.Remote(subtitle.url),
                label = subtitle.language,
                languageTag = subtitle.languageTag,
            )
            append("\ncheck:   subtitle ${subtitle.language} (${subtitle.languageTag}) · ${describe(loader.load(track))}")
        }
    }
}

private fun describe(load: SubtitleLoad): String = when (load) {
    is SubtitleLoad.Failed -> "FAILED: ${load.reason}"
    is SubtitleLoad.Ready -> {
        val cues = load.timeline.cues
        val placed = cues.count { it.placement != CuePlacement.Default }
        "${load.bytes} bytes · ${load.report.format} · ${cues.size} cues, ${load.report.malformedCues} malformed, " +
            "${placed} placed · first at ${cues.first().startTimeMs} ms, ${cues.first().text.sumOf { it.length }} chars" +
            load.report.truncations.takeIf { it.isNotEmpty() }?.let { " · cut: $it" }.orEmpty()
    }
}

private const val MAX_HOSTS_SHOWN = 40
private const val MAX_LOG_LINES = 12
private const val MAX_LOG_CHARS = 200
