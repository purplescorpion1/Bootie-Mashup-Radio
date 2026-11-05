package com.bootiemashup.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFallbackTest {

    @Test
    fun testStreamConstants() {
        assertEquals(2L, PlaybackService.RETRY_INTERVAL_SECONDS)
        assertEquals("https://c7.radioboss.fm:18205/stream", PlaybackService.PRIMARY_STREAM_URL)
        assertEquals("https://c7.radioboss.fm/stream/205", PlaybackService.FALLBACK_STREAM_URL)
    }

    @Test
    fun testFallbackStreamUrlValidation() {
        val isValid = PlaybackService.checkStreamUrlValid(PlaybackService.FALLBACK_STREAM_URL)
        assertTrue("Fallback stream URL should return a valid response (HTTP 200)", isValid)
    }

    @Test
    fun testPrimaryStreamUrlValidation() {
        val isValid = PlaybackService.checkStreamUrlValid(PlaybackService.PRIMARY_STREAM_URL)
        assertTrue("Primary stream URL should return a valid response (HTTP 200)", isValid)
    }

    @Test
    fun testInvalidStreamUrlValidation() {
        val isValid = PlaybackService.checkStreamUrlValid("https://invalid.domain.that.does.not.exist.example/stream")
        assertFalse("Invalid URL should return false", isValid)
    }

    @Test
    fun testDetermineSessionStream() {
        // Primary valid -> should choose primary stream
        assertEquals(
            PlaybackService.PRIMARY_STREAM_URL,
            PlaybackService.determineSessionStream(primaryValid = true, fallbackValid = true)
        )
        assertEquals(
            PlaybackService.PRIMARY_STREAM_URL,
            PlaybackService.determineSessionStream(primaryValid = true, fallbackValid = false)
        )

        // Primary invalid, fallback valid -> should choose fallback stream
        assertEquals(
            PlaybackService.FALLBACK_STREAM_URL,
            PlaybackService.determineSessionStream(primaryValid = false, fallbackValid = true)
        )

        // Both invalid -> should default to primary stream
        assertEquals(
            PlaybackService.PRIMARY_STREAM_URL,
            PlaybackService.determineSessionStream(primaryValid = false, fallbackValid = false)
        )
    }
}
