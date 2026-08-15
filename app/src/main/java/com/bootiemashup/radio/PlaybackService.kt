package com.bootiemashup.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.ui.PlayerNotificationManager
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
    private var playerNotificationManager: PlayerNotificationManager? = null
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

        // Set initial playlist and media item metadata
        val initialMetadata = MediaMetadata.Builder()
            .setTitle("Bootie Mashup Radio")
            .setArtist("Live Stream")
            .setAlbumTitle("Bootie Mashup Radio")
            .setArtworkUri(Uri.parse("https://c7.radioboss.fm/w/artwork/205.jpg"))
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri("https://c7.radioboss.fm:18205/stream")
            .setMediaId("bootie_mashup_stream")
            .setMediaMetadata(initialMetadata)
            .build()

        player.setMediaItem(mediaItem)
        player.playlistMetadata = initialMetadata

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

        // Initialize PlayerNotificationManager
        createNotificationChannel()
        setupPlayerNotificationManager()

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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Background audio streaming for Bootie Mashup Radio"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupPlayerNotificationManager() {
        val descriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence {
                val trackTitle = currentPolledMetadata?.title?.toString()
                val rawNowPlaying = currentPolledMetadata?.displayTitle?.toString()
                return when {
                    !trackTitle.isNullOrBlank() -> trackTitle
                    !rawNowPlaying.isNullOrBlank() -> rawNowPlaying
                    else -> "Bootie Mashup Radio"
                }
            }

            override fun getCurrentContentText(player: Player): CharSequence? {
                val artistName = currentPolledMetadata?.artist?.toString()
                return if (!artistName.isNullOrBlank()) artistName else "Live Stream"
            }

            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback
            ): Bitmap? {
                return currentArtworkBitmap
            }

            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                return PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        }

        val customActionReceiver = object : PlayerNotificationManager.CustomActionReceiver {
            override fun createCustomActions(
                context: Context,
                instanceId: Int
            ): Map<String, NotificationCompat.Action> {
                val mutePendingIntent = PendingIntent.getService(
                    context,
                    102,
                    Intent(context, PlaybackService::class.java).apply { action = "ACTION_TOGGLE_MUTE" },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val iconRes = if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp
                val title = if (isMuted) "Unmute" else "Mute"
                val action = NotificationCompat.Action.Builder(iconRes, title, mutePendingIntent).build()
                return mapOf("ACTION_TOGGLE_MUTE" to action)
            }

            override fun getCustomActions(player: Player): List<String> {
                return listOf("ACTION_TOGGLE_MUTE")
            }

            override fun onCustomAction(player: Player, action: String, intent: Intent) {
                if (action == "ACTION_TOGGLE_MUTE") {
                    toggleMute()
                }
            }
        }

        val notificationListener = object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(
                notificationId: Int,
                notification: Notification,
                ongoing: Boolean
            ) {
                if (ongoing) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(notificationId, notification)
                    }
                }
            }

            override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (dismissedByUser) {
                    stopSelf()
                }
            }
        }

        playerNotificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            CHANNEL_ID
        )
            .setMediaDescriptionAdapter(descriptionAdapter)
            .setCustomActionReceiver(customActionReceiver)
            .setNotificationListener(notificationListener)
            .setSmallIconResourceId(R.mipmap.ic_launcher)
            .build().apply {
                setPlayer(player)
                setUseNextAction(false)
                setUsePreviousAction(false)
                setUseFastForwardAction(false)
                setUseRewindAction(false)
                setUsePlayPauseActions(true)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                mediaSession?.let { session ->
                    val token = session.platformToken
                    if (token is android.media.session.MediaSession.Token) {
                        setMediaSessionToken(token)
                    }
                }
            }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        playerNotificationManager?.invalidate()
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
        val iconRes = if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp
        val muteCommand = SessionCommand("ACTION_TOGGLE_MUTE", Bundle.EMPTY)
        @Suppress("DEPRECATION")
        val muteButton = CommandButton.Builder()
            .setSessionCommand(muteCommand)
            .setIconResId(iconRes)
            .setCustomIconResId(iconRes)
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

                val titleToSet = if (trackInfo.title.isNotBlank()) trackInfo.title else displayTitle
                val artistToSet = if (trackInfo.artist.isNotBlank()) trackInfo.artist else "Bootie Mashup Radio"

                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(titleToSet)
                    .setArtist(artistToSet)
                    .setAlbumTitle("Bootie Mashup Radio")
                    .setAlbumArtist(artistToSet)
                    .setDisplayTitle(titleToSet)
                    .setArtworkUri(artworkUri)
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
        playerNotificationManager?.setPlayer(null)
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
        playerNotificationManager?.setPlayer(null)
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
