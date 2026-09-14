package com.streamflixreborn.streamflix.fragments.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.extcore.ExtContentApi
import com.streamflixreborn.extcore.ExtLink
import com.streamflixreborn.streamflix.extensions.CloudStreamAdapter
import com.streamflixreborn.streamflix.extensions.ExtLinkResolver
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CloudStream-style player VM (V2): no server grid.
 * Flow: `extension → loadLinks(dataUrl) → ExtLink[]` (best-first) → [Video]s
 * with headers + subtitles; player auto-falls back to the next link.
 * Runs side-by-side with [PlayerViewModel] until Milestone 3 cutover.
 */
class PlayerViewModelV2(
    private val apis: () -> List<ExtContentApi> = { emptyList() },
    private val dataUrl: String = ""
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingLinks)
    val state: Flow<State> = _state

    init {
        resolve()
    }

    fun resolve() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.LoadingLinks)
        try {
            val all = supervisorScope {
                apis().map { api ->
                    async {
                        withTimeoutOrNull(PER_API_TIMEOUT_MS) {
                            runCatching { api.loadLinks(dataUrl) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                }.flatMap { it.await() }
            }
            val videos = ExtLinkResolver.resolveVideos(all)
            if (videos.isEmpty()) throw IllegalStateException("No links from extensions")
            _state.emit(State.Ready(videos))
        } catch (e: Exception) {
            _state.emit(State.Failed(e))
        }
    }

    sealed class State {
        data object LoadingLinks : State()
        data class Ready(val videos: List<Video>) : State()
        data class Failed(val error: Exception) : State()
    }

    companion object {
        private const val PER_API_TIMEOUT_MS = 8_000L
    }
}
