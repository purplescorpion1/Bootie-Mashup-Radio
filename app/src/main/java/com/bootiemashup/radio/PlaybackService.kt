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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
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
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
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
    private lateinit var sessionPlayer: ForwardingPlayer
    private var isMuted = false
    private var pollingJob: Job? = null
    private var currentPolledMetadata: MediaMetadata? = null
    private var lastNowPlaying: String = ""
    private var lastNextTrack: String = ""
    private var lastArtworkUrl: String = ""

    private val sessionListeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

    private fun notifySessionMetadataChanged(metadata: MediaMetadata) {
        Handler(Looper.getMainLooper()).post {
            val timeline = sessionPlayer.currentTimeline
            val activeItem = sessionPlayer.currentMediaItem
            for (listener in sessionListeners) {
                try {
                    listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                    listener.onMediaItemTransition(activeItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    listener.onMediaMetadataChanged(metadata)
                    listener.onPlaylistMetadataChanged(metadata)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val okHttpBitmapLoader = object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            return if (bitmap != null) {
                Futures.immediateFuture(bitmap)
            } else {
                Futures.immediateFailedFuture(IllegalArgumentException("Failed to decode bitmap"))
            }
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            val future = SettableFuture.create<Bitmap>()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = Request.Builder().url(uri.toString()).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body
                        if (response.isSuccessful && body != null) {
                            val bytes = body.bytes()
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                future.set(bitmap)
                                return@launch
                            }
                        }
                    }
                    future.setException(IOException("Failed to load bitmap from $uri"))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
            val data = metadata.artworkData
            if (data != null && data.isNotEmpty()) {
                return decodeBitmap(data)
            }
            val uri = metadata.artworkUri
            if (uri != null) {
                return loadBitmap(uri)
            }
            return null
        }
    }
    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.bootiemashup.radio.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_TOGGLE_MUTE = "com.bootiemashup.radio.ACTION_TOGGLE_MUTE"

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

        // Initial Metadata setup
        val initialMetadata = MediaMetadata.Builder()
            .setTitle("Bootie Mashup Radio")
            .setArtist("Live Stream")
            .setAlbumTitle("Bootie Mashup Radio")
            .setAlbumArtist("Bootie Mashup Radio")
            .setDisplayTitle("Bootie Mashup Radio")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(Uri.parse("https://c7.radioboss.fm/w/artwork/205.jpg"))
            .build()

        val initialMediaItem = MediaItem.Builder()
            .setUri("https://c7.radioboss.fm:18205/stream")
            .setMediaId("bootie_mashup_stream")
            .setMediaMetadata(initialMetadata)
            .build()
        player.setMediaItem(initialMediaItem)
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

        // Create ForwardingPlayer so MediaSession delegates getCurrentTimeline, getCurrentMediaItem, getMediaMetadata, and getPlaylistMetadata to currentPolledMetadata
        sessionPlayer = object : ForwardingPlayer(player) {
            override fun addListener(listener: Player.Listener) {
                sessionListeners.add(listener)
                super.addListener(listener)
            }

            override fun removeListener(listener: Player.Listener) {
                sessionListeners.remove(listener)
                super.removeListener(listener)
            }

            override fun getCurrentTimeline(): Timeline {
                val timeline = super.getCurrentTimeline()
                if (timeline.isEmpty) return timeline
                val metadata = currentPolledMetadata ?: return timeline
                return object : Timeline() {
                    override fun getWindowCount(): Int = timeline.windowCount
                    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                        val w = timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
                        w.mediaItem = w.mediaItem.buildUpon().setMediaMetadata(metadata).build()
                        return w
                    }
                    override fun getPeriodCount(): Int = timeline.periodCount
                    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                        return timeline.getPeriod(periodIndex, period, setIds)
                    }
                    override fun getIndexOfPeriod(uid: Any): Int = timeline.getIndexOfPeriod(uid)
                    override fun getUidOfPeriod(periodIndex: Int): Any = timeline.getUidOfPeriod(periodIndex)
                }
            }

            override fun getCurrentMediaItem(): MediaItem? {
                val item = super.getCurrentMediaItem() ?: return null
                val metadata = currentPolledMetadata ?: return item
                return item.buildUpon().setMediaMetadata(metadata).build()
            }

            override fun getMediaItemAt(index: Int): MediaItem {
                val item = super.getMediaItemAt(index)
                val metadata = currentPolledMetadata ?: return item
                return item.buildUpon().setMediaMetadata(metadata).build()
            }

            override fun getMediaMetadata(): MediaMetadata {
                return currentPolledMetadata ?: super.getMediaMetadata()
            }

            override fun getPlaylistMetadata(): MediaMetadata {
                return currentPolledMetadata ?: super.getPlaylistMetadata()
            }

            override fun play() {
                if (!player.playWhenReady) {
                    player.stop()
                    player.prepare()
                }
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady && !player.playWhenReady) {
                    player.stop()
                    player.prepare()
                }
                super.setPlayWhenReady(playWhenReady)
            }
        }

        // Create MediaSession with session activity and custom BitmapLoader using sessionPlayer
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(okHttpBitmapLoader)
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
                updateNotificationLayout()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // Ensure stream ICY metadata does not overwrite polled metadata
                currentPolledMetadata?.let { polled ->
                    notifySessionMetadataChanged(polled)
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
            ACTION_TOGGLE_PLAY_PAUSE -> {
                togglePlayPause()
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
            else -> {
                startForegroundNotification()
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
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

        val title = currentPolledMetadata?.title?.toString()
            ?: currentPolledMetadata?.displayTitle?.toString()
            ?: "Bootie Mashup Radio"
        val artist = currentPolledMetadata?.artist?.toString() ?: "Live Stream"

        var artworkBitmap: Bitmap? = null
        val artworkBytes = currentPolledMetadata?.artworkData
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            artworkBitmap = BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size)
        }

        val isPlaying = player.playWhenReady
        val playPauseIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            1,
            playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIconRes = if (isPlaying) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_arrow_white_24dp
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = NotificationCompat.Action.Builder(
            playPauseIconRes,
            playPauseTitle,
            playPausePendingIntent
        ).build()

        val muteIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            2,
            muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteIconRes = if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp
        val muteTitle = if (isMuted) "Unmute" else "Mute"
        val muteAction = NotificationCompat.Action.Builder(
            muteIconRes,
            muteTitle,
            mutePendingIntent
        ).build()

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Bootie Mashup Radio")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(playPauseAction)
            .addAction(muteAction)

        if (artworkBitmap != null) {
            notificationBuilder.setLargeIcon(artworkBitmap)
        }

        val session = mediaSession
        if (session != null) {
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
            player.stop()
            player.prepare()
            player.play()
        }
        updateNotificationLayout()
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

        val isPlaying = player.playWhenReady
        val playPauseCommand = SessionCommand("ACTION_TOGGLE_PLAY_PAUSE", Bundle.EMPTY)
        val playPauseIconRes = if (isPlaying) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_arrow_white_24dp
        @Suppress("DEPRECATION")
        val playPauseButton = CommandButton.Builder()
            .setSessionCommand(playPauseCommand)
            .setIconResId(playPauseIconRes)
            .setCustomIconResId(playPauseIconRes)
            .setDisplayName(if (isPlaying) "Pause" else "Play")
            .setEnabled(true)
            .build()

        val muteCommand = SessionCommand("ACTION_TOGGLE_MUTE", Bundle.EMPTY)
        val muteIconRes = if (isMuted) R.drawable.ic_volume_off_white_24dp else R.drawable.ic_volume_up_white_24dp
        @Suppress("DEPRECATION")
        val muteButton = CommandButton.Builder()
            .setSessionCommand(muteCommand)
            .setIconResId(muteIconRes)
            .setCustomIconResId(muteIconRes)
            .setDisplayName(if (isMuted) "Unmute" else "Mute")
            .setEnabled(true)
            .build()

        // MediaSession custom buttons in MediaStyle notification
        mediaSessionInstance.setCustomLayout(listOf(playPauseButton, muteButton))

        // Also update foreground notification actions
        startForegroundNotification()
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
            val body = response.body
            if (response.isSuccessful && body != null) {
                val jsonStr = body.string()
                val json = JSONObject(jsonStr)
                val nowPlaying = json.optString("nowplaying", "").trim()
                var artist = json.optString("currenttrack_artist", "").trim()
                var title = json.optString("currenttrack_title", "").trim()
                val nextTrack = json.optString("nexttrack", "").trim()

                val nowPlayingChanged = (nowPlaying != lastNowPlaying) || (currentPolledMetadata == null)

                if (!nowPlayingChanged && nextTrack == lastNextTrack) {
                    return@use
                }

                if (nowPlayingChanged && lastNowPlaying.isNotEmpty()) {
                    // Wait 1 second after now playing details update before reloading artwork
                    delay(1000)
                }

                lastNowPlaying = nowPlaying
                lastNextTrack = nextTrack

                val artworkBaseUrl = "https://c7.radioboss.fm/w/artwork/205.jpg"

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

                val timestampedArtworkUrl = "$artworkBaseUrl?_=" + System.currentTimeMillis()
                val artworkUri = Uri.parse(timestampedArtworkUrl)

                var artworkBytes: ByteArray? = null
                try {
                    val artworkRequest = Request.Builder()
                        .url(timestampedArtworkUrl)
                        .build()
                    okHttpClient.newCall(artworkRequest).execute().use { artworkResponse ->
                        val artworkBody = artworkResponse.body
                        if (artworkResponse.isSuccessful && artworkBody != null) {
                            artworkBytes = artworkBody.bytes()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val extras = Bundle().apply {
                    putString("next_track", nextTrack)
                }

                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(trackTitle)
                    .setArtist(trackArtist)
                    .setAlbumTitle("Bootie Mashup Radio")
                    .setAlbumArtist("Bootie Mashup Radio")
                    .setDisplayTitle(displayTitleStr)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)

                if (artworkBytes != null && artworkBytes!!.isNotEmpty()) {
                    metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }

                val updatedMetadata = metadataBuilder.build()
                currentPolledMetadata = updatedMetadata

                withContext(Dispatchers.Main) {
                    player.playlistMetadata = updatedMetadata
                    notifySessionMetadataChanged(updatedMetadata)
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
