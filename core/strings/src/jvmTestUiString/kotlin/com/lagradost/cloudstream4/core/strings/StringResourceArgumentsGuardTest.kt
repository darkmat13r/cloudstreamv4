package com.lagradost.cloudstream4.core.strings

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * `BUG-84`: no main source calls Compose's argument overloads, and no locale asks for more arguments than English.
 *
 * Compose Resources 1.12's `stringResource(res, args)`, `getString(res, args)`, `pluralStringResource(res, q, args)`
 * and `getPluralString(res, q, args)` substitute only `%N$s` and `%N$d`. A bare `%s`, `%d`, `%.1f` or `%%` reaches
 * the screen as typed, and a `%N$` above the argument count throws. [UiStringFormat] covers every form the corpus
 * uses, which is why its KDoc says feature code never calls those overloads; this test is what makes that a rule.
 *
 * There is no allow-list: a call that passes arguments fails, whatever its pattern is today, because a translation
 * can change the pattern later. Pass them through [UiString.Resource] or [UiString.Plural] instead.
 *
 * What counts as a call: the four names, plus any `import … as` alias of them, outside comments and string text
 * (inside a `${}` template still counts). `stringResource` and `pluralStringResource` count wherever they appear.
 * `getString` and `getPluralString` count only in a file that imports Compose's (by name or `.*`), or when written
 * fully qualified, because preference stores have a `getString(key, default)` of their own. A leading `environment`
 * argument is not a format argument.
 */
class StringResourceArgumentsGuardTest {

    private val root = File(
        requireNotNull(System.getProperty("strings.callSites")) { "run through Gradle: strings.callSites is unset" },
    )

    @Test
    fun noMainSourcePassesArgumentsToComposeStringLookups() {
        val files = mainSources(root)
        // A walk that finds nothing would pass; the tree has hundreds of these files and the calls with no arguments.
        assertTrue(files.size > 100, "only ${files.size} main sources found under the repository root")
        val sources = files.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        assertTrue(
            sources.values.sumOf { Regex("""\bstringResource\s*\(""").findAll(it).count() } > 100,
            "the walk found main sources but almost no stringResource calls; is it reading the right tree?",
        )
        val violations = sources.flatMap { (path, text) -> StringCallScanner.violations(path, text) }
        assertEquals(
            emptyList(),
            violations,
            "Compose's argument overloads fill only %N\$s and %N\$d (BUG-84). " +
                "Use UiString.Resource(res, listOf(...)).resolve() or UiString.Plural instead.",
        )
    }

    @Test
    fun noLocaleAsksForMoreArgumentsThanEnglish() {
        val resources = File(root, "core/strings/src/commonMain/composeResources")
        val english = highestIndices(File(resources, "values/strings.xml"))
        assertTrue(english.isNotEmpty(), "no English strings read from ${resources.name}")
        val locales = resources.listFiles().orEmpty()
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
        assertTrue(locales.size > 50, "only ${locales.size} locales found")
        val over = locales.sortedBy { it.name }.flatMap { dir ->
            highestIndices(File(dir, "strings.xml"))
                .filter { (key, highest) -> highest > (english[key] ?: 0) }
                .map { (key, highest) -> "${dir.name} $key: %$highest\$ but English stops at ${english[key] ?: 0}" }
        }
        assertEquals(emptyList(), over, "a translation that asks for an argument the caller never passes throws")
    }

    // The scanner on planted sources: each of these is a shape the repository walk above has to classify the same way.

    @Test
    fun aBarePercentSCallFails() {
        val found = StringCallScanner.violations(
            "planted.kt",
            """
            import org.jetbrains.compose.resources.stringResource
            @Composable fun Skip(name: String) = BasicText(stringResource(Res.string.skip_title, name))
            """.trimIndent(),
        )
        assertEquals(listOf("planted.kt:2 stringResource(Res.string.skip_title, name)"), found)
    }

    @Test
    fun aCallOverSeveralLinesFailsAtItsFirstLine() {
        val found = StringCallScanner.violations(
            "planted.kt",
            "val a = 1\nval text = stringResource(\n    Res.string.skip_title,\n    first,\n    second,\n)\n",
        )
        assertEquals(listOf("planted.kt:2 stringResource(Res.string.skip_title, first, second)"), found)
    }

    @Test
    fun aSpreadAndANamedArgumentBothCount() {
        val found = StringCallScanner.violations(
            "planted.kt",
            "val a = stringResource(Res.string.x, *args)\n" +
                "val b = stringResource(resource = Res.string.x, formatArgs = arrayOf(1))\n",
        )
        assertEquals(2, found.size, found.toString())
    }

    @Test
    fun aCallWithOnlyItsResourceIsFine() {
        val found = StringCallScanner.violations(
            "planted.kt",
            """
            import org.jetbrains.compose.resources.getPluralString
            import org.jetbrains.compose.resources.getString
            val a = stringResource(Res.string.loading)
            val b = pluralStringResource(Res.plurals.episodes, count)
            suspend fun c() = getString(Res.string.loading) + getString(environment, Res.string.loading)
            suspend fun d() = getPluralString(environment, Res.plurals.episodes, count)
            """.trimIndent(),
        )
        assertEquals(emptyList(), found)
    }

