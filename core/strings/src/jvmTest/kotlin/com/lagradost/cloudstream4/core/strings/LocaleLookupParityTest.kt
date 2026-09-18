package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.StringsOracle.Key
import com.lagradost.cloudstream4.core.strings.StringsOracle.Request
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.allPluralStringResources
import com.lagradost.cloudstream4.core.strings.generated.resources.allStringResources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * For every locale request (each directory's own locale plus `parity-requests.txt`: `pt-PT`, `zh-Hant-TW`, `fil`,
 * `he`, …), what Compose returns equals what Android returns (the generator's API 24+ `ResTable_config` model over
 * aapt2's decoded values), or, for a documented `gap=<id>`, exactly what request `<id>` gets on Android.
 *
 * Strings go through public `getString(environment, res)`; plural bags through the runtime's own item selection
 * and decoder, then public `getPluralString` must return one of the bag's texts.
 */
class LocaleLookupParityTest {
    private val requests = StringsOracle.requests
    private val android = StringsOracle.lookup

    private fun androidValues(requestId: String): Map<Key, String> =
        android.filterKeys { it.scope == requestId }.mapKeys { it.key.copy(scope = "") }

    private suspend fun composeValues(request: Request): Map<Key, String> {
        val environment = ComposeRuntime.environment(request.language, request.script, request.region)
        val out = HashMap<Key, String>()
        Res.allStringResources.forEach { (name, resource) ->
            out[Key("", "string", name, "-")] = getString(environment, resource)
        }
        Res.allPluralStringResources.forEach { (name, resource) ->
            ComposeRuntime.decode(ComposeRuntime.itemFor(resource, environment)).forEach { (sub, text) ->
                out[Key("", "plurals", name, sub)] = text
            }
        }
        return out
    }

    @Test
    fun everyRequestMatchesAndroidOrItsDocumentedGap() = runBlocking {
        val failures = StringBuilder()
        val summary = StringBuilder()
        for (request in requests) {
            val expected = androidValues(request.id)
            val actual = composeValues(request)
            val differing = expected.keys.count { expected[it] != actual[it] } + actual.keys.count { it !in expected }
            val gapTarget = request.expectation.removePrefix("gap=").takeIf { it != request.expectation }
            val outcome = runCatching {
                if (gapTarget == null) {
                    assertEquals("match", request.expectation)
                    assertMapsEqual("${request.id} (${request.androidTag})", expected, actual)
                } else {
                    assertTrue(differing > 0, "${request.id}: documented gap closed; set its expectation to match")
                    assertMapsEqual("${request.id} vs Android $gapTarget", androidValues(gapTarget), actual)
                }
            }
            summary.append("  ${request.id.padEnd(16)} ${request.expectation.padEnd(12)}")
                .appendLine(" keys=${expected.size} differFromAndroid=$differing")
            outcome.exceptionOrNull()?.let { failures.appendLine(it.message) }
        }
        println("[parity] ${requests.size} requests\n$summary")
        if (failures.isNotEmpty()) throw AssertionError(failures.toString())
    }

    @Test
    fun publicPluralLookupReturnsAnAndroidCategoryText() = runBlocking {
        val quantities = listOf(0, 1, 2, 3, 5, 11, 21, 100)
        val throwing = sortedSetOf<String>()
        val wrong = mutableListOf<String>()
        for (request in requests.filter { it.expectation == "match" }) {
            val environment = ComposeRuntime.environment(request.language, request.script, request.region)
            Res.allPluralStringResources.forEach { (name, resource) ->
                val texts = androidValues(request.id)
                    .filterKeys { it.type == "plurals" && it.name == name }.values.toSet()
                for (quantity in quantities) {
                    runCatching { getPluralString(environment, resource, quantity) }
                        .onSuccess {
                            if (it !in texts) wrong += "${request.id} $name q=$quantity -> ${StringsOracle.show(it)}"
                        }
                        .onFailure { if (it is NoSuchElementException) throwing += request.language else throw it }
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.take(20).joinToString("\n"))
        // Compose 1.12.0 has no CLDR plural rules for these languages and throws (plan §7 S-b guard). When this
        // changes, update the S-b guard and this set together.
        val noCldrRules = setOf("apc", "arz", "qt")
        assertEquals(requests.map { it.language }.filter { it in noCldrRules }.toSortedSet(), throwing)
    }
}
