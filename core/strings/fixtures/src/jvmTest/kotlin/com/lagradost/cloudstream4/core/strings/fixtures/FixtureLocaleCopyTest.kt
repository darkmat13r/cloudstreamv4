package com.lagradost.cloudstream4.core.strings.fixtures

import com.lagradost.cloudstream4.core.strings.StringsOracle
import com.lagradost.cloudstream4.core.strings.assertMapsEqual
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * `localeCopies` (`strings.LocaleCopies`): the generated `values-b+he` is exactly the emitted `values-b+iw`, in
 * `emitted.tsv` and in the packaged `.cvr` bytes. That `he` then resolves like Android is `LocaleLookupParityTest`'s
 * `he`/`he-IL` requests; a real `values-b+he` in the res is the `locale-copy-collision` guard case.
 */
@OptIn(ExperimentalResourceApi::class)
class FixtureLocaleCopyTest {
    @Test
    fun hebrewCopyEqualsTheIwDirectory() = runBlocking {
        fun directory(name: String) =
            StringsOracle.emitted.filterKeys { it.scope == name }.mapKeys { it.key.copy(scope = "") }
        val iw = directory("values-b+iw")
        assertTrue(iw.isNotEmpty(), "values-b+iw emitted nothing")
        assertMapsEqual("emitted values-b+he vs values-b+iw", iw, directory("values-b+he"))
        assertContentEquals(
            Res.readBytes("values-b+iw/strings.commonMain.cvr"),
            Res.readBytes("values-b+he/strings.commonMain.cvr"),
        )
    }
}
