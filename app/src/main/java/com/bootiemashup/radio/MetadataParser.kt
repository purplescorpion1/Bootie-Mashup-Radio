package com.bootiemashup.radio

data class TrackInfo(
    val artist: String,
    val title: String,
    val nowPlaying: String,
    val nextTrack: String
)

object MetadataParser {
    fun parseTrackInfo(
        nowPlaying: String,
        rawArtist: String = "",
        rawTitle: String = "",
        nextTrack: String = ""
    ): TrackInfo {
        val trimmedNowPlaying = nowPlaying.trim()
        var artist = rawArtist.trim()
        var title = rawTitle.trim()

        if (artist.isBlank() || title.isBlank()) {
            if (trimmedNowPlaying.contains(" - ")) {
                val parts = trimmedNowPlaying.split(" - ", limit = 2)
                if (artist.isBlank()) artist = parts[0].trim()
                if (title.isBlank()) title = parts[1].trim()
            } else if (trimmedNowPlaying.contains("-")) {
                val parts = trimmedNowPlaying.split("-", limit = 2)
                if (artist.isBlank()) artist = parts[0].trim()
                if (title.isBlank()) title = parts[1].trim()
            } else {
                if (artist.isBlank() && title.isBlank()) {
                    title = trimmedNowPlaying
                }
            }
        }

        return TrackInfo(
            artist = artist,
            title = title,
            nowPlaying = trimmedNowPlaying,
            nextTrack = nextTrack.trim()
        )
    }
}
