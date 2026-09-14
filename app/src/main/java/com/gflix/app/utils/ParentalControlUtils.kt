package com.gflix.app.utils

import com.gflix.app.adapters.AppAdapter
import com.gflix.app.models.Category
import com.gflix.app.models.Show

/**
 * Parental-control list filtering.
 *
 * Age-gated filtering used to rely on TMDB certifications; TMDB was removed
 * from the app (extension-only catalogs carry no age metadata), so list
 * filtering is a pass-through. The PIN itself still guards settings changes
 * and playback actions elsewhere. Kept as a named choke point so a future
 * extension-provided age signal can plug back in here.
 */
object ParentalControlUtils {

    fun filterItems(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        if (!isFilteringActive()) return items
        return items.filter { isAllowed(it) }
    }

    fun filterShows(shows: List<Show>): List<Show> {
        if (!isFilteringActive()) return shows
        return shows.filter { isAllowed(it) }
    }

    fun filterCategories(categories: List<Category>): List<Category> {
        if (!isFilteringActive()) return categories
        return categories.mapNotNull { category ->
            val visible = filterItems(category.list)
            if (visible.isEmpty()) null else category.copy(list = visible)
        }
    }

    private fun isFilteringActive(): Boolean {
        if (!UserPreferences.isParentalControlActive) return false
        if (UserPreferences.isParentalControlTemporarilyLocked) return true
        if (UserPreferences.parentalControlHardLocked) return true
        return false
    }

    private fun isAllowed(item: AppAdapter.Item): Boolean {
        // No age signal on extension items: locked mode hides nothing at the
        // list level (playback-time PIN checks, if any, still apply).
        return true
    }
}
