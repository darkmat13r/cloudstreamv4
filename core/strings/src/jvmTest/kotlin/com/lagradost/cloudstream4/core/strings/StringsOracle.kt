package com.lagradost.cloudstream4.core.strings

import java.io.File

/**
 * Reads the TSV oracles `generateComposeStrings` wrote from aapt2's decoded table (`-Dstrings.oracles=<dir>`).
 * Nothing here decodes Android or Compose escaping: cells only undo the TSV escaping of `\\`, `\n`, `\t`, `\r`.
 */
internal object StringsOracle {
    /** `(directory or request id, type, name, sub)`, sub is `-` or a plural category. */
    data class Key(val scope: String, val type: String, val name: String, val sub: String)

    data class Request(
        val id: String,
        val androidTag: String,
        val language: String,
        val script: String,
        val region: String,
        val expectation: String,
        val note: String,
    )

    private val dir: File by lazy {
        File(requireNotNull(System.getProperty("strings.oracles")) { "run through Gradle: strings.oracles is unset" })
    }

    val emitted: Map<Key, String> by lazy { values("emitted.tsv") }

    val lookup: Map<Key, String> by lazy { values("lookup.tsv") }

    val requests: List<Request> by lazy {
        rows("requests.tsv").map { Request(it[0], it[1], it[2], it[3], it[4], it[5], it[6]) }
    }

    val decoded: List<List<String>> by lazy { rows("decoded.tsv") }

    private fun values(name: String): Map<Key, String> =
        rows(name).associate { Key(it[0], it[1], it[2], it[3]) to it[4] }

    fun rows(name: String): List<List<String>> = File(dir, name).readLines()
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> line.split('\t').map(::unescape) }

    private fun unescape(cell: String): String = buildString {
        var i = 0
        while (i < cell.length) {
            val c = cell[i]
            if (c != '\\') {
                append(c)
                i++
                continue
            }
            append(
                when (cell[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '\\' -> '\\'
                    else -> error("bad escape in '$cell'")
                },
            )
            i += 2
        }
    }

    /** Printable form for failure messages: control characters and backslashes as `\uXXXX`. */
    fun show(value: String?): String = value?.let {
        buildString {
            it.forEach { c -> if (c.code < 0x20 || c == '\\') append("\\u%04X".format(c.code)) else append(c) }
        }
    } ?: "<none>"
}
