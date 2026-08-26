package com.bootiemashup.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFallbackTest {

    @Test
    fun testStreamConstants() {
        assertEquals("https://c7.radioboss.fm:18205/stream", PlaybackService.PRIMARY_STREAM_URL)
        assertEquals("https://c7.radioboss.fm/stream/205", PlaybackService.FALLBACK_STREAM_URL)
    }

    @Test
    fun testFallbackStreamUrlValidation() {
        val isValid = PlaybackService.checkStreamUrlValid(PlaybackService.FALLBACK_STREAM_URL)
        assertTrue("Fallback stream URL should return a valid response (HTTP 200)", isValid)
    }

    @Test
    fun testInvalidStreamUrlValidation() {
        val isValid = PlaybackService.checkStreamUrlValid("https://invalid.domain.that.does.not.exist.example/stream")
        assertFalse("Invalid URL should return false", isValid)
    }
}
