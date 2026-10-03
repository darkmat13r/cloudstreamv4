package strings

/** One source values file; [location] is relative to the res directory, e.g. `values-b+af/strings.xml`. */
internal class SourceFile(val location: String, val bytes: ByteArray) {
    val directory: String get() = location.substringBefore('/')
    val fileName: String get() = location.substringAfter('/')
}

/** `aapt2 compile` over sanitised files (location -> text), returning the decoded entries per location. */
internal fun interface ResourceCompiler {
    fun compile(files: Map<String, String>): Map<String, List<DecodedEntry>>
}

internal class PipelineResult(
    val emitted: Map<String, Map<ResourceKey, TextValue>>,
    /**
     * The **lean** tree: for each directory, only the visible keys that directory itself declares, carrying the
     * value Android resolves for that directory's own locale (so an `@string/` alias is already resolved in it).
     *
     * This is [Materialiser]'s step 1 and nothing else — [emitted] is this plus the copies step 2 adds so that
     * Compose's fallback lands where Android's does. The two are kept apart because they have different jobs:
     * the lean tree is what `app/src/main/res` holds, and the copies are a Compose concern that Android resolves
     * for itself. Writing the copies into the res adds entries Android does not need and makes locale files
     * declare plurals with only the categories their source had, which Android lint rightly rejects.
     */
    val lean: Map<String, Map<ResourceKey, TextValue>>,
    /** Every `@string/…` the res holds instead of literal text, see [AuthoredAliases]. */
    val aliases: List<AuthoredAliases.Alias>,
    val decoded: List<Pair<String, DecodedEntry>>,
    val requests: List<ParityRequest>,
    val lookup: Map<String, Map<ResourceKey, TextValue>>,
    val report: List<String>,
)

/**
 * Android values -> Compose values, with no Gradle types so the guard fixtures can drive it directly.
 * See `GenerateComposeStringsTask` for the contract, `Materialiser` for the lookup parity rules and [LocaleCopies].
 */
internal class StringsPipeline(private val findings: Findings, private val compiler: ResourceCompiler) {
    private companion object {
        const val BASE = "values"
        const val DO_NOT_TRANSLATE = "donottranslate-strings.xml"
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val ASCII_WHITESPACE = setOf(0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D)
    }

    fun run(
        sources: List<SourceFile>,
        visibleDoNotTranslate: List<String>,
        extraRequests: List<ParityRequest>,
        localeCopies: Map<String, String>,
    ): PipelineResult {
        val report = mutableListOf<String>()
        val qualifiers = modelledDirectories(sources.map { it.directory }.distinct().sorted())
        val copies = LocaleCopies.plan(localeCopies, sources.map { it.directory }.distinct(), findings)
        val modelled = sources.filter { it.directory in qualifiers }.sortedBy { it.location }

        val sanitised = modelled.associate { it.location to ValuesSanitizer.sanitise(it.location, it.bytes, findings) }
        report += "stray text runs removed before aapt2: ${sanitised.values.sumOf { it.strayRemoved }}"
        checkDuplicates(modelled, sanitised)

        val decoded = compiler.compile(sanitised.mapValues { it.value.text })
        val table = HashMap<String, MutableMap<ResourceKey, TableEntry>>()
        qualifiers.keys.forEach { table[it] = sortedMapOf() }
        val decodedRows = mutableListOf<Pair<String, DecodedEntry>>()
        for (source in modelled) {
            val entries = decoded[source.location].orEmpty()
            val declared = sanitised.getValue(source.location).declared
            val keys = entries.map { it.type to it.name }.toSet()
            if (keys != declared || keys.size != entries.size) {
                findings.add(
                    FindingKind.DECODE_COUNT_MISMATCH, source.location, "",
                    "XML declares ${declared.size} string/plurals, aapt2 table decoded ${entries.size}: " +
                        "${(declared - keys).take(3)} ${(keys - declared).take(3)}",
                )
            }
            entries.forEach { entry ->
                val key = ResourceKey(entry.type, entry.name)
                table.getValue(source.directory)[key] = TableEntry(source.location, entry.value)
                decodedRows += source.location to entry
                checkWhitespace(source.location, entry)
            }
        }
        report += "decoded: ${qualifiers.size} directories, ${decodedRows.size} entries"

        val visible = visibleKeys(table, visibleDoNotTranslate)
        val own = table.mapValues { (directory, entries) ->
            entries.filter { (key, entry) ->
                val ok = key in visible || (directory == BASE && entry.location.endsWith(DO_NOT_TRANSLATE))
                if (!ok) findings.add(FindingKind.ORPHAN_KEY, entry.location, key.toString(), "not generated")
                key in visible
            }.keys
        }
        report += "visible keys: ${visible.size} (${visible.count { it.type == "plurals" }} plurals)"

        val resolver = AndroidResolver(table, qualifiers, findings)
        val materialiser = Materialiser(qualifiers, visible, resolver, findings)
        materialiser.run(own)
        materialiser.emitted.forEach { (directory, entries) ->
            if (entries.isEmpty()) report += "directory $directory: nothing to emit"
        }
        report += "emitted values by reason: ${materialiser.reasons.toSortedMap()}"
        report += LocaleCopies.apply(copies, materialiser, qualifiers)
        report += "styled values emitted as plain text: ${materialiser.styledEmitted}"
        decodedRows.filter { (_, e) -> e.value.let { it is DecodedValue.Single && it.item is DecodedItem.Styled } }
            .forEach { (location, e) ->
                findings.add(FindingKind.STYLED_AS_PLAIN_TEXT, location, "${e.type}/${e.name}", "spans dropped")
            }

        val requests = materialiser.requests(extraRequests)
        val lookup = materialiser.lookup(requests)
        report += "parity requests: ${requests.size} (${extraRequests.size} beyond directory locales)"
        return PipelineResult(
            emitted = materialiser.emitted.filterValues { it.isNotEmpty() },
            aliases = table.flatMap { (directory, entries) ->
                entries.mapNotNull { (key, entry) ->
                    val byCategory = when (val v = entry.value) {
                        is DecodedValue.Single -> (v.item as? DecodedItem.Ref)
                            ?.let { mapOf(AuthoredAliases.SINGLE to "@" + it.name) }
                        is DecodedValue.Plural -> v.items.mapNotNull { (c, i) ->
                            (i as? DecodedItem.Ref)?.let { c to "@" + it.name }
                        }.takeIf { it.isNotEmpty() }?.toMap()
                        is DecodedValue.Other -> null
                    }
                    byCategory?.let { AuthoredAliases.Alias(directory, key, it) }
                }
            },
            lean = materialiser.emitted
                .mapValues { (directory, values) -> values.filterKeys { it in own[directory].orEmpty() } }
                .filterValues { it.isNotEmpty() },
            decoded = decodedRows.sortedWith(compareBy({ it.first }, { it.second.type }, { it.second.name })),
            requests = requests,
            lookup = lookup,
            report = report,
        )
    }

