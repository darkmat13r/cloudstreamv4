package com.lagradost.cloudstream4.core.strings

import com.lagradost.cloudstream4.core.strings.StringsOracle.Key
import com.lagradost.cloudstream4.core.strings.StringsOracle.show
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.allPluralStringResources
import com.lagradost.cloudstream4.core.strings.generated.resources.allStringResources
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Resource

/**
 * Every value the Compose Multiplatform plugin packaged equals the value the generator emitted from aapt2's decoded
 * table: through the runtime's decoder (every directory, key and plural category, none missing, none extra) and
 * through the raw `.cvr` bytes with public API only.
 */
@OptIn(ExperimentalResourceApi::class)
class GeneratedResourcesParityTest {
    private val expected = StringsOracle.emitted

    @Test
    fun composeVersionIsTheVerifiedOne() {
        // The encoder (CmpStringsXml) and the reflection in ComposeRuntime were verified against this release.
        // After a bump: re-run :core:strings:fixtures:check, then update this pin.
        assertEquals("1.12.0", System.getProperty("cmp.version"))
    }

    @Test
    fun everyPackagedItemDecodesToTheEmittedValue() = runBlocking {
        val resources: List<Pair<String, Resource>> = Res.allStringResources.map { "string" to it.value } +
            Res.allPluralStringResources.map { "plurals" to it.value }
        val actual = HashMap<Key, String>()
        for ((type, resource) in resources) {
            val name = resource.javaClass.getMethod("getKey").invoke(resource) as String
            for ((directory, item) in ComposeRuntime.itemsByDirectory(resource)) {
                ComposeRuntime.decode(item).forEach { (sub, text) -> actual[Key(directory, type, name, sub)] = text }
            }
        }
        assertMapsEqual("runtime decode vs emitted.tsv", expected, actual)
        assertTrue(expected.size > 0, "no emitted values")
        println("[parity] runtime-decoded items: ${actual.size}, emitted: ${expected.size}")
    }

    @Test
    fun packagedCvrRecordsMatchWithPublicApiOnly() = runBlocking {
        val actual = HashMap<Key, String>()
        for (directory in expected.keys.map { it.scope }.toSortedSet()) {
            val lines = Res.readBytes("$directory/strings.commonMain.cvr").decodeToString().lines()
            assertEquals("version:0", lines.first(), directory)
            lines.drop(1).filter { it.isNotEmpty() }.forEach { record ->
                val (type, name, data) = record.split('|')
                data.split(',').forEach { part ->
                    val sub = if (type == "plurals") part.substringBefore(':').lowercase() else "-"
                    val text = Base64.getDecoder().decode(part.substringAfter(':')).decodeToString()
                    actual[Key(directory, type, name, sub)] = text
                }
            }
        }
        assertMapsEqual(".cvr records vs emitted.tsv", expected, actual)
    }
}

internal fun <K> assertMapsEqual(what: String, expected: Map<K, String>, actual: Map<K, String>) {
    val problems = (expected.keys + actual.keys).filter { expected[it] != actual[it] }
    if (problems.isEmpty()) return
    val sample = problems.take(20).joinToString("\n") {
        "  $it expected=${show(expected[it])} actual=${show(actual[it])}"
    }
    throw AssertionError("$what: ${problems.size} of ${expected.size} differ\n$sample")
}
