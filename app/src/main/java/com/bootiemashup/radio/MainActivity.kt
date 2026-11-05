package com.bootiemashup.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.mediarouter.app.MediaRouteButton
import com.bumptech.glide.Glide
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private lateinit var ivArtwork: ImageView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvNextTrackLabel: TextView
    private lateinit var tvNextTrack: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var mediaRouteButton: MediaRouteButton

    private var doubleBackToExitPressedOnce = false

    // Cast SDK
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private val castSessionManagerListener = CastSessionManagerListener()

    // Track whether we're currently casting so we can manage local playback
    private var isCasting = false

    // Last track title pushed to the Cast device — used to avoid redundant metadata updates
    private var lastCastTrackTitle: String = ""

    // Volume step per phone button press when casting (1% of device range per click)
    private companion object {
        const val CAST_VOLUME_STEP = 0.01

        // "Safe" maximum to apply on first connect. If the device was blasting at
        // 100%, we cap the initial cast volume at this level to protect hearing.
        // The user can always raise it with the volume buttons afterwards.
        const val CAST_SAFE_START_VOLUME = 0.20 // 20% of device range
    }

    private var volumeToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        ivArtwork = findViewById(R.id.ivArtwork)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvNextTrackLabel = findViewById(R.id.tvNextTrackLabel)
        tvNextTrack = findViewById(R.id.tvNextTrack)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnMute = findViewById(R.id.btnMute)
        mediaRouteButton = findViewById(R.id.mediaRouteButton)

        // Initialize Cast SDK — CastButtonFactory wires the MediaRouteButton to the Cast SDK's
        // route selector.
        try {
            castContext = CastContext.getSharedInstance(this)
            CastButtonFactory.setUpMediaRouteButton(applicationContext, mediaRouteButton)
        } catch (e: Exception) {
            // Cast not available (e.g. no Google Play Services on TV) — fall back to basic audio routing
            e.printStackTrace()
            setupFallbackRouteSelector()
        }

        // Apply our custom CastVolumeDialogFactory which:
        // 1) Filters out duplicate device entries in the chooser dialog
        // 2) Provides a logarithmic volume slider curve in the controller dialog
        mediaRouteButton.setDialogFactory(CastVolumeDialogFactory())

        // Setup double back press to exit
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (doubleBackToExitPressedOnce) {
                    killAudioAndExit()
                } else {
                    doubleBackToExitPressedOnce = true
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        doubleBackToExitPressedOnce = false
                    }, 2000)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Support Android TV focus and D-pad navigation
        if (isAndroidTV()) {
            btnPlayPause.isFocusable = true
            btnMute.isFocusable = true
            mediaRouteButton.isFocusable = true

            val focusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
            btnPlayPause.onFocusChangeListener = focusChangeListener
            btnMute.onFocusChangeListener = focusChangeListener
            mediaRouteButton.onFocusChangeListener = focusChangeListener

            btnPlayPause.requestFocus()
        }

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Prompt user to disable battery optimizations if currently optimizing
        checkBatteryOptimization()

        // Setup button listeners
        btnPlayPause.setOnClickListener {
            togglePlayback()
        }

        btnMute.setOnClickListener {
            toggleMute()
        }

        // Load initial placeholder artwork via Glide
        Glide.with(this)
            .load(MetadataHelper.ARTWORK_BASE_URL)
            .placeholder(R.mipmap.ic_launcher_round)
            .error(R.mipmap.ic_launcher_round)
            .into(ivArtwork)
    }

    /**
     * Intercept hardware volume key events.
     *
     * When casting, redirect physical volume key presses to adjust the Cast device
     * volume in precise 1% increments per click.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isCasting && castSession?.isConnected == true) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                // Only act on the DOWN action (once per physical press) to avoid double-firing
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val session = castSession ?: return true
                    val selectedRoute = androidx.mediarouter.media.MediaRouter.getInstance(this).selectedRoute
                    val isFixedVolume = selectedRoute.volumeHandling == androidx.mediarouter.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED || selectedRoute.volumeMax <= 1

                    if (isFixedVolume) {
                        showCastPassthroughVolumeToast()
                    } else {
                        if (session.isMute) {
                            try {
                                session.setMute(false)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        val currentPercent = (session.volume * 100).roundToInt()
                        val newPercent = when (keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP   -> (currentPercent + 1).coerceAtMost(100)
                            KeyEvent.KEYCODE_VOLUME_DOWN -> (currentPercent - 1).coerceAtLeast(0)
                            else -> currentPercent
                        }
                        val newVol = newPercent / 100.0
                        try {
                            session.setVolume(newVol)
                            showCastVolumeToast(newPercent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                // Consume both UP and DOWN actions to suppress the system volume bar
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showCastPassthroughVolumeToast() {
        volumeToast?.cancel()
        val toast = Toast.makeText(this, "Audio Passthrough · Use TV / Soundbar remote", Toast.LENGTH_SHORT)
        volumeToast = toast
        toast.show()
    }

    /**
     * Show a brief toast with the current Cast volume expressed as a percentage.
     * Cancels any active toast so rapid button presses update immediately without lag.
     */
    private fun showCastVolumeToast(percent: Int) {
        volumeToast?.cancel()
        val toast = Toast.makeText(this, "Cast Volume: $percent%", Toast.LENGTH_SHORT)
        volumeToast = toast
        toast.show()
    }

    /**
     * Fallback route selector used only when Cast SDK is unavailable (e.g. no Google Play Services).
     * Uses only CATEGORY_LIVE_AUDIO (not REMOTE_PLAYBACK) to avoid showing duplicates.
     */
    private fun setupFallbackRouteSelector() {
        val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_AUDIO)
            .build()
        mediaRouteButton.routeSelector = selector
    }

    override fun onStart() {
        super.onStart()
        // Start foreground service and connect to Media3 Session
        try {
            val serviceIntent = Intent(this, PlaybackService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        connectToMediaSession()
    }

    override fun onResume() {
        super.onResume()
        // Register cast session listener so we respond to cast connect/disconnect
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionManagerListener,
            CastSession::class.java
        )
        // Sync state in case a cast session is already active (e.g. returning to app after screen-off)
        castSession = castContext?.sessionManager?.currentCastSession
        if (castSession?.isConnected == true) {
            isCasting = true
            mediaController?.pause()
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister while paused — the Cast SDK keeps casting on its own
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionManagerListener,
            CastSession::class.java
        )
    }

    override fun onStop() {
        super.onStop()
        releaseMediaController()
    }

    private fun connectToMediaSession() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            try {
                val controller = mediaControllerFuture?.get()
                if (controller != null) {
                    onMediaControllerConnected(controller)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onMediaControllerConnected(controller: MediaController) {
        mediaController = controller

        // If actively casting, ensure the local player remains paused
        if (isCasting || castContext?.sessionManager?.currentCastSession?.isConnected == true) {
            isCasting = true
            controller.pause()
        }

        // Listen for playback and metadata changes from session
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isCasting || castContext?.sessionManager?.currentCastSession?.isConnected == true) {
                    controller.pause()
                }
                updatePlayPauseUI(controller.playWhenReady)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && (isCasting || castContext?.sessionManager?.currentCastSession?.isConnected == true)) {
                    controller.pause()
                }
                updatePlayPauseUI(controller.playWhenReady)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseUI(controller.playWhenReady)
            }

            override fun onVolumeChanged(volume: Float) {
                updateMuteUI(volume == 0f)
            }

            override fun onPlaylistMetadataChanged(metadata: MediaMetadata) {
                updateTrackMetadataUI(metadata)
            }

            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                updateTrackMetadataUI(if (!metadata.title.isNullOrBlank()) metadata else controller.playlistMetadata)
            }
        })

        // Initial UI sync from the connected controller
        updatePlayPauseUI(controller.playWhenReady)
        updateMuteUI(controller.volume == 0f)
        val initialMetadata = controller.playlistMetadata
        val initialTitle = initialMetadata.displayTitle?.toString() ?: initialMetadata.title?.toString()
        if (!initialTitle.isNullOrBlank() && initialTitle != "Bootie Mashup Radio") {
            updateTrackMetadataUI(initialMetadata)
        }
    }

    private fun releaseMediaController() {
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
            mediaControllerFuture = null
        }
        mediaController = null
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        if (isCasting) {
            // While casting, play/pause controls the Cast receiver via RemoteMediaClient
            val remoteClient = castSession?.remoteMediaClient ?: return
            if (remoteClient.isPlaying) {
                remoteClient.pause()
            } else {
                remoteClient.play()
            }
            return
        }
        if (controller.playWhenReady) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                controller.prepare()
            }
            controller.play()
        }
    }

    private fun toggleMute() {
        val controller = mediaController ?: return
        val currentVolume = controller.volume
        if (currentVolume == 0f) {
            controller.volume = 1f
            updateMuteUI(false)
        } else {
            controller.volume = 0f
            updateMuteUI(true)
        }
    }

    private fun updatePlayPauseUI(isPlaying: Boolean) {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause_white_24dp)
            btnPlayPause.contentDescription = "Pause"
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play_arrow_white_24dp)
            btnPlayPause.contentDescription = "Play"
        }
    }

    private fun updateMuteUI(isMuted: Boolean) {
        if (isMuted) {
            btnMute.setImageResource(R.drawable.ic_volume_off_white_24dp)
            btnMute.contentDescription = "Unmute Audio"
        } else {
            btnMute.setImageResource(R.drawable.ic_volume_up_white_24dp)
            btnMute.contentDescription = "Mute Audio"
        }
    }

    private fun updateTrackMetadataUI(metadata: MediaMetadata?) {
        val activeMetadata = metadata ?: return

        val nowPlaying = activeMetadata.displayTitle?.toString() ?: ""
        val artist = activeMetadata.artist?.toString() ?: ""
        val title = activeMetadata.title?.toString() ?: ""

        val displayText = when {
            nowPlaying.isNotEmpty() -> nowPlaying
            artist.isNotEmpty() && title.isNotEmpty() -> "$artist - $title"
            title.isNotEmpty() -> title
            else -> ""
        }

        if (displayText.isNotEmpty()) {
            tvTrackTitle.text = displayText
        }

        // Update Coming Next Track Info
        val nextTrack = activeMetadata.extras?.getString("next_track") ?: ""
        tvNextTrackLabel.visibility = View.VISIBLE
        tvNextTrack.visibility = View.VISIBLE
        if (nextTrack.isNotEmpty()) {
            tvNextTrack.text = nextTrack
        } else {
            tvNextTrack.text = "Bootie Mashup Radio"
        }

        // Update Album Artwork safely: use artworkData if present, or load via Glide
        val artworkData = activeMetadata.artworkData
        if (artworkData != null && artworkData.isNotEmpty()) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                if (bitmap != null) {
                    ivArtwork.setImageBitmap(bitmap)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val artworkUri = activeMetadata.artworkUri ?: MetadataHelper.ARTWORK_BASE_URL.toUri()
        Glide.with(this)
            .load(artworkUri)
            .placeholder(ivArtwork.drawable ?: ContextCompat.getDrawable(this, R.mipmap.ic_launcher_round))
            .error(R.mipmap.ic_launcher_round)
            .into(ivArtwork)

        // Push updated track info to the Chromecast display (soft load of same URL)
        if (isCasting) {
            updateCastMetadata(activeMetadata)
        }
    }

    private fun isCastSessionActive(): Boolean {
        return isCasting || (castSession?.isConnected == true) || (castContext?.sessionManager?.currentCastSession?.isConnected == true)
    }

    // -------------------------------------------------------------------------
    // Cast session management
    // -------------------------------------------------------------------------

    /**
     * Update Chromecast Now Playing using the Default Media Receiver.
     *
     * Soft-reloads the same live stream URL with fresh metadata so the TV display
     * actually updates. May cause a brief rebuffer on some networks/devices.
     * PlaybackService also calls the same path when the screen is off.
     */
    private fun updateCastMetadata(metadata: MediaMetadata) {
        val session = castSession ?: return
        val remoteClient = session.remoteMediaClient ?: return

        val nowPlaying = metadata.displayTitle?.toString()
            ?: metadata.title?.toString()
            ?: "Bootie Mashup Radio"
        val artist = metadata.artist?.toString() ?: "Live Stream"
        val artworkUri = metadata.artworkUri ?: MetadataHelper.ARTWORK_BASE_URL.toUri()

        // Skip redundant updates — only push when the track title actually changed
        if (nowPlaying == lastCastTrackTitle) return
        lastCastTrackTitle = nowPlaying

        try {
            val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(CastMediaMetadata.KEY_TITLE, nowPlaying)
                putString(CastMediaMetadata.KEY_ARTIST, artist)
                putString(CastMediaMetadata.KEY_ALBUM_TITLE, "Bootie Mashup Radio")
                putString(CastMediaMetadata.KEY_ALBUM_ARTIST, "Bootie Mashup Radio")
                addImage(WebImage(artworkUri))
            }

            val mediaInfo = MediaInfo.Builder(PlaybackService.PRIMARY_STREAM_URL)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("audio/mpeg")
                .setMetadata(castMetadata)
                .build()

            val loadRequest = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()

            remoteClient.load(loadRequest)
        } catch (e: Exception) {
            // Non-fatal: metadata display on TV may not update, but we avoid crashing
            e.printStackTrace()
        }
    }

    /**
     * Called when a Cast session becomes connected (user tapped a device in the picker).
     *
     * Volume safety:
     *   - Read the current Cast device volume (0.0–1.0).
     *   - If it's above our safe-start threshold (20%), cap it immediately.
     *   - Toast tells the user what level it's been set to and that volume
     *     buttons will now adjust the Cast device.
     *
     * Then pause local ExoPlayer and load the radio stream on the receiver.
     */
    private fun onCastSessionConnected(session: CastSession) {
        castSession = session
        isCasting = true

        // Pause local playback — the Cast receiver takes over audio output
        mediaController?.pause()

        // --- Volume safety check ---
        val selectedRoute = androidx.mediarouter.media.MediaRouter.getInstance(this).selectedRoute
        val isFixedVolume = selectedRoute.volumeHandling == androidx.mediarouter.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED || selectedRoute.volumeMax <= 1

        if (isFixedVolume) {
            // Unattenuated passthrough: do not artificially cap volume on fixed HDMI/TV passthrough devices
            Toast.makeText(
                this,
                "Casting to ${session.castDevice?.friendlyName}\nAudio Passthrough (Use TV / Soundbar remote for volume)",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val deviceVol = session.volume         // current Cast device volume, 0.0–1.0
            val startVol: Double
            if (deviceVol > CAST_SAFE_START_VOLUME) {
                startVol = CAST_SAFE_START_VOLUME
                try {
                    session.setVolume(startVol)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val previousPct = (deviceVol * 100).roundToInt()
                val safePct     = (startVol  * 100).roundToInt()
                Toast.makeText(
                    this,
                    "🔊 Cast volume reduced to $safePct% (was $previousPct%).\nUse volume buttons to adjust.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                startVol = deviceVol
                val pct = (startVol * 100).roundToInt()
                Toast.makeText(
                    this,
                    "Casting to ${session.castDevice?.friendlyName} · Volume: $pct%\nUse volume buttons to adjust.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Retrieve the current now playing metadata so the TV displays real info from startup
        val activeMetadata = mediaController?.playlistMetadata
            ?: mediaController?.mediaMetadata

        val title = activeMetadata?.title?.toString()
            ?: activeMetadata?.displayTitle?.toString()
            ?: "Bootie Mashup Radio"
        val artist = activeMetadata?.artist?.toString() ?: "Live Stream"
        val artworkUri = activeMetadata?.artworkUri ?: MetadataHelper.ARTWORK_BASE_URL.toUri()

        // Build a Cast MediaInfo with the current track title, artist, and album artwork
        val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(CastMediaMetadata.KEY_TITLE, title)
            putString(CastMediaMetadata.KEY_ARTIST, artist)
            putString(CastMediaMetadata.KEY_ALBUM_TITLE, "Bootie Mashup Radio")
            putString(CastMediaMetadata.KEY_ALBUM_ARTIST, "Bootie Mashup Radio")
            addImage(WebImage(artworkUri))
        }

        val mediaInfo = MediaInfo.Builder(PlaybackService.PRIMARY_STREAM_URL)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/mpeg")
            .setMetadata(castMetadata)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        session.remoteMediaClient?.load(loadRequest)
    }

    /**
     * Called when a Cast session ends (user pressed "Stop casting" or connection dropped).
     * Resume local ExoPlayer playback.
     */
    private fun onCastSessionEnded() {
        castSession = null
        isCasting = false
        // Reset so the next Cast connection will immediately push fresh metadata
        lastCastTrackTitle = ""

        // Resume local playback from live edge
        val controller = mediaController
        if (controller != null) {
            // Seek to default live edge position and play
            controller.stop()
            controller.prepare()
            controller.play()

            // Ensure artwork and track metadata are immediately re-displayed on the phone UI
            val currentMetadata = controller.playlistMetadata
            val currentTitle = currentMetadata.title?.toString() ?: currentMetadata.displayTitle?.toString()
            if (!currentTitle.isNullOrBlank()) {
                updateTrackMetadataUI(currentMetadata)
            } else {
                updateTrackMetadataUI(controller.mediaMetadata)
            }
        }

        Toast.makeText(this, "Cast ended — resuming on phone", Toast.LENGTH_SHORT).show()
    }

    /**
     * SessionManagerListener for Cast session lifecycle events.
     */
    private inner class CastSessionManagerListener : SessionManagerListener<CastSession> {

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            onCastSessionConnected(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
            isCasting = true
            mediaController?.pause()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            onCastSessionEnded()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Temporarily suspended (e.g. network hiccup) — leave isCasting true,
            // the session will be resumed automatically by the Cast SDK.
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Toast.makeText(this@MainActivity, "Cast failed to start", Toast.LENGTH_SHORT).show()
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
    }

    // -------------------------------------------------------------------------

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isAndroidTV(): Boolean {
        val pm = packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun killAudioAndExit() {
        // End any active Chromecast session
        try {
            castContext?.sessionManager?.endCurrentSession(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Disconnect controller
        mediaController?.let {
            it.stop()
            it.release()
            mediaController = null
        }

        // Stop the background service
        val stopServiceIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_STOP
        }
        startService(stopServiceIntent)
        stopService(stopServiceIntent)

        // Close app and kill process
        finishAffinity()
        System.exit(0)
    }
}
