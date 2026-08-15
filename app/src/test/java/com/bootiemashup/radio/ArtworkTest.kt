package com.bootiemashup.radio

import androidx.media3.common.MediaMetadata
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {

    @Test
    fun testFetchArtworkAndMediaMetadata() {
        val request = Request.Builder()
            .url("https://c7.radioboss.fm/w/artwork/205.jpg?_=" + System.currentTimeMillis())
            .build()

        PlaybackService.okHttpClient.newCall(request).execute().use { response ->
            println("Response code: ${response.code}")
            assertTrue(response.isSuccessful)
            val body = response.body
            assertNotNull(body)
            if (body != null) {
                val bytes = body.bytes()
                assertNotNull(bytes)
                println("Downloaded bytes size: ${bytes.size}")
                assertTrue(bytes.isNotEmpty())

                val metadata = MediaMetadata.Builder()
                    .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()

                assertNotNull(metadata.artworkData)
                println("Metadata artworkData size: ${metadata.artworkData?.size}")
            }
        }
    }

    @Test
    fun testMetadataMappingLogic() {
        val rawArtist = "Martinn"
        val rawTitle = "Boys Of Power (Coldplay vs. Don Henley)"
        val nowPlaying = "Martinn - Boys Of Power (Coldplay vs. Don Henley)"

        var artist = rawArtist.trim()
        var title = rawTitle.trim()

        if (artist.isBlank() || title.isBlank()) {
            if (nowPlaying.contains(" - ")) {
                val parts = nowPlaying.split(" - ", limit = 2)
                if (artist.isBlank()) artist = parts[0].trim()
                if (title.isBlank()) title = parts[1].trim()
            } else {
                if (title.isBlank()) title = nowPlaying
            }
        }

        val trackTitle = if (title.isNotBlank()) title else if (nowPlaying.isNotBlank()) nowPlaying else "Bootie Mashup Radio"
        val trackArtist = if (artist.isNotBlank()) artist else "Bootie Mashup Radio"
        val displayTitleStr = if (nowPlaying.isNotBlank()) nowPlaying else if (artist.isNotBlank() && title.isNotBlank()) "$artist - $title" else trackTitle

        assertEquals("Martinn", trackArtist)
        assertEquals("Boys Of Power (Coldplay vs. Don Henley)", trackTitle)
        assertEquals("Martinn - Boys Of Power (Coldplay vs. Don Henley)", displayTitleStr)

        val metadata = MediaMetadata.Builder()
            .setTitle(trackTitle)
            .setArtist(trackArtist)
            .setAlbumTitle("Bootie Mashup Radio")
            .setAlbumArtist("Bootie Mashup Radio")
            .setDisplayTitle(displayTitleStr)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()

        assertEquals("Boys Of Power (Coldplay vs. Don Henley)", metadata.title?.toString())
        assertEquals("Martinn", metadata.artist?.toString())
        assertEquals("Bootie Mashup Radio", metadata.albumTitle?.toString())
        assertEquals("Bootie Mashup Radio", metadata.albumArtist?.toString())
        assertEquals("Martinn - Boys Of Power (Coldplay vs. Don Henley)", metadata.displayTitle?.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, metadata.mediaType)
    }

    @Test
    fun testFallbackMetadataParsing() {
        val rawArtist = ""
        val rawTitle = ""
        val nowPlaying = "DJs From Mars - Best Of 2024"

        var artist = rawArtist.trim()
        var title = rawTitle.trim()

        if (artist.isBlank() || title.isBlank()) {
            if (nowPlaying.contains(" - ")) {
                val parts = nowPlaying.split(" - ", limit = 2)
                if (artist.isBlank()) artist = parts[0].trim()
                if (title.isBlank()) title = parts[1].trim()
            } else {
                if (title.isBlank()) title = nowPlaying
            }
        }

        val trackTitle = if (title.isNotBlank()) title else if (nowPlaying.isNotBlank()) nowPlaying else "Bootie Mashup Radio"
        val trackArtist = if (artist.isNotBlank()) artist else "Bootie Mashup Radio"

        assertEquals("DJs From Mars", trackArtist)
        assertEquals("Best Of 2024", trackTitle)
    }
}
