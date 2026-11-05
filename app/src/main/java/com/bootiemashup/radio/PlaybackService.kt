@file:SuppressLint("RestrictedApi")

package com.bootiemashup.radio

import android.annotation.SuppressLint

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import androidx.media3.session.legacy.MediaDescriptionCompat
import androidx.media3.session.legacy.MediaMetadataCompat
import androidx.media3.session.legacy.MediaSessionCompat
import androidx.media3.session.legacy.PlaybackStateCompat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds
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
    private var trackSequenceNumber: Long = 0L

    private var currentStreamUrl: String = PRIMARY_STREAM_URL
    private var retryJob: Job? = null
    private var isRetrying = false

    private var isCastingActive = false
    private var lastCastTrackTitle: String = ""
    private var castSessionListener: SessionManagerListener<CastSession>? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val okHttpBitmapLoader = object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            val processed = MetadataHelper.processArtworkBytes(data, MetadataHelper.MAX_ARTWORK_DIMENSION)
            val bitmap = processed?.first ?: BitmapFactory.decodeByteArray(data, 0, data.size)
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
                        if (response.isSuccessful) {
                            val bytes = response.body.bytes()
                            val processed = MetadataHelper.processArtworkBytes(bytes, MetadataHelper.MAX_ARTWORK_DIMENSION)
                            val bitmap = processed?.first ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
        const val RETRY_INTERVAL_SECONDS = 2L
        const val PRIMARY_STREAM_URL = "https://c7.radioboss.fm:18205/stream"
        const val FALLBACK_STREAM_URL = "https://c7.radioboss.fm/stream/205"

        const val ACTION_TOGGLE_PLAY_PAUSE = "com.bootiemashup.radio.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_TOGGLE_MUTE = "com.bootiemashup.radio.ACTION_TOGGLE_MUTE"
        const val ACTION_STOP = "com.bootiemashup.radio.ACTION_STOP"

        fun determineSessionStream(primaryValid: Boolean, fallbackValid: Boolean): String {
            return if (primaryValid) {
                PRIMARY_STREAM_URL
            } else if (fallbackValid) {
                FALLBACK_STREAM_URL
            } else {
                PRIMARY_STREAM_URL
            }
        }

        fun checkStreamUrlValid(url: String): Boolean {
            return try {
                val request = Request.Builder()
                    .url(url)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (_: Exception) {
                false
            }
        }

        const val CHANNEL_ID = "bootie_radio_playback_channel"
        const val NOTIFICATION_ID = 1001

        val okHttpClient: OkHttpClient by lazy {
            val trustAllCerts = arrayOf<TrustManager>(
                @SuppressLint("CustomX509TrustManager")
                object : X509TrustManager {
                    @SuppressLint("TrustAllX509TrustManager")
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    @SuppressLint("TrustAllX509TrustManager")
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
            .build().apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
            }

        // Handle audio focus automatically
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        // Initial Metadata setup
        val initialMetadata = MediaMetadata.Builder()
            .setTitle("Bootie Mashup Radio")
            .setArtist("Live Stream")
            .setAlbumTitle("Bootie Mashup Radio")
            .setAlbumArtist("Bootie Mashup Radio")
            .setDisplayTitle("Bootie Mashup Radio")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(MetadataHelper.ARTWORK_BASE_URL.toUri())
            .build()
        currentPolledMetadata = initialMetadata

        // Initial MediaItem with initial metadata so player.mediaMetadata is populated immediately
        val initialMediaItem = MediaItem.Builder()
            .setUri(currentStreamUrl)
            .setMediaId("bootie_mashup_stream")
            .setMediaMetadata(initialMetadata)
            .build()
        player.setMediaItem(initialMediaItem)
        player.playlistMetadata = initialMetadata

        // Create PendingIntent supporting both Android TV Leanback launcher and standard mobile launcher
        val sessionActivityIntent = createLaunchPendingIntent()

        // Native Media3 Setup directly using ExoPlayer instance
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setBitmapLoader(okHttpBitmapLoader)
            .setCallback(CustomCallback())
            .build()

        // Sync legacy MediaSessionCompat sessionActivity and initial metadata
        // This ensures the Android TV "Open" button works and lock screen / TV card show station info from startup
        getSessionCompat()?.setSessionActivity(sessionActivityIntent)
        updateLegacySessionMetadata(initialMetadata, null)

        // Keep notification visible even when player is buffering/idle
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        // Start Foreground Notification immediately
        updateForegroundNotification(startInForegroundRequired = true)

        // Setup Cast session tracking directly in PlaybackService so background service
        // keeps track of active casting when screen is off and MainActivity is paused/destroyed
        setupCastSessionTracking()

        val isCastActive = isCastingActive
        acquireLocks()
        player.prepare()
        player.playWhenReady = !isCastActive

        // Register player listener for status updates
        player.addListener(object : Player.Listener {
            private var lastState: Boolean? = null

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (lastState != playWhenReady) {
                    lastState = playWhenReady
                    if (playWhenReady) {
                        acquireLocks()
                        showToast("Audio Playing")
                    } else {
                        releaseLocks()
                        stopRetryLoop()
                        showToast("Audio Paused")
                    }
                }
                updateNotificationLayout()
                syncLegacyPlaybackState(playWhenReady)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                if (playbackState == Player.STATE_READY) {
                    stopRetryLoop()
                    syncLegacyPlaybackState(player.playWhenReady)
                } else if (playbackState == Player.STATE_BUFFERING) {
                    // Keep Bluetooth connection alive during network buffering
                    if (player.playWhenReady) {
                        syncLegacyPlaybackState(isPlaying = true, isBuffering = true)
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    if (player.playWhenReady) {
                        // Keep Bluetooth connected in buffering state while reconnecting
                        syncLegacyPlaybackState(isPlaying = true, isBuffering = true)
                        startRetryLoop()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                if (player.playWhenReady) {
                    // Keep Bluetooth connected in buffering state while reconnecting
                    syncLegacyPlaybackState(isPlaying = true, isBuffering = true)
                    startRetryLoop()
                }
            }
        })

        // Validate stream reachability asynchronously and fall back if primary is non-functional
        checkAndSelectStreamUrl()

        // Start background metadata and artwork polling
        startMetadataPolling()

        // Perform immediate one-shot fetch for initial metadata & artwork on first open
        fetchInitialMetadataAsync()

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
            ACTION_STOP -> {
                killService()
                return START_NOT_STICKY
            }
            else -> {
                updateForegroundNotification(startInForegroundRequired = player.playWhenReady)
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        updateForegroundNotification(startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) {
            killService()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun killService() {
        stopRetryLoop()
        releaseLocks()
        try {
            val castContext = CastContext.getSharedInstance(applicationContext)
            castContext.sessionManager.endCurrentSession(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        player.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createLaunchPendingIntent(): PendingIntent {
        val pm = applicationContext.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: Intent(applicationContext, MainActivity::class.java)
        launchIntent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getSessionCompat(): MediaSessionCompat? {
        return try {
            val session = mediaSession ?: return null
            val implField = MediaSession::class.java.getDeclaredField("impl").apply { isAccessible = true }
            val impl = implField.get(session) ?: return null
            val getLegacyStubMethod = impl.javaClass.getDeclaredMethod("getMediaSessionLegacyStub").apply { isAccessible = true }
            val legacyStub = getLegacyStubMethod.invoke(impl) ?: return null
            val getSessionCompatMethod = legacyStub.javaClass.getMethod("getSessionCompat")
            getSessionCompatMethod.invoke(legacyStub) as? MediaSessionCompat
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun updateLegacySessionMetadata(
        metadata: MediaMetadata,
        bitmap: Bitmap? = null,
        isTrackChange: Boolean = false
    ) {
        try {
            val sessionCompat = getSessionCompat() ?: return
            val title = metadata.title?.toString()
                ?: metadata.displayTitle?.toString()
                ?: "Bootie Mashup Radio"
            val artist = metadata.artist?.toString() ?: "Live Stream"
            val album = metadata.albumTitle?.toString() ?: "Bootie Mashup Radio"
            val displayTitle = metadata.displayTitle?.toString() ?: title

            val trackId = "bootie_track_$trackSequenceNumber"

            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, trackId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, album)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, trackSequenceNumber)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 999L)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 180000L) // 3-minute spoof duration for car stereos expecting track duration

            metadata.artworkUri?.let { uri ->
                val uriStr = uri.toString()
                builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uriStr)
                builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uriStr)
                builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uriStr)
            }

            val artwork = bitmap ?: currentArtworkBitmap
            if (artwork != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
            }

            sessionCompat.setMetadata(builder.build())

            // Update queue with new track info so Android AVRCP target recognizes active track
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(trackId)
                .setTitle(displayTitle)
                .setSubtitle(artist)
                .setDescription(album)
                .apply {
                    if (artwork != null) setIconBitmap(artwork)
                    metadata.artworkUri?.let { setIconUri(it) }
                }
                .build()
            val queueItem = MediaSessionCompat.QueueItem(description, trackSequenceNumber)
            sessionCompat.setQueue(listOf(queueItem))
            sessionCompat.setQueueTitle("Bootie Mashup Radio")

            // Spoof track transition in PlaybackStateCompat without interrupting ExoPlayer audio playback
            if (player.playWhenReady) {
                if (isTrackChange) {
                    // Momentarily signal a track transition state (STATE_SKIPPING_TO_NEXT) to wake up
                    // car head units that only refresh upon seeing a playback state event
                    val transitionState = PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0L, 1.0f)
                        .setActiveQueueItemId(trackSequenceNumber)
                        .build()
                    sessionCompat.setPlaybackState(transitionState)

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val session = getSessionCompat() ?: return@postDelayed
                            val playingState = PlaybackStateCompat.Builder()
                                .setState(
                                    PlaybackStateCompat.STATE_PLAYING,
                                    0L,
                                    1.0f,
                                    SystemClock.elapsedRealtime()
                                )
                                .setActions(
                                    PlaybackStateCompat.ACTION_PLAY or
                                    PlaybackStateCompat.ACTION_PAUSE or
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                    PlaybackStateCompat.ACTION_STOP
                                )
                                .setActiveQueueItemId(trackSequenceNumber)
                                .build()
                            session.setPlaybackState(playingState)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, 100)
                } else {
                    val playingState = PlaybackStateCompat.Builder()
                        .setState(
                            PlaybackStateCompat.STATE_PLAYING,
                            0L,
                            1.0f,
                            SystemClock.elapsedRealtime()
                        )
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_STOP
                        )
                        .setActiveQueueItemId(trackSequenceNumber)
                        .build()
                    sessionCompat.setPlaybackState(playingState)
                }
            } else {
                val pausedState = PlaybackStateCompat.Builder()
                    .setState(
                        PlaybackStateCompat.STATE_PAUSED,
                        0L,
                        0.0f,
                        SystemClock.elapsedRealtime()
                    )
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .setActiveQueueItemId(trackSequenceNumber)
                    .build()
                sessionCompat.setPlaybackState(pausedState)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun syncLegacyPlaybackState(isPlaying: Boolean, isBuffering: Boolean = false) {
        try {
            val sessionCompat = getSessionCompat() ?: return
            // If the user intends to play (isPlaying == true), but we are buffering or retrying,
            // report STATE_BUFFERING so Bluetooth car systems do not think playback stopped or disconnect.
            val state = when {
                !isPlaying -> PlaybackStateCompat.STATE_PAUSED
                isBuffering || isRetrying -> PlaybackStateCompat.STATE_BUFFERING
                else -> PlaybackStateCompat.STATE_PLAYING
            }
            val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f
            val playbackState = PlaybackStateCompat.Builder()
                .setState(state, 0L, speed, SystemClock.elapsedRealtime())
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setActiveQueueItemId(trackSequenceNumber)
                .build()
            sessionCompat.setPlaybackState(playbackState)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
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

    private fun updateForegroundNotification(startInForegroundRequired: Boolean = false) {
        createNotificationChannel()

        val sessionActivityIntent = mediaSession?.sessionActivity ?: createLaunchPendingIntent()

        val metadata = currentPolledMetadata ?: player.playlistMetadata
        val title = metadata.title?.toString()
            ?: metadata.displayTitle?.toString()
            ?: "Bootie Mashup Radio"
        val artist = metadata.artist?.toString() ?: "Live Stream"

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

        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Bootie Mashup Radio")
            .setContentIntent(sessionActivityIntent)
            .setDeleteIntent(stopPendingIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(playPauseAction)
            .addAction(muteAction)

        currentArtworkBitmap?.let {
            notificationBuilder.setLargeIcon(it)
        }

        val session = mediaSession
        if (session != null) {
            notificationBuilder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        val notification = notificationBuilder.build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val isCastActive = isCastingActive || try {
            val castContext = CastContext.getSharedInstance(applicationContext)
            val session = castContext.sessionManager.currentCastSession
            session != null && session.isConnected
        } catch (_: Throwable) {
            false
        }

        if (isPlaying || isCastActive || startInForegroundRequired) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            // Paused: detach from foreground requirement so user can swipe away, but keep notification visible
            stopForeground(STOP_FOREGROUND_DETACH)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BootieMashupRadio::PlaybackWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hour safe max timeout
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wifiManager?.createWifiLock(wifiMode, "BootieMashupRadio::PlaybackWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        updateNotificationLayout()
    }

    fun toggleMute() {
        isMuted = !isMuted
        player.volume = if (isMuted) 0f else 1f
        showToast(if (isMuted) "Audio Muted" else "Audio Unmuted")
        updateNotificationLayout()
    }

    @Suppress("unused")
    fun isAudioMuted(): Boolean {
        return isMuted
    }

    @Suppress("unused")
    fun getSessionStreamUrl(): String {
        return currentStreamUrl
    }

    private fun checkAndSelectStreamUrl() {
        CoroutineScope(Dispatchers.IO).launch {
            val primaryValid = checkStreamUrlValid(PRIMARY_STREAM_URL)
            val fallbackValid = if (!primaryValid) checkStreamUrlValid(FALLBACK_STREAM_URL) else true
            val chosenUrl = determineSessionStream(primaryValid, fallbackValid)
            if (chosenUrl != currentStreamUrl) {
                withContext(Dispatchers.Main) {
                    currentStreamUrl = chosenUrl
                    val activeMetadata = currentPolledMetadata ?: player.playlistMetadata
                    val item = MediaItem.Builder()
                        .setUri(chosenUrl)
                        .setMediaId("bootie_mashup_stream")
                        .setMediaMetadata(activeMetadata)
                        .build()
                    player.setMediaItem(item)
                    player.prepare()
                    if (player.playWhenReady) {
                        player.play()
                    }
                }
            }
        }
    }

    private fun startRetryLoop() {
        if (isRetrying) return
        isRetrying = true
        // Keep Bluetooth audio connection alive by signaling STATE_BUFFERING
        syncLegacyPlaybackState(isPlaying = true, isBuffering = true)
        retryJob?.cancel()
        retryJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && isRetrying && player.playWhenReady) {
                delay(RETRY_INTERVAL_SECONDS.seconds)
                if (!isRetrying || !player.playWhenReady) break
                // Retry connecting to the session's stream from live
                try {
                    player.stop()
                    val activeMetadata = currentPolledMetadata ?: player.playlistMetadata
                    val item = MediaItem.Builder()
                        .setUri(currentStreamUrl)
                        .setMediaId("bootie_mashup_stream")
                        .setMediaMetadata(activeMetadata)
                        .build()
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun stopRetryLoop() {
        isRetrying = false
        retryJob?.cancel()
        retryJob = null
    }

    @Suppress("unused")
    fun isRetryingStream(): Boolean {
        return isRetrying
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
        updateForegroundNotification(startInForegroundRequired = isPlaying)
    }

    private fun fetchInitialMetadataAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fetchAndUpdateMetadata(isInitial = true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startMetadataPolling() {
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            delay(5.seconds) // Wait 5 seconds so initial fetch completes cleanly without competition
            while (isActive) {
                try {
                    fetchAndUpdateMetadata(isInitial = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(5.seconds) // Poll every 5 seconds
            }
        }
    }

    private suspend fun fetchAndUpdateMetadata(isInitial: Boolean = false) {
        val request = Request.Builder()
            .url(MetadataHelper.NOW_PLAYING_URL)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val jsonStr = response.body.string()
                val parsed = MetadataHelper.parseNowPlayingJson(jsonStr)

                val nowPlayingChanged = (parsed.displayTitle != lastNowPlaying) || (currentPolledMetadata == null)

                if (!nowPlayingChanged && parsed.nextTrack == lastNextTrack && !isInitial) {
                    return@use
                }

                if (nowPlayingChanged && lastNowPlaying.isNotEmpty() && !isInitial) {
                    // Wait 1 second after now playing details update before reloading artwork
                    delay(1.seconds)
                }

                val isRealTrackChange = nowPlayingChanged && (lastNowPlaying.isNotEmpty() || parsed.displayTitle != "Bootie Mashup Radio")
                if (isRealTrackChange) {
                    trackSequenceNumber++
                }

                lastNowPlaying = parsed.displayTitle
                lastNextTrack = parsed.nextTrack

                var scaledBitmap: Bitmap? = null
                var scaledJpegBytes: ByteArray? = null
                try {
                    val artworkRequest = Request.Builder()
                        .url(parsed.artworkUriString)
                        .build()
                    okHttpClient.newCall(artworkRequest).execute().use { artworkResponse ->
                        if (artworkResponse.isSuccessful) {
                            val rawBytes = artworkResponse.body.bytes()
                            val processed = MetadataHelper.processArtworkBytes(rawBytes, MetadataHelper.MAX_ARTWORK_DIMENSION)
                            if (processed != null) {
                                scaledBitmap = processed.first
                                scaledJpegBytes = processed.second
                            } else {
                                scaledJpegBytes = rawBytes
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val updatedMetadata = MetadataHelper.buildMediaMetadata(parsed, scaledJpegBytes)
                currentPolledMetadata = updatedMetadata
                if (scaledBitmap != null) {
                    currentArtworkBitmap = scaledBitmap
                }

                withContext(Dispatchers.Main) {
                    // Updating playlistMetadata and legacy sessionCompat ensures
                    // that Mobile Lock Screen, Android TV Leanback launcher card, and Bluetooth
                    // AVRCP (smartwatch/car) immediately show the correct track title, artist,
                    // album, and album artwork without interrupting live stream playback!
                    player.playlistMetadata = updatedMetadata
                    updateLegacySessionMetadata(updatedMetadata, scaledBitmap, isTrackChange = isRealTrackChange)
                    updateNotificationLayout()

                    // If casting is active, update Cast receiver display even if screen is off
                    if (isCastingActive) {
                        updateCastMetadata(updatedMetadata)
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
        stopRetryLoop()
        releaseLocks()
        castSessionListener?.let { listener ->
            try {
                val castContext = CastContext.getSharedInstance(applicationContext)
                castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        player.release()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        showToast("Audio Stopped")
        super.onDestroy()
    }

    private fun setupCastSessionTracking() {
        try {
            val castContext = CastContext.getSharedInstance(applicationContext)
            val currentSession = castContext.sessionManager.currentCastSession
            isCastingActive = (currentSession != null && currentSession.isConnected)

            castSessionListener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    isCastingActive = true
                    lastCastTrackTitle = ""
                    // Ensure local playback is paused when casting starts
                    if (player.playWhenReady) {
                        player.pause()
                    }
                    currentPolledMetadata?.let { updateCastMetadata(it) }
                    updateForegroundNotification(startInForegroundRequired = true)
                }

                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    isCastingActive = true
                    lastCastTrackTitle = ""
                    if (player.playWhenReady) {
                        player.pause()
                    }
                    currentPolledMetadata?.let { updateCastMetadata(it) }
                    updateForegroundNotification(startInForegroundRequired = true)
                }

                override fun onSessionEnded(session: CastSession, error: Int) {
                    isCastingActive = false
                    lastCastTrackTitle = ""
                    // Force ExoPlayer to reconnect directly to the live stream edge rather than playing stale buffered audio
                    resumeLocalPlaybackFromLive()
                    updateForegroundNotification(startInForegroundRequired = true)
                }

                override fun onSessionSuspended(session: CastSession, reason: Int) {
                    // Temporarily suspended: remain in casting mode
                }

                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    isCastingActive = false
                }
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            }

            castContext.sessionManager.addSessionManagerListener(
                castSessionListener as SessionManagerListener<CastSession>,
                CastSession::class.java
            )
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun resumeLocalPlaybackFromLive() {
        try {
            player.stop()
            val activeMetadata = currentPolledMetadata ?: player.playlistMetadata
            val item = MediaItem.Builder()
                .setUri(currentStreamUrl)
                .setMediaId("bootie_mashup_stream")
                .setMediaMetadata(activeMetadata)
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCastMetadata(metadata: MediaMetadata) {
        try {
            val castContext = CastContext.getSharedInstance(applicationContext)
            val session = castContext.sessionManager.currentCastSession ?: return
            if (!session.isConnected) return
            val remoteClient = session.remoteMediaClient ?: return

            val nowPlaying = metadata.displayTitle?.toString()
                ?: metadata.title?.toString()
                ?: "Bootie Mashup Radio"
            val artist = metadata.artist?.toString() ?: "Live Stream"
            val artworkUri = metadata.artworkUri ?: MetadataHelper.ARTWORK_BASE_URL.toUri()

            if (nowPlaying == lastCastTrackTitle) return
            lastCastTrackTitle = nowPlaying

            val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(CastMediaMetadata.KEY_TITLE, nowPlaying)
                putString(CastMediaMetadata.KEY_ARTIST, artist)
                putString(CastMediaMetadata.KEY_ALBUM_TITLE, "Bootie Mashup Radio")
                putString(CastMediaMetadata.KEY_ALBUM_ARTIST, "Bootie Mashup Radio")
                putInt(CastMediaMetadata.KEY_TRACK_NUMBER, trackSequenceNumber.toInt())
                addImage(WebImage(artworkUri))
            }

            val mediaInfo = MediaInfo.Builder(PRIMARY_STREAM_URL)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("audio/mpeg")
                .setMetadata(castMetadata)
                .build()

            val currentItemId = remoteClient.mediaStatus?.currentItemId ?: return
            val updatedItem = MediaQueueItem.Builder(mediaInfo)
                .setItemId(currentItemId)
                .build()
            remoteClient.queueUpdateItems(arrayOf(updatedItem), null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controllerInfo)
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
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .build()
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                availablePlayerCommands
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
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
            return super.onCustomCommand(session, controllerInfo, customCommand, args)
        }
    }
}
