package com.lagradost.cloudstream4.extensions

import com.lagradost.cloudstream4.feature.extensions.sandbox.SandboxedProvider
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.home.DesktopHomeSource
import kotlinx.coroutines.runBlocking

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
            val outcome = check(provider, query)
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
private suspend fun check(provider: SandboxedProvider, query: String): CheckOutcome {
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
        val links = provider.links(data)
        val hosts = links.links.map { it.url.substringAfter("://").substringBefore('/') }.distinct()
        CheckOutcome(true, "$opened · ${links.links.size} links ${hosts.take(3)}, ${links.subtitles} subtitles")
    } catch (failure: Exception) {
        CheckOutcome(true, "$opened · links FAILED: ${failure.message}")
    }
}

private const val MAX_LOG_LINES = 12
private const val MAX_LOG_CHARS = 200
