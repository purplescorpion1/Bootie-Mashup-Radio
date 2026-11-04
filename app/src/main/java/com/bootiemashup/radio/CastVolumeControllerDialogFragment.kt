package com.bootiemashup.radio

import android.content.Context
import android.os.Bundle
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment

/**
 * Public top-level DialogFragment so Android's FragmentManager can instantiate
 * and recreate it properly without throwing IllegalStateException.
 */
class CastVolumeControllerDialogFragment : MediaRouteControllerDialogFragment() {

    override fun onCreateControllerDialog(
        context: Context,
        savedInstanceState: Bundle?
    ): MediaRouteControllerDialog {
        return CastVolumeControllerDialog(context)
    }
}
