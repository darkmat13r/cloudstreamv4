package com.lagradost.cloudstream4.media.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

/** Markup is removed and never interpreted. */
class CueTextTest {

    private fun plain(raw: String) = CueText.toPlainLines(raw)

    @Test
    fun `html style tags go and their text stays`() {
        assertEquals(listOf("Hello there"), plain("<i>Hello</i> <b>there</b>"))
        assertEquals(listOf("red"), plain("<font color=\"#ff0000\" face='x'>red</font>"))
        assertEquals(listOf("upper"), plain("<B>upper</B>"))
    }

    @Test
    fun `ssa override blocks go`() {
        assertEquals(listOf("Top"), plain("{\\an8}Top"))
        assertEquals(listOf("Styled text"), plain("{\\i1}Styled{\\i0} {\\c&H0000FF&}text"))
    }

    @Test
    fun `braces that are not ssa blocks stay`() {
        assertEquals(listOf("{not a tag}"), plain("{not a tag}"))
    }

    @Test
    fun `line break tags split the line in any spelling`() {
        assertEquals(listOf("a", "b", "c", "d"), plain("a<br>b<BR/>c<br />d"))
    }

    @Test
    fun `tags that only start with br do not break the line`() {
        assertEquals(listOf("ab"), plain("a<brand>b"))
    }

    @Test
    fun `a less than sign that opens no tag is text`() {
        assertEquals(listOf("1 < 2"), plain("1 < 2"))
        assertEquals(listOf("I <3 you"), plain("I <3 you"))
        assertEquals(listOf("<3>"), plain("<3>"))
        assertEquals(listOf("<"), plain("<"))
    }

    @Test
    fun `a tag with no close on its line is text`() {
        assertEquals(listOf("x <b and more"), plain("x <b and more"))
        assertEquals(listOf("x {\\an8 more"), plain("x {\\an8 more"))
    }

    @Test
    fun `vtt timestamp tags go`() {
        assertEquals(listOf("one two"), plain("<00:00:01.000>one <00:02.5>two"))
    }

    @Test
    fun `scripts and event handlers are reduced to harmless text`() {
        assertEquals(listOf("alert(1)"), plain("<script>alert(1)</script>"))
        assertEquals(listOf("x"), plain("<img src=x onerror=alert(1)>x"))
        assertEquals(listOf("x"), plain("<a href=\"javascript:alert(1)\">x</a>"))
        assertEquals(listOf("x"), plain("<!-- comment -->x<?php evil ?>"))
    }

    @Test
    fun `one pass can leave tag shaped text which is why output is not markup`() {
        assertEquals(listOf("<script>"), plain("<<b>script>"))
    }

    @Test
    fun `named references decode`() {
        assertEquals(listOf("Tom & Jerry"), plain("Tom &amp; Jerry"))
        assertEquals(listOf("<>\"'"), plain("&lt;&gt;&quot;&apos;"))
    }

    @Test
    fun `numeric references decode in both bases and beyond the bmp`() {
        assertEquals(listOf("it's"), plain("it&#39;s"))
        assertEquals(listOf("it's"), plain("it&#x27;s"))
        assertEquals(listOf("it's"), plain("it&#X27;s"))
        assertEquals(listOf("\uD83D\uDE00"), plain("&#x1F600;"))
        assertEquals(listOf("\uD83D\uDE00"), plain("&#128512;"))
    }

    @Test
    fun `references decode exactly once`() {
        assertEquals(listOf("&lt;b&gt;"), plain("&amp;lt;b&amp;gt;"))
    }

    @Test
    fun `an escaped tag is text the author wanted shown`() {
        assertEquals(listOf("<b>"), plain("&lt;b&gt;"))
    }

    @Test
    fun `unknown and malformed references stay as text`() {
        for (text in listOf(
            "AT&T", "a & b", "&", "&;", "&#;", "&#x;", "&bogus;", "&amp", "&#xZZ;", "&#12a;",
            "&#x110000;", "&#xD800;", "&#xFFFFFFFF;", "&#99999999;", "&verylongnamehere;",
        )) {
            assertEquals(listOf(text), plain(text), text)
        }
    }

    @Test
    fun `a reference cannot smuggle a control character or line break`() {
        assertEquals(listOf("ab"), plain("a&#0;b"))
        assertEquals(listOf("ab"), plain("a&#10;b"))
        assertEquals(listOf("ab"), plain("a&#x1B;b"))
        assertEquals(listOf("ab"), plain("a&#x9F;b"))
    }

    @Test
    fun `control characters and stray boms are dropped`() {
        assertEquals(listOf("abc"), plain("a\u0000b\u001B\u007F\u0085\uFEFFc"))
    }

    @Test
    fun `tabs and unusual spaces become plain spaces`() {
        assertEquals(listOf("a b c d e"), plain("a\tb\u00A0c\u2003d\u205Fe"))
        assertEquals(listOf("a b"), plain("a&nbsp;b"))
        assertEquals(listOf("a b c"), plain("a\u2028b\u2029c"))
    }

    @Test
    fun `surrogate pairs survive and lone surrogates do not`() {
        assertEquals(listOf("\uD83D\uDE00"), plain("\uD83D\uDE00"))
        assertEquals(listOf("ab"), plain("a\uD83Db"))
        assertEquals(listOf("ab"), plain("a\uDE00b"))
        assertEquals(listOf("a"), plain("a\uD83D"))
    }

    @Test
    fun `right to left controls and zero width spaces inside text stay`() {
        assertEquals(listOf("\u202Bمرحبا\u202C"), plain("\u202Bمرحبا\u202C"))
        assertEquals(listOf("a\u200Bb"), plain("a\u200Bb"))
        assertEquals(listOf("a\u3000b"), plain("a\u3000b"))
    }

    @Test
    fun `outer whitespace goes and a line of nothing gives no line`() {
        assertEquals(listOf("x"), plain("  x\t"))
        assertEquals(emptyList(), plain("   "))
        assertEquals(emptyList(), plain("\u200B"))
        assertEquals(emptyList(), plain("<i></i>"))
        assertEquals(listOf("a", "b"), plain("a<br><br> <br>b"))
    }
}
