package com.bootiemashup.radio

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Provides Cast SDK configuration.
 *
 * Uses the Default Media Receiver app ID so no custom Cast receiver app needs to be registered.
 * The Default Receiver supports loading a plain audio stream URL directly via MediaInfo.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        return CastOptions.Builder()
            // Default Media Receiver — works with any plain audio/video URL with no custom receiver
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_APP_ID)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        // Google's built-in Default Media Receiver app ID
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
    }
}
