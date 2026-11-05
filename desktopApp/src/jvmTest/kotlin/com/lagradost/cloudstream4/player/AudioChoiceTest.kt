package com.lagradost.cloudstream4.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The audio track someone chose, carried to the next source, whose tracks have their own ids. */
class AudioChoiceTest {

    private val englishFirst = AudioTracks(
        listOf(AudioTrack(1, "Track 1 - [English]", 6, "eac3"), AudioTrack(2, "Track 2 - [Hindi]", 2, "mp4a")),
        selected = 1,
    )

    @Test
    fun `nothing chosen leaves the new stream on its own default`() {
        assertNull(AudioChoice().match(englishFirst))
    }

    @Test
    fun `a chosen language is found on the next source under another id`() {
        val choice = AudioChoice()
        choice.chosen(AudioTrack(7, "Hindi - [Hindi]", 6, "eac3"))
        assertEquals(2, choice.match(englishFirst))
    }

    @Test
    fun `the same description wins over the same language`() {
        val choice = AudioChoice()
        choice.chosen(AudioTrack(4, "Commentary - [English]"))
        val next = AudioTracks(
            listOf(AudioTrack(10, "Track 1 - [English]"), AudioTrack(11, "Commentary - [English]")),
            selected = 10,
        )
        assertEquals(11, choice.match(next))
    }

    @Test
    fun `a source without that language keeps its own choice`() {
        val choice = AudioChoice()
        choice.chosen(AudioTrack(3, "Track 3 - [German]"))
        assertNull(choice.match(englishFirst))
    }

    @Test
    fun `the match already playing needs no command`() {
        val choice = AudioChoice()
        choice.chosen(AudioTrack(9, "Track 9 - [English]"))
        assertNull(choice.match(englishFirst))
    }

    @Test
    fun `a track with no language is matched only by its whole description`() {
        val choice = AudioChoice()
        choice.chosen(AudioTrack(2, "Track 2"))
        assertEquals(5, choice.match(AudioTracks(listOf(AudioTrack(4, "Track 1"), AudioTrack(5, "Track 2")), selected = 4)))
        assertNull(choice.match(AudioTracks(listOf(AudioTrack(4, "Track 1"), AudioTrack(6, "Track 3")), selected = 4)))
    }
}
