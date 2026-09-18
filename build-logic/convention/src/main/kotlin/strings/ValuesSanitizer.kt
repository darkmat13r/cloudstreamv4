package strings

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** A values file ready for aapt2, plus what the XML itself declares (for the decode self-check). */
internal class SanitisedFile(val text: String, val declared: Set<Pair<String, String>>, val strayRemoved: Int)

/**
 * Prepares one `values…/….xml` file for `aapt2 compile` without changing any value.
 *
 * `app/src/main/res` has stray `"` text directly under `<resources>` (e.g. `values/strings.xml:309`). AGP's merger
 * ignores it (sdk-common `ValueResourceParser2.java:111` skips non-element nodes), but native aapt2 rejects the raw
 * file with "plain text not allowed here". This removes exactly that text and nothing else:
 *
 * - **No XML re-serialisation.** The non-whitespace characters of each stray run are cut out of the original text;
 *   every other character reaches aapt2 unchanged (so line numbers in aapt2 errors match the source), and character
 *   references such as `&#13;` or raw CRs are never normalised by an XML writer.
 * - **Proven equivalent.** The spliced text is parsed again and its event stream (elements, attributes, text,
 *   comments below `<resources>`) must equal the original's, or the build fails.
 * - **Hardened parser.** DTDs and external entities are disabled, and a DOCTYPE fails the build in every guard
 *   mode: a Weblate-supplied file must never pull local files into the generated resources.
 */
internal object ValuesSanitizer {
    private val CR_REFERENCE = Regex("&#(0*13|[xX]0*[dD]);")
    private const val XML_WHITESPACE = " \t\r\n"

    fun sanitise(location: String, bytes: ByteArray, findings: Findings): SanitisedFile {
        val decoded = decodeUtf8(location, bytes, findings)
        // A byte order mark is kept for aapt2 but is not part of the XML the parser sees.
        val bom = if (decoded.startsWith('\uFEFF')) "\uFEFF" else ""
        val text = decoded.removePrefix(bom)
        val original = scan(location, text, findings)
        CR_REFERENCE.findAll(text).forEach { match ->
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            findings.add(
                FindingKind.CR_CHARACTER_REFERENCE, location, "line $line",
                "'${match.value}' is kept for aapt2, but the APK's value depends on how AGP's merger writes it",
            )
        }
        if (original.stray == 0) return SanitisedFile(decoded, original.declared, 0)

        val runs = strayRuns(text)
        // Only the run's non-whitespace characters go, so aapt2's line numbers still point at the source lines.
        val spliced = buildString {
            var from = 0
            runs.forEach { range ->
                append(text, from, range.first)
                append(text.substring(range).filter { it in XML_WHITESPACE })
                from = range.last + 1
            }
            append(text, from, text.length)
        }
        // Scratch findings: the re-scan must not report the same elements twice (BROKEN still throws).
        val after = scan(location, spliced, Findings(findings.mode))
        if (runs.size != original.stray || after.stray != 0 || after.events != original.events) {
            findings.add(
                FindingKind.SANITISER_MISMATCH, location, "",
                "StAX saw ${original.stray} stray text node(s), the splice removed ${runs.size}, " +
                    "${after.stray} remain; events equal: ${after.events == original.events}",
            )
        }
        runs.forEach { range ->
            val line = text.substring(0, range.first).count { it == '\n' } + 1
            findings.add(FindingKind.STRAY_TEXT_REMOVED, location, "line $line", text.substring(range).trim())
        }
        return SanitisedFile(bom + spliced, original.declared, runs.size)
    }

