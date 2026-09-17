package com.lagradost.cloudstream.buildlogic

/** The unified diff `checkExtensionApi` prints between the committed classes.jar dump and the current one. */
internal object ExtensionApiDiff {
    private sealed interface Edit
    private data class Same(val a: Int, val b: Int) : Edit
    private data class Delete(val a: Int) : Edit
    private data class Insert(val b: Int) : Edit

    /**
     * A unified diff with 3 lines of context. Classes are aligned by name, and only lines within the
     * same class are compared (LCS), so the cost stays proportional to the classes that changed.
     */
    fun unifiedDiff(a: List<String>, b: List<String>, aName: String, bName: String): List<String> {
        val edits = mutableListOf<Edit>()
        val aBlocks = ExtensionApiDump.blocks(a)
        val bBlocks = ExtensionApiDump.blocks(b)
        var i = 0
        var j = 0
        var aLine = 0
        var bLine = 0
        while (i < aBlocks.size || j < bBlocks.size) {
            val cmp = when {
                i == aBlocks.size -> 1
                j == bBlocks.size -> -1
                else -> aBlocks[i].first.compareTo(bBlocks[j].first)
            }
            when {
                cmp < 0 -> { aBlocks[i].second.forEach { _ -> edits += Delete(aLine++) }; i++ }
                cmp > 0 -> { bBlocks[j].second.forEach { _ -> edits += Insert(bLine++) }; j++ }
                else -> {
                    lcsEdits(aBlocks[i].second, bBlocks[j].second, aLine, bLine, edits)
                    aLine += aBlocks[i].second.size
                    bLine += bBlocks[j].second.size
                    i++; j++
                }
            }
        }

        val changes = edits.indices.filter { edits[it] !is Same }
        if (changes.isEmpty()) return emptyList()
        val context = 3
        val out = mutableListOf("--- $aName", "+++ $bName")
        var start = 0
        while (start < changes.size) {
            var end = start
            while (end + 1 < changes.size && changes[end + 1] - changes[end] <= 2 * context) end++
            val from = maxOf(0, changes[start] - context)
            val to = minOf(edits.size - 1, changes[end] + context)
            val hunk = edits.subList(from, to + 1)
            val aCount = hunk.count { it !is Insert }
            val bCount = hunk.count { it !is Delete }
            val aStart = hunk.firstNotNullOfOrNull { (it as? Same)?.a ?: (it as? Delete)?.a }
                ?: edits.subList(0, from).count { it !is Insert }
            val bStart = hunk.firstNotNullOfOrNull { (it as? Same)?.b ?: (it as? Insert)?.b }
                ?: edits.subList(0, from).count { it !is Delete }
            // Like git's function context: name the class the hunk is in, since it may start far below its header.
            val owner = when (val first = hunk.first { it !is Same }) {
                is Insert -> enclosingClass(b, first.b)
                is Delete -> enclosingClass(a, first.a)
                is Same -> enclosingClass(a, first.a)
            }
            out += "@@ -${if (aCount == 0) aStart else aStart + 1},$aCount +${if (bCount == 0) bStart else bStart + 1},$bCount @@" +
                (owner?.let { " $it" } ?: "")
            hunk.forEach { edit ->
                out += when (edit) {
                    is Same -> " " + a[edit.a]
                    is Delete -> "-" + a[edit.a]
                    is Insert -> "+" + b[edit.b]
                }
            }
            start = end + 1
        }
        return out
    }

    /** The class header line (`... {` at column 0) at or above [line], without the brace. */
    private fun enclosingClass(lines: List<String>, line: Int): String? =
        (line downTo 0).map { lines[it] }.firstOrNull { !it.startsWith("\t") && it.endsWith("{") }
            ?.removeSuffix("{")?.trim()

    private fun lcsEdits(x: List<String>, y: List<String>, xOffset: Int, yOffset: Int, edits: MutableList<Edit>) {
        val lengths = Array(x.size + 1) { IntArray(y.size + 1) }
        for (p in x.indices.reversed()) for (q in y.indices.reversed()) {
            lengths[p][q] = if (x[p] == y[q]) lengths[p + 1][q + 1] + 1 else maxOf(lengths[p + 1][q], lengths[p][q + 1])
        }
        var p = 0
        var q = 0
        while (p < x.size || q < y.size) {
            when {
                p < x.size && q < y.size && x[p] == y[q] -> edits += Same(xOffset + p++, yOffset + q++)
                // Removals before additions within a change, as `diff -u` prints them.
                p < x.size && (q == y.size || lengths[p + 1][q] >= lengths[p][q + 1]) -> edits += Delete(xOffset + p++)
                else -> edits += Insert(yOffset + q++)
            }
        }
    }
}
