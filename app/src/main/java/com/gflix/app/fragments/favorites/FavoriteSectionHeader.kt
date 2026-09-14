package com.gflix.app.fragments.favorites

import com.gflix.app.adapters.AppAdapter

data class FavoriteSectionHeader(
    val title: String,
    val section: FavoritesViewModel.Section,
) : AppAdapter.Item {
    override var itemType: AppAdapter.Type = AppAdapter.Type.FAVORITE_SECTION_HEADER
}
