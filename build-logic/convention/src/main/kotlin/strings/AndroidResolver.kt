package strings

/** A decoded entry and the file it came from (`values-b+af/strings.xml`). */
internal data class TableEntry(val location: String, val value: DecodedValue)

/** What Android returns for a lookup, and how it got there. */
internal data class AndroidValue(
    val value: TextValue,
    val directory: String,
    val viaReference: Boolean,
    val styled: Boolean,
)

/**
 * Resolves `string` and `plurals` resources the way the APK does for one request: [selectAndroid] picks the
 * directory, then a whole-value `@string/x` reference is resolved again **with the same request**, so a base-only
 * alias (`duplicate_cancel` = `@string/cancel`) yields the requesting locale's `cancel`. References chain; plural
 * items resolve individually.
 *
 * Anything Android would resolve through framework resources or fail on at link time (`@android:string/…`,
 * `?attr`, `@null`, a missing target, a cycle, a non-string target) is a finding, never a guessed value.
 */
internal class AndroidResolver(
    private val table: Map<String, Map<ResourceKey, TableEntry>>,
    private val qualifiers: Map<String, LocaleQualifier>,
    private val findings: Findings,
) {
    private val candidates = HashMap<ResourceKey, List<Pair<String, AndroidConfig>>>()

    /** Directories in `ResTable` configuration order: the default configuration first. */
    private fun candidates(key: ResourceKey) = candidates.getOrPut(key) {
        table.keys.filter { key in table.getValue(it) }
            .sortedWith(compareBy({ !qualifiers.getValue(it).isDefault }, { it }))
            .map { it to AndroidConfig.ofDirectory(qualifiers.getValue(it)) }
    }

    /** The directory Android reads [key] from for [request], or null if no directory matches. */
    fun directory(request: AndroidConfig, key: ResourceKey): String? {
        val selection = selectAndroid(request, candidates(key))
        selection.tiedWith?.let { tied ->
            findings.add(
                FindingKind.AMBIGUOUS_LOCALE, "${selection.chosen}/strings.xml", key.toString(),
                "request ${request.language}-${request.script}-${request.region} ties with $tied",
            )
        }
        return selection.chosen
    }

    fun value(request: AndroidConfig, key: ResourceKey): AndroidValue? = resolve(request, key, emptySet())

    private fun resolve(request: AndroidConfig, key: ResourceKey, visiting: Set<ResourceKey>): AndroidValue? {
        val directory = directory(request, key) ?: return null
        val entry = table.getValue(directory).getValue(key)
        var viaReference = false
        var styled = false
        fun text(item: DecodedItem): String? = when (item) {
            is DecodedItem.Text -> item.text
            is DecodedItem.Styled -> item.text.also { styled = true }
            is DecodedItem.Ref -> resolveReference(request, entry.location, key, item, visiting + key)?.let {
                viaReference = true
                styled = styled || it.styled
                (it.value as TextValue.Single).text
            }
            is DecodedItem.Unsupported -> null.also {
                findings.add(FindingKind.UNSUPPORTED_VALUE, entry.location, key.toString(), item.what)
            }
        }
        val value: TextValue = when (val decoded = entry.value) {
            is DecodedValue.Single -> TextValue.Single(text(decoded.item) ?: return null)
            is DecodedValue.Plural -> TextValue.Plural(
                decoded.items.associate { (category, item) -> category to (text(item) ?: return null) },
            )
            is DecodedValue.Other -> {
                findings.add(FindingKind.UNSUPPORTED_VALUE, entry.location, key.toString(), decoded.what)
                return null
            }
        }
        return AndroidValue(value, directory, viaReference, styled)
    }

    private fun resolveReference(
        request: AndroidConfig,
        location: String,
        key: ResourceKey,
        reference: DecodedItem.Ref,
        visiting: Set<ResourceKey>,
    ): AndroidValue? {
        fun unresolved(reason: String): AndroidValue? = null.also {
            findings.add(FindingKind.UNRESOLVED_REFERENCE, location, key.toString(), reason)
        }
        val name = reference.name
        if (reference.isAttribute) return unresolved("theme attribute ?$name")
        if (name.isEmpty()) return unresolved("@null")
        if (!name.startsWith("string/")) return unresolved("@$name is not a string of this app")
        val target = ResourceKey("string", name.removePrefix("string/"))
        if (target in visiting) return unresolved("reference cycle through @$name")
        if (candidates(target).isEmpty()) return unresolved("@$name does not exist")
        return resolve(request, target, visiting) ?: unresolved("@$name has no value for this locale")
    }
}
