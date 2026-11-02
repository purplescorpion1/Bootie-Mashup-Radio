package com.bootiemashup.radio

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

data class MetadataResult(
    val title: String,
    val artist: String,
    val displayTitle: String,
    val nextTrack: String,
    val artworkUriString: String
) {
    val artworkUri: Uri?
        get() = try { Uri.parse(artworkUriString) } catch (e: Exception) { null }
}

object MetadataHelper {

    const val ARTWORK_BASE_URL = "https://c7.radioboss.fm/w/artwork/205.jpg"
    const val NOW_PLAYING_URL = "https://c7.radioboss.fm/w/nowplayinginfo?u=205"

    fun parseNowPlayingJson(jsonStr: String): MetadataResult {
        val json = JSONObject(jsonStr)
        val nowPlaying = (json.optString("nowplaying") ?: "").trim()
        var artist = (json.optString("currenttrack_artist") ?: "").trim()
        var title = (json.optString("currenttrack_title") ?: "").trim()
        val nextTrack = (json.optString("nexttrack") ?: "").trim()

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

        val timestampedArtworkUrl = "$ARTWORK_BASE_URL?_=" + System.currentTimeMillis()

        return MetadataResult(
            title = trackTitle,
            artist = trackArtist,
            displayTitle = displayTitleStr,
            nextTrack = nextTrack,
            artworkUriString = timestampedArtworkUrl
        )
    }

    fun buildMediaMetadata(result: MetadataResult, artworkBytes: ByteArray? = null): MediaMetadata {
        val extras = try {
            Bundle().apply {
                putString("next_track", result.nextTrack)
            }
        } catch (e: Exception) {
            null
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(result.title)
            .setArtist(result.artist)
            .setAlbumTitle("Bootie Mashup Radio")
            .setAlbumArtist("Bootie Mashup Radio")
            .setDisplayTitle(result.displayTitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        result.artworkUri?.let {
            metadataBuilder.setArtworkUri(it)
        }

        if (extras != null) {
            metadataBuilder.setExtras(extras)
        }

        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return metadataBuilder.build()
    }
}