    private fun decodeUtf8(location: String, bytes: ByteArray, findings: Findings): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        findings.add(FindingKind.MALFORMED_XML, location, "", "not valid UTF-8: $e")
        error("unreachable")
    }

    private class Scan(val events: List<String>, val declared: Set<Pair<String, String>>, val stray: Int)

    private fun factory(): XMLInputFactory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    /** Canonical event list of everything except text directly under `<resources>`. */
    private fun scan(location: String, text: String, findings: Findings): Scan {
        val events = ArrayList<String>()
        val declared = LinkedHashSet<Pair<String, String>>()
        var stray = 0
        var depth = 0
        val pendingText = StringBuilder()
        fun flushText() {
            if (pendingText.isNotEmpty()) events += "T:$pendingText"
            pendingText.setLength(0)
        }
        try {
            val reader = factory().createXMLStreamReader(StringReader(text))
            val encoding = reader.characterEncodingScheme
            if (encoding != null && !encoding.equals("utf-8", ignoreCase = true)) {
                findings.add(FindingKind.MALFORMED_XML, location, "", "declares encoding '$encoding', expected UTF-8")
            }
            while (reader.hasNext()) {
                when (val event = reader.next()) {
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_DECLARATION ->
                        findings.add(FindingKind.DOCTYPE, location, "", "remove the DOCTYPE; the file is not read")
                    XMLStreamConstants.START_ELEMENT -> {
                        flushText()
                        depth++
                        events += "S:${reader.name}:${attributes(reader)}"
                        if (depth == 2) declared(location, reader, findings)?.let { declared += it }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        flushText()
                        depth--
                        events += "E:${reader.name}"
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE,
                    XMLStreamConstants.ENTITY_REFERENCE,
                    -> if (depth >= 2) {
                        pendingText.append(reader.text)
                    } else if (depth == 1 && !reader.isWhiteSpace) {
                        stray++
                    }
                    XMLStreamConstants.COMMENT -> { flushText(); events += "M:${reader.text}" }
                    XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                        flushText()
                        events += "P:${reader.piTarget}:${reader.piData}"
                    }
                    else -> if (event != XMLStreamConstants.END_DOCUMENT) flushText()
                }
            }
        } catch (e: XMLStreamException) {
            findings.add(FindingKind.MALFORMED_XML, location, "", e.message ?: e.toString())
        }
        return Scan(events, declared, stray)
    }

    private fun attributes(reader: XMLStreamReader): String = (0 until reader.attributeCount)
        .map { "${reader.getAttributeName(it)}=${reader.getAttributeValue(it)}" }.sorted().joinToString(",")

    /** The (type, name) aapt2 will decode for a direct child of `<resources>`, or null if it is not text. */
    private fun declared(location: String, reader: XMLStreamReader, findings: Findings): Pair<String, String>? {
        val name = reader.getAttributeValue(null, "name") ?: ""
        val type = when (val element = reader.localName) {
            "string", "plurals" -> element
            "item" -> reader.getAttributeValue(null, "type")
            "eat-comment", "skip" -> return null
            else -> element
        }
        if (type != "string" && type != "plurals") {
            findings.add(FindingKind.UNSUPPORTED_VALUE, location, name, "<${reader.localName}> is not generated")
            return null
        }
        return type to name
    }

    /** Character ranges of text runs directly under `<resources>` that contain more than XML whitespace. */
    private fun strayRuns(text: String): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var depth = 0
        var runStart = 0
        var i = 0
        fun closeRun(end: Int) {
            if (depth == 1 && end > runStart && text.substring(runStart, end).any { it !in XML_WHITESPACE }) {
                runs += runStart until end
            }
        }
        while (i < text.length) {
            if (text[i] != '<' || text.startsWith("<![CDATA[", i)) {
                i = if (text[i] == '<') text.indexOf("]]>", i) + 3 else i + 1
                continue
            }
            closeRun(i)
            i = when {
                text.startsWith("<!--", i) -> text.indexOf("-->", i) + 3
                text.startsWith("<?", i) -> text.indexOf("?>", i) + 2
                text.startsWith("</", i) -> text.indexOf('>', i).also { depth-- } + 1
                else -> tagEnd(text, i).also { end -> if (text[end - 1] != '/') depth++ } + 1
            }
            runStart = i
        }
        return runs
    }

    private fun tagEnd(text: String, start: Int): Int {
        var quote = ' '
        for (i in start until text.length) {
            val c = text[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
        }
        return text.length - 1
    }
}
