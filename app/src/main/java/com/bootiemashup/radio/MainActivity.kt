package com.bootiemashup.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import com.bumptech.glide.Glide
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

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
    private var uiPollingJob: kotlinx.coroutines.Job? = null
    private var lastUiNowPlaying: String = ""

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

        // Configure MediaRouteButton for standard Bluetooth / audio routing
        val mediaRouteSelector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
        mediaRouteButton.routeSelector = mediaRouteSelector

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

            // Set up clean background coloring or focus change listeners to indicate focus
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

        // Setup button listeners
        btnPlayPause.setOnClickListener {
            togglePlayback()
        }

        btnMute.setOnClickListener {
            toggleMute()
        }
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
        startUiPolling()
    }

    override fun onPause() {
        super.onPause()
        stopUiPolling()
    }

    override fun onStop() {
        super.onStop()
        stopUiPolling()
        // Release MediaController connection
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

        // Listen for playback and metadata changes from session
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseUI(controller.playWhenReady)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlayPauseUI(playWhenReady)
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
                updateTrackMetadataUI(controller.playlistMetadata)
            }
        })

        // Initial UI sync
        updatePlayPauseUI(controller.playWhenReady)
        updateMuteUI(controller.volume == 0f)
        updateTrackMetadataUI(controller.playlistMetadata)
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
        if (controller.playWhenReady) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                controller.prepare()
            }
            controller.play()
        }
        // Also send custom action to service for complete sync
        try {
            controller.sendCustomCommand(
                androidx.media3.session.SessionCommand("ACTION_TOGGLE_PLAY_PAUSE", Bundle.EMPTY),
                Bundle.EMPTY
            )
        } catch (e: Exception) {
            e.printStackTrace()
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
        // Also trigger service custom action for complete sync
        try {
            controller.sendCustomCommand(
                androidx.media3.session.SessionCommand("ACTION_TOGGLE_MUTE", Bundle.EMPTY),
                Bundle.EMPTY
            )
        } catch (e: Exception) {
            e.printStackTrace()
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
        val activeMetadata = if (metadata != null && (!metadata.displayTitle.isNullOrEmpty() || !metadata.title.isNullOrEmpty())) {
            metadata
        } else {
            mediaController?.playlistMetadata ?: metadata
        } ?: return

        // Update Track Title
        val nowPlaying = activeMetadata.displayTitle?.toString() ?: ""
        val artist = activeMetadata.artist?.toString() ?: ""
        val title = activeMetadata.title?.toString() ?: ""

        val displayText = when {
            nowPlaying.isNotEmpty() -> nowPlaying
            artist.isNotEmpty() && title.isNotEmpty() -> "$artist - $title"
            title.isNotEmpty() -> title
            else -> "Bootie Mashup Radio"
        }
        tvTrackTitle.text = displayText

        // Update Coming Next Track Info
        val nextTrack = activeMetadata.extras?.getString("next_track") ?: ""
        if (nextTrack.isNotEmpty()) {
            tvNextTrackLabel.visibility = View.VISIBLE
            tvNextTrack.visibility = View.VISIBLE
            tvNextTrack.text = nextTrack
        } else {
            tvNextTrackLabel.visibility = View.GONE
            tvNextTrack.visibility = View.GONE
        }

        // Update Album Artwork using Glide without placeholder flashing
        val artworkUri = activeMetadata.artworkUri

        val requestBuilder = Glide.with(this@MainActivity)
            .load(artworkUri ?: "https://c7.radioboss.fm/w/artwork/205.jpg")
            .error(R.mipmap.ic_launcher_round)

        if (ivArtwork.drawable != null) {
            requestBuilder.placeholder(ivArtwork.drawable)
        } else {
            requestBuilder.placeholder(R.mipmap.ic_launcher_round)
        }

        requestBuilder.into(ivArtwork)
    }

    private fun startUiPolling() {
        uiPollingJob?.cancel()
        uiPollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    fetchAndUpdateUiMetadata()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(5000)
            }
        }
    }

    private fun stopUiPolling() {
        uiPollingJob?.cancel()
        uiPollingJob = null
    }

    private suspend fun fetchAndUpdateUiMetadata() {
        val request = Request.Builder()
            .url("https://c7.radioboss.fm/w/nowplayinginfo?u=205")
            .build()

        PlaybackService.okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val nowPlaying = json.optString("nowplaying", "").trim()
                var artist = json.optString("currenttrack_artist", "").trim()
                var title = json.optString("currenttrack_title", "").trim()
                val nextTrack = json.optString("nexttrack", "").trim()

                if (nowPlaying.isNotBlank() && nowPlaying == lastUiNowPlaying) {
                    return@use
                }

                if (nowPlaying.isNotBlank()) {
                    lastUiNowPlaying = nowPlaying
                }

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

                val displayText = if (nowPlaying.isNotBlank()) nowPlaying else "$artist - $title"

                withContext(Dispatchers.Main) {
                    tvTrackTitle.text = displayText

                    if (nextTrack.isNotEmpty()) {
                        tvNextTrackLabel.visibility = View.VISIBLE
                        tvNextTrack.visibility = View.VISIBLE
                        tvNextTrack.text = nextTrack
                    } else {
                        tvNextTrackLabel.visibility = View.GONE
                        tvNextTrack.visibility = View.GONE
                    }

                    val artworkUrl = "https://c7.radioboss.fm/w/artwork/205.jpg?_=" + System.currentTimeMillis()
                    val requestBuilder = Glide.with(this@MainActivity)
                        .load(artworkUrl)
                        .error(R.mipmap.ic_launcher_round)

                    if (ivArtwork.drawable != null) {
                        requestBuilder.placeholder(ivArtwork.drawable)
                    } else {
                        requestBuilder.placeholder(R.mipmap.ic_launcher_round)
                    }

                    requestBuilder.into(ivArtwork)
                }
            }
        }
    }

    private fun isAndroidTV(): Boolean {
        val pm = packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
               pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }

    private fun killAudioAndExit() {
        // Disconnect controller
        mediaController?.let {
            it.stop()
            it.release()
            mediaController = null
        }

        // Stop the background service
        val stopServiceIntent = Intent(this, PlaybackService::class.java)
        stopService(stopServiceIntent)

        // Close app and kill process
        finishAffinity()
        System.exit(0)
    }
}
