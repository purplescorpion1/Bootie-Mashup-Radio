package com.bootiemashup.radio

import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory

/**
 * Custom MediaRouteDialogFactory that hooks up:
 * 1. CastChooserDialogFragment (deduplicates device listings)
 * 2. CastVolumeControllerDialogFragment (logarithmic volume curve for realistic slider control)
 */
class CastVolumeDialogFactory : MediaRouteDialogFactory() {

    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment {
        return CastChooserDialogFragment()
    }

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment {
        return CastVolumeControllerDialogFragment()
    }
}
