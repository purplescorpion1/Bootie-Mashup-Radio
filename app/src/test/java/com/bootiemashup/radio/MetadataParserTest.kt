package com.bootiemashup.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataParserTest {

    @Test
    fun testParseTrackInfoWithArtistAndTitleInNowPlaying() {
        val nowPlaying = "Martinn - Boys Of Power (Coldplay vs. Don Henley)"
        val trackInfo = MetadataParser.parseTrackInfo(nowPlaying)

        assertEquals("Martinn", trackInfo.artist)
        assertEquals("Boys Of Power (Coldplay vs. Don Henley)", trackInfo.title)
        assertEquals("Martinn - Boys Of Power (Coldplay vs. Don Henley)", trackInfo.nowPlaying)
    }

    @Test
    fun testParseTrackInfoWithComplexTitle() {
        val nowPlaying = "Thriftshop XL - Trick Me Laura (Kelis vs. Scissor Sisters)"
        val trackInfo = MetadataParser.parseTrackInfo(
            nowPlaying = nowPlaying,
            rawArtist = "Thriftshop XL",
            rawTitle = "Trick Me Laura (Kelis vs. Scissor Sisters)",
            nextTrack = "Next Track Example"
        )

        assertEquals("Thriftshop XL", trackInfo.artist)
        assertEquals("Trick Me Laura (Kelis vs. Scissor Sisters)", trackInfo.title)
        assertEquals("Next Track Example", trackInfo.nextTrack)
    }

    @Test
    fun testParseTrackInfoWithoutHyphen() {
        val nowPlaying = "Bootie Radio Special Mix"
        val trackInfo = MetadataParser.parseTrackInfo(nowPlaying)

        assertEquals("", trackInfo.artist)
        assertEquals("Bootie Radio Special Mix", trackInfo.title)
    }
}
