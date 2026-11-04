package com.bootiemashup.radio

import android.content.Context
import android.os.Bundle
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment

/**
 * Public top-level DialogFragment so Android's FragmentManager can instantiate
 * and recreate it properly without throwing IllegalStateException.
 */
class CastChooserDialogFragment : MediaRouteChooserDialogFragment() {

    override fun onCreateChooserDialog(
        context: Context,
        savedInstanceState: Bundle?
    ): MediaRouteChooserDialog {
        return CastChooserDialog(context)
    }
}
