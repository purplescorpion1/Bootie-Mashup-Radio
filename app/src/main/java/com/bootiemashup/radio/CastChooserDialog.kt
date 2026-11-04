package com.bootiemashup.radio

import android.content.Context
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouter

/**
 * Custom MediaRouteChooserDialog that filters out duplicate device listings.
 *
 * Devices like soundbars and smart speakers frequently broadcast over multiple network
 * protocols or dual IPv4/IPv6 interfaces, causing duplicate entries in the standard picker.
 *
 * This dialog deduplicates routes by normalized name and ID so each unique physical
 * device is displayed only once.
 */
class CastChooserDialog @JvmOverloads constructor(
    context: Context,
    theme: Int = 0
) : MediaRouteChooserDialog(context, theme) {

    override fun onFilterRoutes(routes: MutableList<MediaRouter.RouteInfo>) {
        super.onFilterRoutes(routes)

        // Prioritize REMOTE playback routes (e.g. Cast devices) over system/local ones
        routes.sortByDescending { it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE }

        val seenNames = HashSet<String>()
        val seenIds = HashSet<String>()
        val iterator = routes.iterator()

        while (iterator.hasNext()) {
            val route = iterator.next()
            val name = route.name.trim()
            val id = route.id.trim()

            if (name.isNullOrBlank()) {
                iterator.remove()
                continue
            }

            val nameKey = name.lowercase()
            // Deduplicate if we have already encountered a route with this name or ID
            if (seenNames.contains(nameKey) || (id.isNotEmpty() && seenIds.contains(id))) {
                iterator.remove()
            } else {
                seenNames.add(nameKey)
                if (id.isNotEmpty()) {
                    seenIds.add(id)
                }
            }
        }
    }
}
