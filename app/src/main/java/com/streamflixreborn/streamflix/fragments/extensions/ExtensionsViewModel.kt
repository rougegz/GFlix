package com.streamflixreborn.streamflix.fragments.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.extcore.CsExtensionMeta
import com.streamflixreborn.extcore.CsRepo
import com.streamflixreborn.extcore.HttpGet
import com.streamflixreborn.extcore.RepoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** OkHttp-backed [HttpGet] for repo JSON (5-min cache lives in Room later). */
class OkHttpGet(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
) : HttpGet {
    override suspend fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Streamflix-Extensions/1.0")
            .build()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
                resp.body?.string() ?: error("Empty body for $url")
            }
        }
    }
}

class ExtensionsViewModel(
    private val repoManager: RepoManager = RepoManager(OkHttpGet())
) : ViewModel() {

    private val _repos = MutableStateFlow<List<CsRepo>>(emptyList())
    val repos: StateFlow<List<CsRepo>> = _repos

    private val _available = MutableStateFlow<List<CsExtensionMeta>>(emptyList())
    val available: StateFlow<List<CsExtensionMeta>> = _available

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _loading.value = true
        try {
            _repos.value = repoManager.listRepos()
            _available.value = repoManager.listAvailable()
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _loading.value = false
        }
    }

    fun addRepo(url: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        _loading.value = true
        var ok = false
        try {
            repoManager.addRepo(url)
            _repos.value = repoManager.listRepos()
            _available.value = repoManager.listAvailable()
            ok = true
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _loading.value = false
        }
        onDone(ok)
    }

    fun deleteRepo(url: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            repoManager.removeRepo(url)
            _repos.value = repoManager.listRepos()
            _available.value = repoManager.listAvailable()
        }.onFailure { _error.value = it.message }
    }

    fun clearError() {
        _error.value = null
    }
}
