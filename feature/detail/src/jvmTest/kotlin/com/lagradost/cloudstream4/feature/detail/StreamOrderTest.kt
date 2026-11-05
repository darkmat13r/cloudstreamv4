package com.lagradost.cloudstream4.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamOrderTest {

    private fun link(name: String, quality: Int?) = StreamLink(name, null, "https://a/$name", null, null, null, quality = quality)

    @Test
    fun `the highest resolution comes first`() {
        val links = listOf(link("sd", 480), link("uhd", 2160), link("hd", 720), link("fhd", 1080))
        assertEquals(listOf("uhd", "fhd", "hd", "sd"), links.bestFirst().map { it.label })
    }

    @Test
    fun `an unknown quality ranks with 480p, as the old app's, and so below 720p and above 360p`() {
        // Qualities.Unknown is 400, and its default priority is 4, the same as 480p's.
        val links = listOf(link("low", 360), link("unknown", 400), link("none", null), link("hd", 720), link("sd", 480))
        assertEquals(listOf("hd", "unknown", "none", "sd", "low"), links.bestFirst().map { it.label })
    }

    @Test
    fun `equal ranks keep the order they arrived in`() {
        val links = listOf(link("a", 1080), link("b", 1080), link("c", 1080))
        assertEquals(listOf("a", "b", "c"), links.bestFirst().map { it.label })
    }

    @Test
    fun `an odd number ranks as the nearest named quality, and Auto's 0 ranks last`() {
        assertEquals(rankOf(720), rankOf(800))
        assertEquals(rankOf(480), rankOf(534))
        assertEquals(rankOf(240), rankOf(266))
        assertEquals(rankOf(2160), rankOf(4320))
        assertEquals(0, rankOf(0))
        // Exactly between 720 and 1080: the first of the two in Qualities' order, which is 720.
        assertEquals(rankOf(720), rankOf(900))
    }

    @Test
    fun `a failure moves to the next best, then the next`() {
        val uhd = link("uhd", 2160)
        val found = listOf(link("sd", 480), uhd, link("fhd", 1080), link("hd", 720))
        val queue = StreamQueue(uhd)
        val order = generateSequence { queue.next(found)?.also(queue::chosen) }.map { it.label }.toList()
        assertEquals(listOf("fhd", "hd", "sd"), order)
    }

    @Test
    fun `once every link has been tried there is no next, so it stops`() {
        val one = link("one", 1080)
        val two = link("two", 720)
        val queue = StreamQueue(one)
        assertEquals(two, queue.next(listOf(one, two)))
        queue.chosen(two)
        assertNull(queue.next(listOf(one, two)))
        assertNull(queue.next(listOf(one, two)), "and asking again does not go round")
    }

    @Test
    fun `a better link that arrives late is next, even though it ranks above the one that failed`() {
        val hd = link("hd", 720)
        val queue = StreamQueue(hd)
        val sd = link("sd", 480)
        val late = link("late", 2160)
        assertEquals(late, queue.next(listOf(hd, sd, late)))
    }

    @Test
    fun `a link arriving after all have failed is tried`() {
        val one = link("one", 720)
        val queue = StreamQueue(one)
        assertNull(queue.next(listOf(one)))
        val later = link("later", 360)
        assertEquals(later, queue.next(listOf(one, later)))
    }

    @Test
    fun `the same stream under a newer answer is not tried twice`() {
        val one = link("one", 1080)
        val queue = StreamQueue(one)
        assertNull(queue.next(listOf(one.copy(subtitles = emptyList(), label = "renamed"))))
    }

    @Test
    fun `unknown qualities are tried in the old app's place, not skipped`() {
        val first = link("hd", 720)
        val queue = StreamQueue(first)
        val found = listOf(first, link("low", 360), link("unknown", 400))
        assertEquals("unknown", queue.next(found)?.label)
    }
}