    private fun modelledDirectories(directories: List<String>): Map<String, LocaleQualifier> {
        val result = LinkedHashMap<String, LocaleQualifier>()
        val byLocale = HashMap<LocaleQualifier, String>()
        for (directory in directories) {
            val qualifier = LocaleQualifier.parseDirectory(directory)
            val clash = qualifier?.let { byLocale[it] }
            when {
                qualifier == null -> findings.add(
                    FindingKind.UNMODELLED_QUALIFIER, directory, "",
                    "only values, values-ll, values-ll-rRR and values-b+ll[+Ssss][+RR] are modelled; directory skipped",
                )
                clash != null -> findings.add(
                    FindingKind.AMBIGUOUS_LOCALE, directory, "", "same locale as $clash; directory skipped",
                )
                else -> {
                    result[directory] = qualifier
                    byLocale[qualifier] = directory
                }
            }
        }
        if (BASE !in result) throw StringsBuildException("no values/ directory among the sources")
        return result
    }

    private fun checkDuplicates(sources: List<SourceFile>, sanitised: Map<String, SanitisedFile>) {
        sources.groupBy { it.directory }.forEach { (directory, files) ->
            val seen = HashMap<Pair<String, String>, String>()
            files.forEach { file ->
                sanitised.getValue(file.location).declared.forEach { key ->
                    seen.put(key, file.fileName)?.let { other ->
                        findings.add(
                            FindingKind.DUPLICATE_DEFINITION, file.location, "${key.first}/${key.second}",
                            "also defined in $directory/$other; :app's mergeResources fails on this too",
                        )
                    }
                }
            }
        }
    }

    /** Code points where `Character.isWhitespace` (aaptcompiler `XmlStringBuilder.kt:131`) and `isspace` disagree. */
    private fun checkWhitespace(location: String, entry: DecodedEntry) {
        val texts = when (val value = entry.value) {
            is DecodedValue.Single -> listOfNotNull(value.item.text())
            is DecodedValue.Plural -> value.items.mapNotNull { it.second.text() }
            is DecodedValue.Other -> emptyList()
        }
        texts.forEach { text ->
            val divergent = text.codePoints().filter { Character.isWhitespace(it) && it !in ASCII_WHITESPACE }
                .distinct()
            divergent.forEach { cp ->
                findings.add(
                    FindingKind.UNICODE_WHITESPACE, location, "${entry.type}/${entry.name}",
                    "U+%04X: the APK's compiler collapses it, native aapt2 keeps it".format(cp),
                )
            }
        }
    }

    private fun DecodedItem.text(): String? = when (this) {
        is DecodedItem.Text -> text
        is DecodedItem.Styled -> text
        else -> null
    }

    private fun visibleKeys(
        table: Map<String, Map<ResourceKey, TableEntry>>,
        allowlist: List<String>,
    ): Set<ResourceKey> {
        val base = table.getValue(BASE)
        val visible = sortedSetOf<ResourceKey>()
        base.forEach { (key, entry) -> if (!entry.location.endsWith(DO_NOT_TRANSLATE)) visible += key }
        allowlist.forEach { name ->
            val keys = base.filter { (key, entry) ->
                key.name == name && entry.location.endsWith(DO_NOT_TRANSLATE)
            }.keys
            if (keys.isEmpty()) {
                findings.add(
                    FindingKind.UNKNOWN_ALLOWLIST_NAME, "$BASE/$DO_NOT_TRANSLATE", name, "listed but not defined",
                )
            }
            visible += keys
        }
        visible.filterNot { it.name.matches(IDENTIFIER) }.forEach {
            findings.add(FindingKind.INVALID_NAME, "$BASE/strings.xml", it.toString(), "not generated")
            visible -= it
        }
        return visible
    }
}
