package com.lagradost.cloudstream4.core.strings.fixtures

import com.lagradost.cloudstream4.core.strings.StringsOracle
import com.lagradost.cloudstream4.core.strings.assertMapsEqual
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An independent check of the generator's protobuf wire reader: its decoded table must equal
 * `golden-decoded.tsv`, produced from the same fixtures with `aapt2-proto` + `protobuf-java` and reviewed by hand.
 * A swapped plural arity, a reference read as text or a lost span fails here even though every other gate compares
 * Compose with the generator's own decode.
 */
class FixtureGoldenDecodeTest {
    @Test
    fun wireReaderMatchesTheGoldenDecode() {
        val golden = checkNotNull(javaClass.getResourceAsStream("/golden-decoded.tsv")).bufferedReader().readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val actual = StringsOracle.rows("decoded.tsv").map { cells -> cells.joinToString("\t") { escape(it) } }
        assertEquals(92, golden.size, "golden row count")
        assertMapsEqual("decoded.tsv vs golden-decoded.tsv", golden.associateBy(::key), actual.associateBy(::key))
    }

    private fun key(row: String) = row.split('\t').take(4).joinToString("\t")

    private fun escape(cell: String) =
        cell.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
}
