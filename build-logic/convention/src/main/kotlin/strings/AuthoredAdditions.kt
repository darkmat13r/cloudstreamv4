package strings

/**
 * Values the authored Compose tree carries that the forward generator does not derive from
 * `app/src/main/res` — see `core/strings/authored-additions.tsv` for which, and why each one exists.
 *
 * They are applied **after** [StringsPipeline] has run, over its emitted tree alone. That placement is the point:
 * `lookup`, `requests` and `decoded` are Android's own answers, read out of aapt2's table, and an addition must not
 * be able to move them. `verifyStringsRoundTrip` asserts a zero-line lookup diff against the frozen baseline, and
 * that assertion is only worth something if nothing on this path can reach the lookup.
 *
 * Both callers apply them, because they build the tree by different routes: `GenerateComposeStringsTask` writes the
 * Compose resources and `emitted.tsv`, and `VerifyStringsRoundTripTask` drives [StringsPipeline] itself.
 */
internal object AuthoredAdditions {
    /**
     * `directory`, `type`, `name`, plural category (`-` for a `<string>`) and the text, TSV-escaped as the oracles
     * are; `#` starts a comment. Rows sharing a directory, type and name are one plural value.
     */
    fun parse(text: String): List<Triple<String, ResourceKey, TextValue>> = text.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cells = line.split('\t')
            require(cells.size == 5) { "an addition needs 5 tab-separated cells, got ${cells.size}: $line" }
            Triple(cells[0], ResourceKey(cells[1], cells[2]), cells[3] to unescape(cells[4]))
        }
        .groupBy({ it.first to it.second }, { it.third })
        .map { (key, items) ->
            val value = if (items.singleOrNull()?.first == "-") {
                TextValue.Single(items.single().second)
            } else {
                TextValue.Plural(items.toMap())
            }
            Triple(key.first, key.second, value)
        }

    /**
     * [emitted] with [additions] merged in. Every addition must name a directory the pipeline emitted and a key that
     * directory does not already hold: an addition that has become derivable is a stale row, not a no-op, and
     * silently letting it win would hide the generator having started to produce the value itself.
     *
     * Problems are appended to [problems] rather than thrown, so one run reports all of them.
     */
    fun apply(
        emitted: Map<String, Map<ResourceKey, TextValue>>,
        additions: List<Triple<String, ResourceKey, TextValue>>,
        problems: MutableList<String>,
    ): Map<String, Map<ResourceKey, TextValue>> {
        val merged = emitted.mapValues { (_, values) ->
            sortedMapOf<ResourceKey, TextValue>().apply { putAll(values) }
        }.toSortedMap()
        additions.forEach { (directory, key, value) ->
            val target = merged[directory]
            when {
                target == null -> problems += "addition $directory $key: no such emitted directory"
                target.put(key, value) != null ->
                    problems += "addition $directory $key: the forward pass already emits this value"
            }
        }
        return merged
    }

    private fun unescape(cell: String): String = buildString {
        var escaped = false
        cell.forEach { c ->
            when {
                escaped -> {
                    append(if (c == 'n') '\n' else if (c == 't') '\t' else if (c == 'r') '\r' else c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                else -> append(c)
            }
        }
        require(!escaped) { "an addition ends in a dangling backslash: $cell" }
    }
}
