package com.bootiemashup.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.media3.session.MediaStyleNotificationHelper
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
    private var currentArtworkBitmap: Bitmap? = null
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
                    startForegroundNotification()
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
        when (intent?.action) {
            "ACTION_TOGGLE_PLAY_PAUSE" -> togglePlayPause()
            "ACTION_TOGGLE_MUTE" -> toggleMute()
        }
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

        val trackTitle = currentPolledMetadata?.title?.toString()
        val artistName = currentPolledMetadata?.artist?.toString()

        val displayTitle = if (!trackTitle.isNullOrBlank()) trackTitle else (currentPolledMetadata?.displayTitle?.toString() ?: "Bootie Mashup Radio")
        val displayArtist = if (!artistName.isNullOrBlank()) artistName else "Live Stream"

        val playPauseIntent = Intent(applicationContext, PlaybackService::class.java).apply {
            action = "ACTION_TOGGLE_PLAY_PAUSE"
        }
        val playPausePendingIntent = PendingIntent.getService(
            applicationContext,
            101,
            playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val muteIntent = Intent(applicationContext, PlaybackService::class.java).apply {
            action = "ACTION_TOGGLE_MUTE"
        }
        val mutePendingIntent = PendingIntent.getService(
            applicationContext,
            102,
            muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_arrow_white_24dp
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val muteIcon = if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp
        val muteTitle = if (isMuted) "Unmute" else "Mute"

        val playPauseAction = NotificationCompat.Action.Builder(
            playPauseIcon,
            playPauseTitle,
            playPausePendingIntent
        ).build()

        val muteAction = NotificationCompat.Action.Builder(
            muteIcon,
            muteTitle,
            mutePendingIntent
        ).build()

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayTitle)
            .setContentText(displayArtist)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(playPauseAction)
            .addAction(muteAction)

        currentArtworkBitmap?.let {
            notificationBuilder.setLargeIcon(it)
        }

        mediaSession?.let { session ->
            notificationBuilder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        val notification = notificationBuilder.build()

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
        startForegroundNotification()
    }

    fun toggleMute() {
        isMuted = !isMuted
        player.volume = if (isMuted) 0f else 1f
        showToast(if (isMuted) "Audio Muted" else "Audio Unmuted")
        updateNotificationLayout()
        startForegroundNotification()
    }

    fun isAudioMuted(): Boolean {
        return isMuted
    }

    private fun updateNotificationLayout() {
        val mediaSessionInstance = mediaSession ?: return
        val muteCommand = SessionCommand("ACTION_TOGGLE_MUTE", Bundle.EMPTY)
        val muteButton = CommandButton.Builder(if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp)
            .setSessionCommand(muteCommand)
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
            .url("https://c7.radioboss.fm/w/nowplayinginfo?u=205")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val nowPlaying = json.optString("nowplaying", "").trim()
                val rawArtist = json.optString("currenttrack_artist", "").trim()
                val rawTitle = json.optString("currenttrack_title", "").trim()
                val nextTrack = json.optString("nexttrack", "").trim()

                val trackInfo = MetadataParser.parseTrackInfo(nowPlaying, rawArtist, rawTitle, nextTrack)

                val nowPlayingChanged = (trackInfo.nowPlaying != lastNowPlaying)

                if (!nowPlayingChanged && trackInfo.nextTrack == lastNextTrack) {
                    return@use
                }

                if (nowPlayingChanged && lastNowPlaying.isNotEmpty()) {
                    // Wait 1 second after now playing details update before reloading artwork
                    delay(1000)
                }

                lastNowPlaying = trackInfo.nowPlaying
                lastNextTrack = trackInfo.nextTrack

                val artworkBaseUrl = "https://c7.radioboss.fm/w/artwork/205.jpg"

                val displayTitle = if (trackInfo.nowPlaying.isNotBlank()) {
                    trackInfo.nowPlaying
                } else if (trackInfo.title.isNotBlank() && trackInfo.artist.isNotBlank()) {
                    "${trackInfo.artist} - ${trackInfo.title}"
                } else {
                    trackInfo.title
                }

                val timestampedArtworkUrl = "$artworkBaseUrl?_=" + System.currentTimeMillis()
                val artworkUri = Uri.parse(timestampedArtworkUrl)

                var artworkBytes: ByteArray? = null
                try {
                    val artworkRequest = Request.Builder()
                        .url(timestampedArtworkUrl)
                        .build()
                    okHttpClient.newCall(artworkRequest).execute().use { artworkResponse ->
                        if (artworkResponse.isSuccessful) {
                            artworkBytes = artworkResponse.body?.bytes()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val extras = Bundle().apply {
                    putString("next_track", trackInfo.nextTrack)
                }

                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(if (trackInfo.title.isNotBlank()) trackInfo.title else displayTitle)
                    .setArtist(trackInfo.artist)
                    .setArtworkUri(artworkUri)
                    .setDisplayTitle(displayTitle)
                    .setExtras(extras)

                if (artworkBytes != null && artworkBytes!!.isNotEmpty()) {
                    metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    try {
                        currentArtworkBitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes!!.size)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    currentArtworkBitmap = null
                }

                val updatedMetadata = metadataBuilder.build()

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        pollingJob?.cancel()
        player.stop()
        player.release()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
