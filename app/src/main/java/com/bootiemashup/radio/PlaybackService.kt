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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var isMuted = false
    private var pollingJob: Job? = null
    companion object {
        val okHttpClient: OkHttpClient by lazy {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            val sslContext = SSLContext.getInstance("SSL").apply {
                init(null, trustAllCerts, SecureRandom())
            }

            val headerInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                val requestWithHeaders = originalRequest.newBuilder()
                    .header("Origin", "https://bootiemashup.com")
                    .header("Referer", "https://bootiemashup.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0")
                    .build()
                chain.proceed(requestWithHeaders)
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(headerInterceptor)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Create track selector to disable ICY/stream metadata tracks
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                .build()
        }

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(okHttpDataSourceFactory)

        // Create player with track selector and okhttp media source factory
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
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
                delay(5000) // Poll every 5 seconds
            }
        }
    }

    private suspend fun fetchAndUpdateMetadata() {
        val request = Request.Builder()
            .url("https://c7.radioboss.fm/w/nowplayinginfo?u=205&nl=1")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val nowPlaying = json.optString("nowplaying", "")
                var artist = json.optString("currenttrack_artist", "")
                var title = json.optString("currenttrack_title", "")
                val nextTrack = json.optString("nexttrack", "")

                if (artist.isBlank() || title.isBlank()) {
                    if (nowPlaying.contains(" - ")) {
                        val parts = nowPlaying.split(" - ", limit = 2)
                        if (artist.isBlank()) artist = parts[0].trim()
                        if (title.isBlank()) title = parts[1].trim()
                    } else {
                        if (title.isBlank()) title = if (nowPlaying.isNotBlank()) nowPlaying else "Live Stream"
                        if (artist.isBlank()) artist = "Bootie Mashup Radio"
                    }
                }

                val displayTitle = if (nowPlaying.isNotBlank()) nowPlaying else "$artist - $title"

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
                        .setDisplayTitle(displayTitle)
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