    @Test
    fun pluralAndSuspendLookupsWithArgumentsFail() {
        val found = StringCallScanner.violations(
            "planted.kt",
            """
            import org.jetbrains.compose.resources.*
            val a = pluralStringResource(Res.plurals.episodes, count, count)
            suspend fun b() = getString(Res.string.skip_title, name)
            suspend fun c() = getString(environment, Res.string.skip_title, name)
            suspend fun d() = getPluralString(localEnvironment, Res.plurals.episodes, count, count)
            """.trimIndent(),
        )
        assertEquals(listOf(2, 3, 4, 5), found.map { it.substringAfter(':').substringBefore(' ').toInt() })
    }

    @Test
    fun anAliasAndAFullyQualifiedCallBothCount() {
        val found = StringCallScanner.violations(
            "planted.kt",
            """
            import org.jetbrains.compose.resources.stringResource as text
            val a = text(Res.string.skip_title, name)
            suspend fun b() = org.jetbrains.compose.resources.getString(Res.string.skip_title, name)
            """.trimIndent(),
        )
        assertEquals(listOf(2, 3), found.map { it.substringAfter(':').substringBefore(' ').toInt() })
    }

    @Test
    fun aPreferenceStoreGetStringIsNotComposes() {
        val found = StringCallScanner.violations(
            "planted.kt",
            """
            import org.jetbrains.compose.resources.getString
            class Store(private val prefs: Prefs) : PreferenceStore {
                override fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue)
            }
            """.trimIndent(),
        ) + StringCallScanner.violations("store.kt", "fun theme() = getString(\"theme_key\", \"Normal\")\n")
        assertEquals(emptyList(), found)
    }

    @Test
    fun commentsAndStringTextAreNotCallsButTemplatesAre() {
        val found = StringCallScanner.violations(
            "planted.kt",
            """
            // stringResource(Res.string.a, b)
            /** Never `stringResource(res, *args)`; /* nested stringResource(x, y) */ still a comment. */
            val a = "stringResource(Res.string.a, b) ${'$'}{stringResource(Res.string.c, d)}"
            val b = ""${'"'}stringResource(Res.string.a, b)""${'"'}
            val c = '"'
            """.trimIndent(),
        )
        assertEquals(listOf("planted.kt:3 stringResource(Res.string.c, d)"), found)
    }

    private fun mainSources(root: File): List<File> {
        val out = mutableListOf<File>()
        val start = root.toPath()
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = start.relativize(dir).toString().replace(File.separatorChar, '/')
                    val name = dir.fileName?.toString().orEmpty()
                    return if (rel == "app" || rel == "core/strings" || rel == "build-logic" ||
                        name == "build" || name.startsWith(".")
                    ) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = start.relativize(file).toString().replace(File.separatorChar, '/')
                    if (MAIN_SOURCE.containsMatchIn(rel)) out += file.toFile()
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return out
    }

    /** Each `<string>` or `<plurals>` name, with the highest `%N$` any of its texts uses (0 for none). */
    private fun highestIndices(file: File): Map<String, Int> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableMapOf<String, Int>()
        val children = document.documentElement.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            val texts = if (element.tagName == "plurals") {
                val items = element.getElementsByTagName("item")
                (0 until items.length).map { items.item(it).textContent }
            } else {
                listOf(element.textContent)
            }
            val highest = texts
                .flatMap { text -> POSITIONAL.findAll(text.replace("%%", "")).map { it.groupValues[1].toInt() } }
                .maxOrNull() ?: 0
            out["${element.tagName}/${element.getAttribute("name")}"] = highest
        }
        return out
    }

    private companion object {
        val MAIN_SOURCE = Regex("""(^|/)src/[A-Za-z0-9]*Main/.*\.kt$""")
        val POSITIONAL = Regex("""%(\d+)\$""")
    }
}

/**
 * Finds calls to Compose's string lookups that pass format arguments. Works on a copy of the source with comments and
 * string text blanked to spaces (newlines and quotes kept), so offsets, line numbers and argument counts survive.
 */
internal object StringCallScanner {
    private const val COMPOSE = "org.jetbrains.compose.resources"

    /** How many arguments each lookup takes before its format arguments start, after any leading environment. */
    private val FIXED =
        mapOf("stringResource" to 1, "pluralStringResource" to 2, "getString" to 1, "getPluralString" to 2)

    private val IMPORT = Regex(
        """^\s*import\s+org\.jetbrains\.compose\.resources\.(\*|\w+)(?:\s+as\s+(\w+))?""",
        RegexOption.MULTILINE,
    )

    private val DECLARATION = Regex("""\bfun\s+(<[^>]*>\s*)?$""")

