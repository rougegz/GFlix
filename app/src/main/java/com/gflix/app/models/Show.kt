package com.gflix.app.models

import com.gflix.app.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
