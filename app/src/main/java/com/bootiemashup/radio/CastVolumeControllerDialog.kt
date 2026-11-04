package com.bootiemashup.radio

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouter
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A MediaRouteControllerDialog that applies a square-root/square volume curve to the slider,
 * giving much finer control at quiet levels on variable-volume devices (like Wi-Fi soundbars).
 *
 * For fixed-volume devices (e.g. Chromecast on a TV passing digital audio through HDMI to a soundbar),
 * the volume slider is safely disabled to prevent erratic mute/unmute behavior.
 */
class CastVolumeControllerDialog @JvmOverloads constructor(
    context: Context,
    theme: Int = 0
) : MediaRouteControllerDialog(context, theme) {

    override fun onStart() {
        super.onStart()
        setupLogarithmicVolumeControl()
    }

    private fun setupLogarithmicVolumeControl() {
        val seekBar = findVolumeSeekBar() ?: return
        val route   = MediaRouter.getInstance(context).selectedRoute
        val maxVol  = route.volumeMax

        // Fixed volume / audio passthrough check:
        // When Chromecast is plugged into a TV passing audio through to an external speaker/soundbar,
        // volumeHandling is PLAYBACK_VOLUME_FIXED (or maxVol <= 1).
        // In that mode, dragging the slider only sends 0 (Mute) or 1 (Unmute).
        // Disabling the slider allows the external TV / Soundbar remote to control master volume cleanly.
        if (route.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED || maxVol <= 1) {
            seekBar.isEnabled = false
            return
        }

        // Set initial slider DISPLAY position using the inverse curve (sqrt),
        // so if actual device volume is 25% the slider shows at 50%.
        val currentActual = route.volume
        val displayPos = (sqrt(currentActual.toFloat() / maxVol) * maxVol)
            .roundToInt()
            .coerceIn(0, maxVol)
        seekBar.progress = displayPos
        seekBar.isEnabled = true

        // Replace the dialog's default linear listener with our square-curve one.
        // fromUser guard ensures we don't react to programmatic progress changes.
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // square curve: (position / max)^2 * max
                val normalised  = progress.toFloat() / maxVol
                val curved      = normalised * normalised
                val remapped    = (curved * maxVol).roundToInt().coerceIn(0, maxVol)
                MediaRouter.getInstance(context).selectedRoute.requestSetVolume(remapped)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    /**
     * Walk the dialog's view hierarchy to find the volume SeekBar.
     */
    private fun findVolumeSeekBar(): SeekBar? {
        return try {
            findViewById(androidx.mediarouter.R.id.mr_volume_slider)
                ?: window?.decorView?.let { findSeekBar(it) }
        } catch (e: Exception) {
            window?.decorView?.let { findSeekBar(it) }
        }
    }

    private fun findSeekBar(view: View): SeekBar? {
        if (view is SeekBar) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSeekBar(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
