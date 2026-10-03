package strings

/**
 * The places where `app/src/main/res` holds an `@string/…` **reference** rather than literal text — 68 of them in
 * the whole corpus, 12 of those in `values/strings.xml`.
 *
 * They cannot live in the Compose tree: Compose string resources have no indirection, so the authored tree carries
 * each one's resolved text. But the reference is not decoration — Android re-resolves it **in the requesting
 * locale**, so `values/duplicate_cancel = @string/cancel` gives a Hungarian user Hungarian. Flatten it to the base
 * literal and every locale that does not define `duplicate_cancel` itself drops to English.
 *
 * So this file is what makes the flip reversible. It feeds both directions:
 *
 * - `writeAndroidStrings` emits the reference form for these keys, so the regenerated res is what it was and
 *   Android resolves exactly as before — no copies, no extra entries, no change to any locale;
 * - `materialiseComposeStrings` restores them as [DecodedItem.Ref] in the table it hands [AndroidResolver], so
 *   [Materialiser] resolves each one per locale and writes the result into the directories Compose needs it in.
 *
 * Written by `generateComposeStrings` from aapt2's decoded table, so it is derived rather than maintained: the
 * reference form is whatever the res actually said.
 *
 * Row shape, TSV: `directory`, `type`, `name`, plural category (`-` for a `<string>`), `@string/target`.
 */
internal object AuthoredAliases {
    /** A reference, keyed by directory and resource; plural categories map to their own targets. */
    data class Alias(val directory: String, val key: ResourceKey, val byCategory: Map<String, String>)

    const val SINGLE = "-"

    fun format(rows: List<Alias>): String = buildString {
        append("# Where app/src/main/res holds an @string/ reference instead of literal text, written by\n")
        append("# generateComposeStrings from aapt2's decoded table. See strings.AuthoredAliases.\n")
        append("# directory\ttype\tname\tquantity ('-' for a <string>)\ttarget\n")
        rows.sortedWith(compareBy({ it.directory }, { it.key.type }, { it.key.name })).forEach { alias ->
            alias.byCategory.entries.sortedBy { it.key }.forEach { (category, target) ->
                append(alias.directory).append('\t').append(alias.key.type).append('\t').append(alias.key.name)
                    .append('\t').append(category).append('\t').append(target).append('\n')
            }
        }
    }

    fun parse(text: String): List<Alias> = text.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cells = line.split('\t')
            require(cells.size == 5) { "an alias needs 5 tab-separated cells, got ${cells.size}: $line" }
            Triple(cells[0], ResourceKey(cells[1], cells[2]), cells[3] to cells[4])
        }
        .groupBy({ it.first to it.second }, { it.third })
        .map { (key, items) -> Alias(key.first, key.second, items.toMap()) }

    /** Indexed for a single directory, as both consumers need it. */
    fun byKey(rows: List<Alias>, directory: String): Map<ResourceKey, Map<String, String>> =
        rows.filter { it.directory == directory }.associate { it.key to it.byCategory }

    /** The reference form as a decoded item, so [AndroidResolver] resolves it the way aapt2's table would. */
    fun decoded(key: ResourceKey, byCategory: Map<String, String>): DecodedValue = when (key.type) {
        "plurals" -> DecodedValue.Plural(byCategory.map { it.key to ref(it.value) })
        else -> DecodedValue.Single(ref(byCategory.getValue(SINGLE)))
    }

    private fun ref(target: String): DecodedItem =
        DecodedItem.Ref(target.removePrefix("@"), isAttribute = target.startsWith("?"))
}
