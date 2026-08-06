package com.bootiemashup.radio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var isMuted = false
    private var pollingJob: Job? = null
    private val okHttpClient = OkHttpClient()

    override fun onCreate() {
        super.onCreate()

        // Create track selector to disable ICY/stream metadata tracks
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                .build()
        }

        // Create player with track selector
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()

        // Handle audio focus automatically
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        // Set up play when ready and prepare stream
        val mediaItem = MediaItem.Builder()
            .setUri("https://c7.radioboss.fm:18205/stream")
            .setMediaId("bootie_mashup_stream")
            .build()
        player.setMediaItem(mediaItem)

        // Set initial playlist metadata
        val initialMetadata = MediaMetadata.Builder()
            .setTitle("Live Stream")
            .setArtist("Bootie Mashup Radio")
            .setAlbumTitle("Bootie Mashup Radio")
            .setArtworkUri(Uri.parse("https://c7.radioboss.fm/w/artwork/205.jpg"))
            .build()
        player.setPlaylistMetadata(initialMetadata)

        player.prepare()
        player.playWhenReady = true

        // Create PendingIntent to launch MainActivity when the notification is tapped
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create MediaSession with session activity configured
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(CustomCallback())
            .build()

        // Register player listener for toast messages on status changes
        player.addListener(object : Player.Listener {
            private var lastState: Boolean? = null

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (lastState != playWhenReady) {
                    lastState = playWhenReady
                    if (playWhenReady) {
                        showToast("Audio Playing")
                    } else {
                        showToast("Audio Paused")
                    }
                }
            }
        })

        // Start background metadata and artwork polling
        startMetadataPolling()

        // Initialize notification button layout
        updateNotificationLayout()
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
        player.volume = if (isMuted) 0f else 1f
        showToast(if (isMuted) "Audio Muted" else "Audio Unmuted")
        updateNotificationLayout()
    }

    fun isAudioMuted(): Boolean {
        return isMuted
    }

    private fun updateNotificationLayout() {
        val mediaSessionInstance = mediaSession ?: return
        val muteCommand = SessionCommand("ACTION_TOGGLE_MUTE", Bundle.EMPTY)
        val muteButton = CommandButton.Builder()
            .setSessionCommand(muteCommand)
            .setIconResId(if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp)
            .setDisplayName(if (isMuted) "Unmute" else "Mute")
            .setEnabled(true)
            .build()

        // MediaSession custom buttons in MediaStyle notification
        mediaSessionInstance.setCustomLayout(listOf(muteButton))
    }

    private fun startMetadataPolling() {
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    fetchAndUpdateMetadata()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(10000) // Poll every 10 seconds
            }
        }
    }

    private suspend fun fetchAndUpdateMetadata() {
        val request = Request.Builder()
            .url("https://c7.radioboss.fm/w/nowplayinginfo?u=205")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val nowPlaying = json.optString("nowplaying", "Bootie Mashup Radio")
                val artist = json.optString("currenttrack_artist", "Bootie Mashup Radio")
                val title = json.optString("currenttrack_title", "Live Stream")
                val nextTrack = json.optString("nexttrack", "")

                withContext(Dispatchers.Main) {
                    val artworkUri = Uri.parse("https://c7.radioboss.fm/w/artwork/205.jpg?_=" + System.currentTimeMillis())

                    val extras = Bundle().apply {
                        putString("next_track", nextTrack)
                    }

                    val updatedMetadata = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle("Bootie Mashup Radio")
                        .setArtworkUri(artworkUri)
                        .setDisplayTitle(nowPlaying)
                        .setExtras(extras)
                        .build()

                    player.setPlaylistMetadata(updatedMetadata)

                    // Also update the current media item's metadata to seamlessly update the system notification
                    val currentItem = player.currentMediaItem
                    if (currentItem != null) {
                        val updatedItem = currentItem.buildUpon()
                            .setMediaMetadata(updatedMetadata)
                            .build()
                        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        player.release()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        showToast("Audio Stopped")
        super.onDestroy()
    }

    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand("ACTION_TOGGLE_MUTE", Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                connectionResult.availablePlayerCommands
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "ACTION_TOGGLE_MUTE") {
                toggleMute()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }
}
