package com.lagradost.cloudstream4.core.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live `generateComposeStrings` still reproduces `core/strings/oracle-baseline/` exactly.
 *
 * That directory is the pre-flip ground truth: what the generator emitted while `app/src/main/res` was the source
 * of truth. It is never regenerated (see its `README.md`), so this test is green today by construction and is what
 * turns any later drift — in the resources, in the resolver, in the escaping, in a locale fallback — into a build
 * failure with the changed rows named.
 *
 * Five of the six files were verified byte-identical across a forced rerun and across both guard modes, and are
 * compared whole. `report.txt` line 1 is `aapt2 <version> compile --legacy; guards=<mode>`: both halves are
 * environment, not output, so only its shape is asserted and the comparison starts at line 2.
 *
 * This test is in `src/jvmTestUiString`, not the shared `src/jvmTest`, because `:core:strings:fixtures` compiles
 * the shared sources over its own adversarial resources and has no baseline.
 */
class OracleBaselineTest {
    private val baseline = File(
        requireNotNull(System.getProperty("strings.baseline")) { "run through Gradle: strings.baseline is unset" },
    )
    private val live = File(
        requireNotNull(System.getProperty("strings.oracles")) { "run through Gradle: strings.oracles is unset" },
    )

    /** Every row count the baseline `README.md` quotes, so the prose cannot drift from the bytes either. */
    private val expectedDataRows = mapOf(
        "decoded.tsv" to 58_260,
        "emitted.tsv" to 60_979,
        "lookup.tsv" to 108_472,
        "requests.tsv" to 87,
        "findings.tsv" to 0,
        "report.txt" to 12,
    )

    @Test
    fun baselineHoldsExactlyTheSixGeneratedFiles() {
        val names = baseline.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
        assertEquals((COMPARED_WHOLE + REPORT + "README.md").sorted(), names)
    }

    @Test
    fun everyByteOfTheDeterministicOraclesIsUnchanged() {
        COMPARED_WHOLE.forEach { name -> assertFileEquals(name, baseline.resolve(name), live.resolve(name)) }
    }

    @Test
    fun theReportBodyIsUnchangedAndItsEnvironmentLineKeepsItsShape() {
        val expected = baseline.resolve(REPORT).readLines()
        val actual = live.resolve(REPORT).readLines()
        val shape = Regex("""^aapt2 \S+ compile --legacy; guards=(fail|report)$""")
        assertTrue(shape.matches(expected.first()), "baseline $REPORT line 1: '${expected.first()}'")
        assertTrue(shape.matches(actual.first()), "live $REPORT line 1: '${actual.first()}'")
        assertLinesEqual(REPORT, expected.drop(1), actual.drop(1), firstLineNumber = 2)
    }

    @Test
    fun theBaselineRowCountsAreTheOnesTheReadmeQuotes() {
        expectedDataRows.forEach { (name, rows) ->
            val lines = baseline.resolve(name).readLines().filter { it.isNotEmpty() }
            val counted = lines.count { !it.startsWith("#") }
            assertEquals(rows, counted, "$name rows (excluding the '#' header)")
        }
    }

    private fun assertFileEquals(name: String, expectedFile: File, actualFile: File) {
        if (expectedFile.readBytes().contentEquals(actualFile.readBytes())) return
        assertLinesEqual(name, expectedFile.readLines(), actualFile.readLines(), firstLineNumber = 1)
        throw AssertionError("$name: bytes differ but every line matches — a trailing-newline or encoding change")
    }

    private fun assertLinesEqual(name: String, expected: List<String>, actual: List<String>, firstLineNumber: Int) {
        val differing = (0 until maxOf(expected.size, actual.size))
            .filter { expected.getOrNull(it) != actual.getOrNull(it) }
        if (differing.isEmpty()) {
            assertEquals(expected.size, actual.size, "$name line count")
            return
        }
        val sample = differing.take(20).joinToString("\n") {
            "  line ${it + firstLineNumber}\n" +
                "    baseline: ${StringsOracle.show(expected.getOrNull(it))}\n" +
                "    live:     ${StringsOracle.show(actual.getOrNull(it))}"
        }
        throw AssertionError(
            "$name drifted from the frozen pre-flip baseline: ${differing.size} of ${expected.size} lines differ.\n" +
                "Do not re-record core/strings/oracle-baseline/ — review the change, see that directory's README.\n" +
                sample,
        )
    }

    private companion object {
        const val REPORT = "report.txt"

        /** Byte-identical across a forced rerun and across `guards=fail` / `guards=report`. */
        val COMPARED_WHOLE = listOf("decoded.tsv", "emitted.tsv", "lookup.tsv", "requests.tsv", "findings.tsv")
    }
}
