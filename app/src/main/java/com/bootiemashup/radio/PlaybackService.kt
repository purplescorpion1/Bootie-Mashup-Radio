package com.bootiemashup.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
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
    private var currentPolledMetadata: MediaMetadata? = null
    private var lastNowPlaying: String = ""
    private var lastNextTrack: String = ""
    private var lastArtworkUrl: String = ""
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

    private val CHANNEL_ID = "bootie_radio_playback_channel"
    private val NOTIFICATION_ID = 1001

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
            .build().apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
            }

        // Handle audio focus automatically
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        // Set up MediaItem
        val mediaItem = MediaItem.Builder()
            .setUri("https://c7.radioboss.fm:18205/stream")
            .setMediaId("bootie_mashup_stream")
            .build()
        player.setMediaItem(mediaItem)

        // Set initial playlist metadata without static title fallbacks
        val initialMetadata = MediaMetadata.Builder()
            .setArtworkUri(Uri.parse("https://c7.radioboss.fm/w/artwork/205.jpg"))
            .build()
        player.setPlaylistMetadata(initialMetadata)

        // Create PendingIntent to launch MainActivity when notification is tapped
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create MediaSession with session activity configured BEFORE preparing/playing player
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(CustomCallback())
            .build()

        // Start Foreground Notification immediately to prevent ForegroundServiceDidNotStartInTimeException
        startForegroundNotification()

        player.prepare()
        player.playWhenReady = true

        // Register player listener for toast messages on status changes and metadata persistence
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

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // Ensure stream ICY metadata does not overwrite polled metadata
                currentPolledMetadata?.let { polled ->
                    if (player.playlistMetadata != polled) {
                        player.playlistMetadata = polled
                    }
                }
            }
        })

        // Start background metadata and artwork polling
        startMetadataPolling()

        // Initialize notification button layout
        updateNotificationLayout()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bootie Radio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio streaming for Bootie Mashup Radio"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = currentPolledMetadata?.displayTitle?.toString()
            ?: currentPolledMetadata?.title?.toString()
            ?: "Bootie Mashup Radio"
        val artist = currentPolledMetadata?.artist?.toString() ?: "Live Stream"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePlayPause() {
        if (player.playWhenReady) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
            player.play()
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

    private fun extractArtworkUrlFromJs(jsContent: String): String {
        val delimiters = charArrayOf('\'', '"', ' ', '\n', '\r', '\t', ';', '(', ')', '<', '>', ',')
        val tokens = jsContent.split(*delimiters)
        for (token in tokens) {
            val trimmed = token.trim()
            if ((trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) &&
                trimmed.contains(".jpg", ignoreCase = true)
            ) {
                val cleanUrl = trimmed.split("?")[0].split("#")[0]
                return if (cleanUrl.endsWith(".jpg", ignoreCase = true)) cleanUrl else trimmed
            }
        }
        return "https://c7.radioboss.fm/w/artwork/205.jpg"
    }

    private suspend fun fetchAndUpdateMetadata() {
        // 1. Fetch artwork URL from cover.js
        var artworkBaseUrl = "https://c7.radioboss.fm/w/artwork/205.jpg"
        try {
            val coverRequest = Request.Builder()
                .url("https://c7.radioboss.fm/w/cover.js?u=205")
                .build()
            okHttpClient.newCall(coverRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val jsContent = response.body?.string() ?: ""
                    artworkBaseUrl = extractArtworkUrlFromJs(jsContent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fetch now playing info from nowplayinginfo
        val request = Request.Builder()
            .url("https://c7.radioboss.fm/w/nowplayinginfo?u=205")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val nowPlaying = json.optString("nowplaying", "").trim()
                var artist = json.optString("currenttrack_artist", "").trim()
                var title = json.optString("currenttrack_title", "").trim()
                val nextTrack = json.optString("nexttrack", "").trim()

                // Only update when details actually change!
                if (nowPlaying == lastNowPlaying && nextTrack == lastNextTrack && artworkBaseUrl == lastArtworkUrl) {
                    return@use
                }

                lastNowPlaying = nowPlaying
                lastNextTrack = nextTrack
                lastArtworkUrl = artworkBaseUrl

                if (artist.isBlank() || title.isBlank()) {
                    if (nowPlaying.contains(" - ")) {
                        val parts = nowPlaying.split(" - ", limit = 2)
                        if (artist.isBlank()) artist = parts[0].trim()
                        if (title.isBlank()) title = parts[1].trim()
                    } else {
                        if (title.isBlank()) title = nowPlaying
                    }
                }

                val displayTitle = if (nowPlaying.isNotBlank()) nowPlaying else if (title.isNotBlank() && artist.isNotBlank()) "$artist - $title" else title

                val artworkUri = Uri.parse("$artworkBaseUrl?_=" + System.currentTimeMillis())

                val extras = Bundle().apply {
                    putString("next_track", nextTrack)
                }

                val updatedMetadata = MediaMetadata.Builder()
                    .setTitle(if (title.isNotBlank()) title else displayTitle)
                    .setArtist(artist)
                    .setArtworkUri(artworkUri)
                    .setDisplayTitle(displayTitle)
                    .setExtras(extras)
                    .build()

                currentPolledMetadata = updatedMetadata

                withContext(Dispatchers.Main) {
                    player.playlistMetadata = updatedMetadata
                    startForegroundNotification()
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
                .add(SessionCommand("ACTION_TOGGLE_PLAY_PAUSE", Bundle.EMPTY))
                .build()
            val availablePlayerCommands = connectionResult.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_PREPARE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_SET_VOLUME)
                .add(Player.COMMAND_GET_VOLUME)
                .build()
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                availablePlayerCommands
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "ACTION_TOGGLE_MUTE" -> {
                    toggleMute()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                "ACTION_TOGGLE_PLAY_PAUSE" -> {
                    togglePlayPause()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }
}
