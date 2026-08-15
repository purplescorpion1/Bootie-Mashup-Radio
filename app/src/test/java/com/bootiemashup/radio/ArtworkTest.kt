package com.bootiemashup.radio

import androidx.media3.common.MediaMetadata
import okhttp3.Request
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
            val bytes = response.body?.bytes()
            assertNotNull(bytes)
            println("Downloaded bytes size: ${bytes?.size}")
            assertTrue((bytes?.size ?: 0) > 0)

            val metadata = MediaMetadata.Builder()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()

            assertNotNull(metadata.artworkData)
            println("Metadata artworkData size: ${metadata.artworkData?.size}")
        }
    }
}