    fun violations(path: String, source: String): List<String> {
        val masked = mask(source)
        // Name as written in this file -> the Compose function it is.
        val names = mutableMapOf("stringResource" to "stringResource", "pluralStringResource" to "pluralStringResource")
        for (match in IMPORT.findAll(masked)) {
            val imported = match.groupValues[1]
            val alias = match.groupValues[2]
            when {
                imported == "*" -> {
                    names["getString"] = "getString"
                    names["getPluralString"] = "getPluralString"
                }
                imported in FIXED -> names[alias.ifEmpty { imported }] = imported
            }
        }
        val call = Regex("""(?<![\w$])(\w+)\s*\(""")
        val out = mutableListOf<String>()
        for (match in call.findAll(masked)) {
            val written = match.groupValues[1]
            val start = match.range.first
            val qualifier = qualifierBefore(masked, start)
            val function = when {
                qualifier == COMPOSE && written in FIXED -> written
                qualifier != null -> null // a member call: someone else's getString
                else -> names[written]
            } ?: continue
            if (DECLARATION.containsMatchIn(masked.substring(maxOf(0, start - 40), start))) continue
            var args = arguments(masked, match.range.last + 1).map { arg ->
                // Same span in the original source, so the report shows the call as it is written.
                source.substring(arg.first, arg.last + 1).trim().replace(Regex("""\s+"""), " ")
            }
            if (function == "getString" || function == "getPluralString") {
                // `environment`, `localEnvironment`, or `environment = …` written as a named argument.
                val first = args.firstOrNull().orEmpty()
                val value = first.substringAfter("=").trim()
                if (first.startsWith("environment") || value == "environment" || value.endsWith("Environment")) {
                    args = args.drop(1)
                }
            }
            if (args.size > FIXED.getValue(function)) {
                val line = masked.substring(0, start).count { it == '\n' } + 1
                out += "$path:$line $written(${args.joinToString(", ")})"
            }
        }
        return out
    }

    /** The dotted qualifier in front of a call (`org.jetbrains.compose.resources`, `prefs`), or null for none. */
    private fun qualifierBefore(text: String, start: Int): String? {
        var i = start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != '.') return null
        var j = i - 1
        while (j >= 0 && (text[j].isLetterOrDigit() || text[j] in "_.?)]")) j--
        return text.substring(j + 1, i).trimEnd('?')
    }

    /** The source ranges of each top-level argument of the call whose `(` ends just before [from]; empty for `()`. */
    private fun arguments(text: String, from: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var depth = 0
        var argStart = from
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    if (depth == 0) {
                        if (text.substring(argStart, i).isNotBlank()) out += argStart until i
                        return out
                    }
                    depth--
                }
                ',' -> if (depth == 0) {
                    out += argStart until i
                    argStart = i + 1
                }
            }
            i++
        }
        return out
    }

    /** [source] with comment and string-literal text replaced by spaces; template expressions stay as code. */
    internal fun mask(source: String): String = Masker(source).run()

    private class Masker(private val source: String) {
        private val out = StringBuilder(source)

        fun run(): String {
            code(0, inTemplate = false)
            return out.toString()
        }

        private fun blank(index: Int) {
            if (index < out.length && out[index] != '\n') out[index] = ' '
        }

        /** Returns the index just past what it consumed. In a template, code stops after the `}` that closes it. */
        private fun code(from: Int, inTemplate: Boolean): Int {
            var i = from
            var braces = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    source.startsWith("//", i) -> {
                        while (i < source.length && source[i] != '\n') blank(i++)
                    }
                    source.startsWith("/*", i) -> {
                        var nesting = 0
                        do {
                            when {
                                source.startsWith("/*", i) -> {
                                    nesting++
                                    blank(i)
                                    blank(i + 1)
                                    i += 2
                                }
                                source.startsWith("*/", i) -> {
                                    nesting--
                                    blank(i)
                                    blank(i + 1)
                                    i += 2
                                }
                                else -> blank(i++)
                            }
                        } while (nesting > 0 && i < source.length)
                    }
                    source.startsWith("\"\"\"", i) -> i = string(i + 3, raw = true)
                    c == '"' -> i = string(i + 1, raw = false)
                    c == '\'' -> {
                        i++
                        while (i < source.length && source[i] != '\'') {
                            if (source[i] == '\\') blank(i++)
                            if (i < source.length) blank(i++)
                        }
                        i++
                    }
                    c == '{' -> {
                        braces++
                        i++
                    }
                    c == '}' -> {
                        if (inTemplate && braces == 0) return i + 1
                        braces--
                        i++
                    }
                    else -> i++
                }
            }
            return i
        }

        /** [from] is just past the opening quote(s); returns the index just past the closing ones. */
        private fun string(from: Int, raw: Boolean): Int {
            var i = from
            while (i < source.length) {
                when {
                    raw && source.startsWith("\"\"\"", i) -> {
                        // Quotes before the closing three belong to the text.
                        var end = i
                        while (end + 3 < source.length && source[end + 3] == '"') end++
                        for (k in i until end) blank(k)
                        return end + 3
                    }
                    !raw && source[i] == '"' -> return i + 1
                    !raw && source[i] == '\\' -> {
                        blank(i)
                        blank(i + 1)
                        i += 2
                    }
                    source.startsWith("\${", i) -> i = code(i + 2, inTemplate = true)
                    else -> blank(i++)
                }
            }
            return i
        }
    }
}
