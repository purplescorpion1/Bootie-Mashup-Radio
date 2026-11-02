package com.bootiemashup.radio

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArraySet

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
    fun testSessionListenerNotification() {
        val listeners = CopyOnWriteArraySet<Player.Listener>()
        var receivedTitle: String? = null

        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                receivedTitle = mediaMetadata.title?.toString()
            }
        }

        listeners.add(listener)

        val updatedMetadata = MediaMetadata.Builder()
            .setTitle("Live Radio Track")
            .setArtist("Bootie Mashup Artist")
            .build()

        for (l in listeners) {
            l.onMediaMetadataChanged(updatedMetadata)
        }

        assertEquals("Live Radio Track", receivedTitle)
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

    @Test
    fun testMetadataHelperParseFullJson() {
        val jsonStr = """
            {
                "nowplaying": "Victor Serna - Ir-roar-placeable",
                "currenttrack_artist": "Victor Serna",
                "currenttrack_title": "Ir-roar-placeable",
                "nexttrack": "DJs From Mars - Mashup Next"
            }
        """.trimIndent()

        val result = MetadataHelper.parseNowPlayingJson(jsonStr)
        assertEquals("Ir-roar-placeable", result.title)
        assertEquals("Victor Serna", result.artist)
        assertEquals("Victor Serna - Ir-roar-placeable", result.displayTitle)
        assertEquals("DJs From Mars - Mashup Next", result.nextTrack)
        assertTrue(result.artworkUriString.startsWith(MetadataHelper.ARTWORK_BASE_URL))

        val mediaMetadata = MetadataHelper.buildMediaMetadata(result, byteArrayOf(1, 2, 3))
        assertEquals("Ir-roar-placeable", mediaMetadata.title?.toString())
        assertEquals("Victor Serna", mediaMetadata.artist?.toString())
        assertEquals("Bootie Mashup Radio", mediaMetadata.albumTitle?.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, mediaMetadata.mediaType)
        assertNotNull(mediaMetadata.artworkData)
        assertEquals(3, mediaMetadata.artworkData?.size)
    }

    @Test
    fun testMetadataHelperFallbackParsing() {
        val jsonStr = """
            {
                "nowplaying": "Lobsterdust - Stayin' Alive In The Wall",
                "currenttrack_artist": "",
                "currenttrack_title": "",
                "nexttrack": ""
            }
        """.trimIndent()

        val result = MetadataHelper.parseNowPlayingJson(jsonStr)
        assertEquals("Stayin' Alive In The Wall", result.title)
        assertEquals("Lobsterdust", result.artist)
        assertEquals("Lobsterdust - Stayin' Alive In The Wall", result.displayTitle)
        assertEquals("", result.nextTrack)

        val mediaMetadata = MetadataHelper.buildMediaMetadata(result)
        assertEquals("Stayin' Alive In The Wall", mediaMetadata.title?.toString())
        assertEquals("Lobsterdust", mediaMetadata.artist?.toString())
    }

    @Test
    fun testMetadataHelperEmptyFallback() {
        val jsonStr = """
            {
                "nowplaying": "",
                "currenttrack_artist": "",
                "currenttrack_title": "",
                "nexttrack": ""
            }
        """.trimIndent()

        val result = MetadataHelper.parseNowPlayingJson(jsonStr)
        assertEquals("Bootie Mashup Radio", result.title)
        assertEquals("Bootie Mashup Radio", result.artist)
        assertEquals("Bootie Mashup Radio", result.displayTitle)
    }
}
