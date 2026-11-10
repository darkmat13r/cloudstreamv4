package com.lagradost.cloudstream3.ui.subtitles

import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * `BUG-77`: the subtitle picker's fonts are free faces now, under the resource names the proprietary ones had.
 *
 * The picker stores a choice as its `R.font` id ([SaveCaptionStyle.typeface]), never its label, and no font resource
 * was added, removed or renamed, so an id saved before the change is the same id after it. These tests hold the
 * other half: that a stored id still loads, and that what it loads is the face the picker's label now names, read
 * from the font file's own name table rather than trusted from its file name.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleFontsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var before: SaveCaptionStyle? = null

    @Before
    fun keepTheStoredStyle() {
        instrumentation.runOnMainSync { before = getKey<SaveCaptionStyle>(SUBTITLE_KEY) }
    }

    @After
    fun putTheStoredStyleBack() {
        instrumentation.runOnMainSync {
            before?.let { setKey(SUBTITLE_KEY, it) } ?: context.removeKey(SUBTITLE_KEY)
        }
    }

    @Test
    fun aSavedFontChoiceStillLoadsAndIsTheFreeFace() {
        val saved = SubtitlesFragment.getCurrentSavedStyle().copy(typeface = R.font.netflix_sans, typefaceFilePath = null)
        var read: SaveCaptionStyle? = null
        instrumentation.runOnMainSync {
            setKey(SUBTITLE_KEY, saved)
            read = getKey<SaveCaptionStyle>(SUBTITLE_KEY)
        }

        val id = checkNotNull(checkNotNull(read) { "the stored style reads back" }.typeface) { "a typeface is stored" }
        assertEquals("the stored value is the R.font id", R.font.netflix_sans, id)
        assertNotNull("the id loads a typeface", ResourcesCompat.getFont(context, id))
        assertEquals("Inter", familyOf(id))
    }

    @Test
    fun everyPickerFontIsTheFaceItsLabelNames() {
        // The picker's order and labels (`SubtitlesFragment`), and the family each file's name table must start with.
        val expected = listOf(
            R.font.trebuchet_ms to "Fira Sans",
            R.font.netflix_sans to "Inter",
            R.font.open_sans to "Open Sans",
            R.font.futura to "Jost",
            R.font.consola to "JetBrains Mono",
            R.font.gotham to "Montserrat",
            R.font.lucida_grande to "Source Sans 3",
            R.font.stix_general to "STIX Two Text",
            R.font.times_new_roman to "Liberation Serif",
            R.font.verdana to "DejaVu Sans",
            R.font.ubuntu_regular to "Ubuntu",
            R.font.comic_sans to "Comic Neue",
            R.font.poppins_regular to "Poppins",
        )
        expected.forEach { (id, label) ->
            assertNotNull("$label loads", ResourcesCompat.getFont(context, id))
            val family = familyOf(id)
            assertTrue("$label's file says it is $family", family.startsWith(label))
        }
    }

    /**
     * `google_sans` is a family file over the twelve `productsans_*` files, which are static Google Sans instances. Google
     * Sans runs from 400 to 700, so Thin and Light are the 400 instance and Black the 700 one; each italic is cut from the
     * italic file. Read from each file's `OS/2` table.
     */
    @Test
    fun theGoogleSansFamilyIsGoogleSansAtEveryWeight() {
        assertNotNull("the family loads", ResourcesCompat.getFont(context, R.font.google_sans))
        listOf(
            R.font.productsans_thin to 400, R.font.productsans_light to 400, R.font.productsans_regular to 400,
            R.font.productsans_medium to 500, R.font.productsans_bold to 700, R.font.productsans_black to 700,
        ).forEach { (id, weight) -> assertFace(id, weight, italic = false) }
        listOf(
            R.font.productsans_thinitalic to 400, R.font.productsans_lightitalic to 400, R.font.productsans_italic to 400,
            R.font.productsans_mediumitalic to 500, R.font.productsans_bolditalic to 700, R.font.productsans_blackitalic to 700,
        ).forEach { (id, weight) -> assertFace(id, weight, italic = true) }
    }

    private fun assertFace(id: Int, weight: Int, italic: Boolean) {
        val name = context.resources.getResourceEntryName(id)
        val family = familyOf(id)
        assertTrue("$name says it is $family", family.startsWith("Google Sans"))
        val os2 = table(id, "OS/2")
        assertEquals("$name's weight", weight, os2.getShort(OS2_WEIGHT).toInt() and 0xFFFF)
        assertEquals("$name is italic", italic, os2.getShort(OS2_SELECTION).toInt() and ITALIC_BIT != 0)
    }

    /** One table of the font file, positioned at its start. */
    private fun table(id: Int, tag: String): ByteBuffer {
        val bytes = context.resources.openRawResource(id).use { it.readBytes() }
        val font = ByteBuffer.wrap(bytes)
        val tables = font.getShort(4).toInt() and 0xFFFF
        val offset = (0 until tables).map { 12 + 16 * it }
            .first { String(bytes, it, 4, Charsets.US_ASCII) == tag }
            .let { font.getInt(it + 8) }
        return ByteBuffer.wrap(bytes, offset, bytes.size - offset).slice()
    }

    /** The font family (name ID 1) from the file's `name` table, Windows Unicode record. */
    private fun familyOf(id: Int): String {
        val bytes = context.resources.openRawResource(id).use { it.readBytes() }
        val font = ByteBuffer.wrap(bytes)
        val tables = font.getShort(4).toInt() and 0xFFFF
        val name = (0 until tables).map { 12 + 16 * it }
            .first { String(bytes, it, 4, Charsets.US_ASCII) == "name" }
            .let { font.getInt(it + 8) }
        val count = font.getShort(name + 2).toInt() and 0xFFFF
        val strings = name + (font.getShort(name + 4).toInt() and 0xFFFF)
        for (i in 0 until count) {
            val record = name + 6 + 12 * i
            val platform = font.getShort(record).toInt()
            val nameId = font.getShort(record + 6).toInt()
            if (platform == WINDOWS && nameId == FAMILY) {
                val length = font.getShort(record + 8).toInt() and 0xFFFF
                val offset = font.getShort(record + 10).toInt() and 0xFFFF
                return String(bytes, strings + offset, length, Charsets.UTF_16BE)
            }
        }
        error("no family name in ${context.resources.getResourceEntryName(id)}")
    }

    private companion object {
        const val WINDOWS = 3
        const val FAMILY = 1
        const val OS2_WEIGHT = 4
        const val OS2_SELECTION = 62
        const val ITALIC_BIT = 1
    }
}
