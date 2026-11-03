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
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import com.bumptech.glide.Glide
import com.google.common.util.concurrent.ListenableFuture

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

    override fun onStop() {
        super.onStop()
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

        val artworkUri = activeMetadata.artworkUri
        if (artworkUri != null) {
            Glide.with(this)
                .load(artworkUri)
                .placeholder(ivArtwork.drawable)
                .error(R.mipmap.ic_launcher_round)
                .into(ivArtwork)
        }
    }

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
