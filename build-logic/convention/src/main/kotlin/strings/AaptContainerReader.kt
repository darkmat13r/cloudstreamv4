package strings

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One aapt2-decoded `Item`: what `aapt2 compile --legacy` stored, never re-interpreted here. */
internal sealed interface DecodedItem {
    /** `String.value`: unstyled text after aapt2's quote, escape and whitespace processing. */
    data class Text(val text: String) : DecodedItem

    /** `StyledString`: the text plus spans rendered as `tag:first-last;` (UTF-16, inclusive). */
    data class Styled(val text: String, val spans: String) : DecodedItem

    /** `Reference`: [name] is `string/x`, `android:string/x`, `attr/x` or empty (`@null`). */
    data class Ref(val name: String, val isAttribute: Boolean) : DecodedItem

    /** Anything else (`RawString`, `Primitive`, `FileReference`, `Id`): not representable as text. */
    data class Unsupported(val what: String) : DecodedItem
}

internal sealed interface DecodedValue {
    data class Single(val item: DecodedItem) : DecodedValue

    /** Plural entries by arity name (`zero` … `other`), in the order aapt2 stored them. */
    data class Plural(val items: List<Pair<String, DecodedItem>>) : DecodedValue

    data class Other(val what: String) : DecodedValue
}

/** A decoded resource of type `string` or `plurals`, keyed by its entry name. */
internal data class DecodedEntry(val type: String, val name: String, val value: DecodedValue)

/**
 * Reads the `*.arsc.flat` files `aapt2 compile` writes: the AAPT container format
 * (`frameworks/base/tools/aapt2/format/Container.cpp`, magic `AAPT`, entry type 0 = `ResTable`) wrapping a
 * protobuf `aapt.pb.ResourceTable` (`frameworks/base/tools/aapt2/Resources.proto`).
 *
 * The protobuf is read with a minimal wire reader over the stable field numbers below instead of `aapt2-proto`
 * + `protobuf-java`, which are on AGP's runtime classpath but not build-logic's compile classpath and would be a
 * new dependency. Independent checks of this reader, which do not share its decode path:
 *  - `GenerateComposeStringsTask` counts `<string>`/`<plurals>` elements per file in the sanitiser (StAX) and
 *    fails if the decoded entry count differs;
 *  - `:core:strings:fixtures` compares the decoded table with a checked-in golden TSV whose values were produced
 *    with `protobuf-java` and reviewed by hand (quotes, spaces, styled text, references, plural arities).
 */
internal object AaptContainerReader {
    private const val MAGIC = 0x54504141 // "AAPT", little endian
    private const val ENTRY_RES_TABLE = 0
    private val ARITY = listOf("zero", "one", "two", "few", "many", "other")

    /** Decodes every `string` and `plurals` entry of one compiled values file. */
    fun read(flat: ByteArray): List<DecodedEntry> = containerTables(flat).flatMap(::table)

    private fun containerTables(bytes: ByteArray): List<ByteArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(buffer.int == MAGIC) { "not an AAPT container" }
        buffer.int // format version
        val count = buffer.int
        return (0 until count).mapNotNull {
            val type = buffer.int
            val length = buffer.long.toInt()
            val data = ByteArray(length).also(buffer::get)
            buffer.position(buffer.position() + (4 - length % 4) % 4)
            data.takeIf { type == ENTRY_RES_TABLE }
        }
    }

    // ResourceTable.package(2) -> Package.type(3) -> Type.name(2), entry(3) -> Entry.name(2), config_value(6)
    // ConfigValue.value(2) -> Value.item(4) | Value.compound_value(5) -> CompoundValue.plural(5)
    private fun table(bytes: ByteArray): List<DecodedEntry> = buildList {
        for (pkg in fields(bytes).allBytes(2)) for (type in fields(pkg).allBytes(3)) {
            val typeFields = fields(type)
            val typeName = typeFields.bytes(2).utf8()
            if (typeName != "string" && typeName != "plurals") continue
            for (entry in typeFields.allBytes(3)) {
                val entryFields = fields(entry)
                val name = entryFields.bytes(2).utf8()
                val configValues = entryFields.allBytes(6)
                check(configValues.size == 1) { "$typeName/$name has ${configValues.size} values in one file" }
                add(DecodedEntry(typeName, name, value(fields(fields(configValues.single()).bytes(2)))))
            }
        }
    }

    private fun value(valueFields: List<Field>): DecodedValue {
        valueFields.bytes(4)?.let { return DecodedValue.Single(item(it)) }
        val compound = valueFields.bytes(5) ?: return DecodedValue.Other("empty value")
        // Plural(1: repeated Entry) -> Entry.arity(3), Entry.item(4)
        val plural = fields(compound).bytes(5) ?: return DecodedValue.Other("compound value")
        return DecodedValue.Plural(
            fields(plural).allBytes(1).map { entry ->
                val entryFields = fields(entry)
                ARITY[entryFields.varint(3).toInt()] to item(entryFields.bytes(4))
            },
        )
    }

    // Item: ref(1) str(2) raw_str(3) styled_str(4) file(5) id(6) prim(7)
    private fun item(bytes: ByteArray?): DecodedItem {
        val itemFields = fields(bytes)
        itemFields.bytes(2)?.let { return DecodedItem.Text(fields(it).bytes(1).utf8()) }
        itemFields.bytes(4)?.let { styled ->
            // StyledString.value(1), span(2) -> Span.tag(1), first_char(2), last_char(3)
            val styledFields = fields(styled)
            val spans = styledFields.allBytes(2).joinToString("") { span ->
                val spanFields = fields(span)
                "${spanFields.bytes(1).utf8()}:${spanFields.varint(2)}-${spanFields.varint(3)};"
            }
            return DecodedItem.Styled(styledFields.bytes(1).utf8(), spans)
        }
        if (itemFields.any { it.number == 1 }) {
            // Reference.type(1) REFERENCE=0 / ATTRIBUTE=1, name(3)
            val refFields = fields(itemFields.bytes(1))
            return DecodedItem.Ref(refFields.bytes(3).utf8(), isAttribute = refFields.varint(1) == 1L)
        }
        val kinds = mapOf(3 to "raw string", 5 to "file reference", 6 to "id", 7 to "primitive")
        return DecodedItem.Unsupported(itemFields.firstNotNullOfOrNull { kinds[it.number] } ?: "empty item")
    }

    private class Field(val number: Int, val varint: Long, val bytes: ByteArray?)

    private fun fields(bytes: ByteArray?): List<Field> {
        if (bytes == null) return emptyList()
        val out = ArrayList<Field>()
        var position = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (position < bytes.size) {
            val tag = readVarint()
            val number = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> out += Field(number, readVarint(), null)
                1 -> position += 8
                2 -> {
                    val length = readVarint().toInt()
                    out += Field(number, 0, bytes.copyOfRange(position, position + length))
                    position += length
                }
                5 -> position += 4
                else -> error("unsupported protobuf wire type in field $number")
            }
        }
        return out
    }

    private fun List<Field>.bytes(number: Int) = firstOrNull { it.number == number && it.bytes != null }?.bytes

    private fun List<Field>.allBytes(number: Int) = filter { it.number == number }.mapNotNull { it.bytes }

    private fun List<Field>.varint(number: Int) = firstOrNull { it.number == number && it.bytes == null }?.varint ?: 0L

    private fun ByteArray?.utf8() = this?.toString(Charsets.UTF_8) ?: ""
}
