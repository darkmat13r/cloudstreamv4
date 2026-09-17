package strings

/**
 * Port of AOSP `frameworks/base/libs/androidfw/LocaleData.cpp` (likely scripts, region parents and region
 * comparison), which `ResTable_config::match` and `isLocaleBetterThan` call. The tables are in
 * `resources/strings/android-locale-data.txt`, extracted from AGP 9.1.1's `aaptcompiler` copy of
 * `LocaleDataTables.cpp`. A device's own table is the CLDR snapshot of its Android release, so a locale whose likely
 * script or parent region changed between releases can resolve differently on that device.
 */
internal object AndroidLocaleData {
    private const val PACKED_ROOT = 0
    private const val US_SPANISH = 0x65735553 // es-US
    private const val MEXICAN_SPANISH = 0x65734D58 // es-MX
    private const val LATIN_AMERICAN_SPANISH = 0x6573A424 // es-419
    private val ENGLISH_STOP_LIST = listOf(0x656E0000, 0x656E8400) // en, en-001

    private val likelyScripts = HashMap<Int, String>()
    private val scriptParents = HashMap<String, HashMap<Int, Int>>()
    private val representativeLocales = HashSet<Long>()

    init {
        val stream = checkNotNull(AndroidLocaleData::class.java.getResourceAsStream("/strings/android-locale-data.txt"))
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val cells = line.split(' ')
                when (cells[0]) {
                    "S" -> likelyScripts[cells[1].toLong(16).toInt()] = cells[2]
                    "P" -> scriptParents.getOrPut(cells[1]) { HashMap() }[cells[2].toLong(16).toInt()] =
                        cells[3].toLong(16).toInt()
                    "R" -> representativeLocales += java.lang.Long.parseUnsignedLong(cells[1], 16)
                    else -> error("bad locale data line: $line")
                }
            }
        }
    }

    /**
     * `ResTable_config::packLanguageOrRegion`: two-letter codes are stored as-is; three-letter codes are packed into
     * 15 bits with the high bit set (`fil` -> `0xAD05`, the `kFilipino` constant in ResourceTypes.cpp).
     */
    fun packCode(code: String, base: Char): Int = when (code.length) {
        0 -> 0
        2 -> (code[0].code shl 8) or code[1].code
        3 -> {
            val first = (code[0] - base) and 0x7f
            val second = (code[1] - base) and 0x7f
            val third = (code[2] - base) and 0x7f
            ((0x80 or (third shl 2) or (second shr 3)) shl 8) or (((second shl 5) or first) and 0xff)
        }
        else -> throw IllegalArgumentException("cannot pack '$code'")
    }

    fun packLocale(language: String, region: String): Int = (packCode(language, 'a') shl 16) or packCode(region, '0')

    private fun dropRegion(packed: Int) = packed and 0xFFFF0000.toInt()

    private fun hasRegion(packed: Int) = packed and 0x0000FFFF != 0

    /** `localeDataComputeScript`: `""` when the language (and language + region) is unknown. */
    fun computeScript(language: String, region: String): String {
        if (language.isEmpty()) return ""
        val key = packLocale(language, region)
        likelyScripts[key]?.let { return it }
        if (region.isNotEmpty()) likelyScripts[dropRegion(key)]?.let { return it }
        return ""
    }

    private fun findParent(packed: Int, script: String): Int = when {
        hasRegion(packed) -> scriptParents[script]?.get(packed) ?: dropRegion(packed)
        else -> PACKED_ROOT
    }

    /** `findAncestors`: the ancestor chain, stopping at the first member of [stopList] (index, or -1). */
    private fun findAncestors(packed: Int, script: String, stopList: List<Int>): Pair<List<Int>, Int> {
        val out = ArrayList<Int>()
        var ancestor = packed
        do {
            out += ancestor
            val index = stopList.indexOf(ancestor)
            if (index >= 0) return out to index
            ancestor = findParent(ancestor, script)
        } while (ancestor != PACKED_ROOT)
        return out to -1
    }

    private fun findDistance(supported: Int, script: String, requestAncestors: List<Int>): Int {
        val (ancestors, index) = findAncestors(supported, script, requestAncestors)
        return ancestors.size + index - 1
    }

    private fun isRepresentative(languageAndRegion: Int, script: String): Boolean {
        val scriptBits = script.fold(0L) { acc, c -> (acc shl 8) or (c.code.toLong() and 0xff) }
        return ((languageAndRegion.toLong() shl 32) or scriptBits) in representativeLocales
    }

    /** `localeDataCompareRegions`: > 0 when [leftRegion] is the better match for the request. */
    fun compareRegions(
        leftRegion: String,
        rightRegion: String,
        requestedLanguage: String,
        requestedScript: String,
        requestedRegion: String,
    ): Int {
        if (leftRegion == rightRegion) return 0
        var left = packLocale(requestedLanguage, leftRegion)
        var right = packLocale(requestedLanguage, rightRegion)
        val request = packLocale(requestedLanguage, requestedRegion)
        val leftSpecial = left == US_SPANISH || left == MEXICAN_SPANISH
        val rightSpecial = right == US_SPANISH || right == MEXICAN_SPANISH
        if (leftSpecial && !rightSpecial && right != LATIN_AMERICAN_SPANISH) {
            left = LATIN_AMERICAN_SPANISH
        } else if (rightSpecial && !leftSpecial && left != LATIN_AMERICAN_SPANISH) {
            right = LATIN_AMERICAN_SPANISH
        }
        val (requestAncestors, index) = findAncestors(request, requestedScript, listOf(left, right))
        if (index == 0) return 1
        if (index == 1) return -1
        val leftDistance = findDistance(left, requestedScript, requestAncestors)
        val rightDistance = findDistance(right, requestedScript, requestAncestors)
        if (leftDistance != rightDistance) return rightDistance - leftDistance
        val leftRepresentative = isRepresentative(left, requestedScript)
        val rightRepresentative = isRepresentative(right, requestedScript)
        if (leftRepresentative != rightRepresentative) return if (leftRepresentative) 1 else -1
        // (int)(left < right) - (int)(left > right), on uint32_t.
        return Integer.signum(Integer.compareUnsigned(right, left))
    }

    /** `localeDataIsCloseToUsEnglish`: "en" is seen before "en-001" among the region's ancestors. */
    fun isCloseToUsEnglish(region: String): Boolean =
        findAncestors(packLocale("en", region), "Latn", ENGLISH_STOP_LIST).second == 0
}
