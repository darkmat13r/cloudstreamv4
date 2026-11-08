package com.lagradost.cloudstream4.search

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.throwAbleToResource
import com.lagradost.cloudstream3.utils.HostileFailures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `BUG-63`: search's shortfall line names each failing provider and what it said. What it said is a provider's own
 * words, built here as `safeApiCall` builds them, so it must carry no address and no user path, however they are hidden.
 */
class SearchShortfallLineTest {

    @Test
    fun noHostileFailureReachesTheShortfallLineWithATokenOrAPath() {
        val forgedFrame = NullPointerException().apply {
            stackTrace = arrayOf(StackTraceElement("a.B", "c", "https://cdn.example/k?token=SECRET99 provider.kt", 7))
        }
        // A plain exception's text starts on the second line, which the line does not show; `ErrorLoadingException`'s
        // is the first line, as is a `NullPointerException`'s frame. Each text is thrown both ways.
        val thrown = HostileFailures.cases.flatMap { (label, text) ->
            listOf("$label (IOException)" to IOException(text), "$label (ErrorLoadingException)" to ErrorLoadingException(text))
        } + ("forged NullPointerException frame" to forgedFrame)
        // Every case is tried, and every one that leaks is named, so a failure shows all of them at once.
        val leaks = thrown.mapNotNull { (label, error) ->
            val line = shortfallLine(listOf("Provider" to throwAbleToResource<Unit>(error)), maxReasons = 3, maxChars = 60)
            HostileFailures.leakIn(line)?.let { "$label: $it in \"$line\"" }
        }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test
    fun theProviderAndItsReasonStay() {
        val failure: Resource<Unit> = throwAbleToResource(ErrorLoadingException("Refused https://cdn.example/a?token=S"))
        assertEquals("Provider: Refused https://cdn.example/<…>", shortfallLine(listOf("Provider" to failure), 3, 60))
    }
}
