package strings

/**
 * One locale request the parity oracle and the jvmTest evaluate: [androidTag] is what Android resolves
 * ([AndroidRequest]); [compose] is the environment Compose builds for the same device locale.
 *
 * [expectation] is `match` (Compose must return Android's value for every visible key) or `gap=<request id>`
 * (a documented gap: Compose returns exactly what that other request gets on Android, and differs from Android for
 * at least one key).
 */
data class ParityRequest(
    val id: String,
    val androidTag: String,
    val compose: ComposeEnvironment,
    val expectation: String,
    val note: String,
)

/** Why a value was written into a directory that does not define it in `app/src/main/res`. */
internal enum class CopyReason { OWN, REFERENCE_IN_LOCALE, ANDROID_FALLBACK }

/**
 * Decides what goes into each generated directory so that Compose's lookup returns Android's value.
 *
 * 1. Every visible entry a directory defines is written with Android's value for that directory's own locale
 *    (references resolved in that locale).
 * 2. For every other visible key, if Compose's fallback (as emitted so far) returns a different value than Android for
 *    the directory's own locale, Android's value is written into the directory. This covers base-only aliases
 *    resolved per locale, `zh-TW` falling back to the default rather than `zh` (Hant never matches Hans),
 *    `pt` falling back to `pt-BR` (same script, any region beats the default) and `fil` <-> `tl`.
 * 3. Step 2 repeats until nothing changes, since a copy can change another directory's Compose fallback.
 *
 * Requests that are not directory names (`pt-PT`, `zh-Hant-TW`) are then checked against the result.
 */
internal class Materialiser(
    private val qualifiers: Map<String, LocaleQualifier>,
    private val visible: Set<ResourceKey>,
    private val resolver: AndroidResolver,
    private val findings: Findings,
) {
    val emitted: Map<String, MutableMap<ResourceKey, TextValue>> = qualifiers.keys.associateWith { sortedMapOf() }
    val reasons = HashMap<CopyReason, Int>()
    var styledEmitted = 0
        private set

    private val order = qualifiers.keys.sortedWith(
        compareBy({ specificity(qualifiers.getValue(it)) }, { it }),
    )

    private fun specificity(q: LocaleQualifier) =
        (if (q.isDefault) 0 else 1) + (if (q.script.isEmpty()) 0 else 1) + (if (q.region.isEmpty()) 0 else 2)

    fun requestOf(q: LocaleQualifier): AndroidConfig =
        if (q.isDefault) AndroidConfig("", "", "", scriptWasComputed = true) else AndroidRequest.of(q.bcp47)

    fun run(own: Map<String, Set<ResourceKey>>) {
        for (directory in order) {
            val request = requestOf(qualifiers.getValue(directory))
            own[directory].orEmpty().filter { it in visible }.sorted().forEach { key ->
                val android = resolver.value(request, key) ?: return@forEach
                if (android.directory != directory) {
                    error("$directory does not win the lookup of its own $key (got ${android.directory})")
                }
                emitted.getValue(directory)[key] = android.value
                if (android.styled) styledEmitted++
                reasons.merge(CopyReason.OWN, 1, Int::plus)
            }
        }
        do {
            var changed = false
            for (directory in order) {
                val q = qualifiers.getValue(directory)
                if (q.isDefault) continue
                val request = requestOf(q)
                val environment = ComposeEnvironment(q.language, q.script, q.region)
                for (key in visible) {
                    if (key in emitted.getValue(directory)) continue
                    val android = resolver.value(request, key) ?: continue
                    if (composeValue(environment, key) == android.value) continue
                    emitted.getValue(directory)[key] = android.value
                    if (android.styled) styledEmitted++
                    val reason = if (android.directory == "values" || android.directory == directory) {
                        CopyReason.REFERENCE_IN_LOCALE
                    } else {
                        CopyReason.ANDROID_FALLBACK
                    }
                    reasons.merge(reason, 1, Int::plus)
                    changed = true
                }
            }
        } while (changed)
    }

    /** What Compose returns for [key] in [environment] given the emitted directories, or null (runtime error). */
    fun composeValue(environment: ComposeEnvironment, key: ResourceKey): TextValue? {
        val candidates = emitted.filterValues { key in it }.keys.map { it to qualifiers.getValue(it) }
        val chosen = selectCompose(environment, candidates) { tied ->
            findings.add(
                FindingKind.AMBIGUOUS_LOCALE, tied.joinToString(), key.toString(), "Compose finds more than one file",
            )
        }
        return chosen?.let { emitted.getValue(it)[key] }
    }

    /** The requests the oracle covers: every modelled locale directory's own locale, then [extra]. */
    fun requests(extra: List<ParityRequest>): List<ParityRequest> {
        val fromDirectories = qualifiers.filterValues { !it.isDefault }.map { (directory, q) ->
            val environment = ComposeEnvironment(q.language, q.script, q.region)
            ParityRequest(directory, q.bcp47, environment, "match", "directory locale")
        }
        return fromDirectories.sortedBy { it.id } + extra
    }

    /** Android's value for every request x visible key, and a guard for every request the prediction contradicts. */
    fun lookup(requests: List<ParityRequest>): Map<String, Map<ResourceKey, TextValue>> {
        val android = requests.associate { request ->
            val config = AndroidRequest.of(request.androidTag)
            request.id to visible.mapNotNull { key -> resolver.value(config, key)?.let { key to it.value } }
                .toMap().toSortedMap()
        }
        for (request in requests) {
            val expected = android.getValue(request.id)
            val predicted = visible.associateWith { composeValue(request.compose, it) }
            val differing = visible.filter { predicted[it] != expected[it] }.sorted()
            val gapTarget = request.expectation.removePrefix("gap=").takeIf { it != request.expectation }
            val problem = when {
                request.expectation == "match" && differing.isNotEmpty() ->
                    "expected to match, predicted to differ for ${differing.size} key(s): ${differing.take(5)}"
                gapTarget != null && android[gapTarget] == null -> "gap target '$gapTarget' is not a request"
                gapTarget != null && visible.any { predicted[it] != android.getValue(gapTarget)[it] } ->
                    "documented gap no longer equals request '$gapTarget'"
                gapTarget != null && differing.isEmpty() -> "documented gap closed: Compose now matches Android"
                request.expectation != "match" && gapTarget == null -> "unknown expectation '${request.expectation}'"
                else -> null
            }
            problem?.let { findings.add(FindingKind.PREDICTED_PARITY_GAP, "parity-requests", request.id, it) }
        }
        return android
    }
}
