package com.gflix.app.fragments.extensions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gflix.app.extensions.ExtDao
import com.gflix.app.extensions.ExtDatabase
import com.gflix.app.extensions.ExtEntity
import com.gflix.app.extensions.ExtensionEngine
import com.gflix.app.extensions.ExtensionActions
import com.gflix.app.extensions.RepoDao
import com.gflix.app.extensions.RepoEntity
import com.gflix.app.utils.UserPreferences
import com.gflix.extcore.CsExtensionMeta
import com.gflix.extcore.CsRepo
import com.gflix.extcore.HttpGet
import com.gflix.extcore.RepoManager
import com.gflix.extcore.normalizeRepoUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
            .header("User-Agent", "GFlix-Extensions/1.0")
            .build()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
                resp.body?.string() ?: error("Empty body for $url")
            }
        }
    }
}

class ExtensionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repoManager: RepoManager = RepoManager(OkHttpGet())
    private val repoDao: RepoDao = ExtDatabase.getInstance(application).repoDao()
    private val extDao: ExtDao = ExtDatabase.getInstance(application).extDao()
    private val actions: ExtensionActions = ExtensionActions(application)

    private val _repos = MutableStateFlow<List<CsRepo>>(emptyList())
    val repos: StateFlow<List<CsRepo>> = _repos

    private val _available = MutableStateFlow<List<CsExtensionMeta>>(emptyList())
    val available: StateFlow<List<CsExtensionMeta>> = _available

    private val _installed = MutableStateFlow<List<ExtEntity>>(emptyList())
    val installed: StateFlow<List<ExtEntity>> = _installed

    private val _currentId = MutableStateFlow(UserPreferences.currentExtensionId)
    val currentId: StateFlow<String> = _currentId

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _installed.value = extDao.listInstalled()
            for (stored in repoDao.listRepos()) {
                runCatching { repoManager.addRepo(stored.url) }
            }
            _repos.value = repoManager.listRepos()
            _available.value = repoManager.listAvailable()
            if (_currentId.value.isNotBlank() &&
                extDao.findById(_currentId.value) == null
            ) {
                _currentId.value = ""
                UserPreferences.currentExtensionId = ""
            }
        }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _loading.value = true
        try {
            for (repo in repoManager.listRepos()) {
                runCatching { repoManager.refreshRepo(repo.url) }
            }
            _repos.value = repoManager.listRepos()
            _available.value = repoManager.listAvailable()
            _installed.value = extDao.listInstalled()
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _loading.value = false
        }
    }

    fun addRepo(rawUrl: String, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            var ok = false
            try {
                val repo = repoManager.addRepo(rawUrl)
                repoDao.upsert(
                    RepoEntity(
                        url = repo.url,
                        name = repo.name,
                        description = repo.description,
                        iconUrl = repo.iconUrl,
                        manifestVersion = repo.manifestVersion,
                        pluginListUrls = repo.pluginLists.joinToString("\n"),
                        lastRefreshMillis = System.currentTimeMillis()
                    )
                )
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
            val normalized = normalizeRepoUrl(url)
            val keys = (com.gflix.extcore.RepoManager.repoCandidates(normalized) + normalized).toSet()
            for (ext in extDao.listInstalled().filter { it.repoUrl in keys }) {
                actions.delete(
                    com.gflix.extcore.InstalledExtension(
                        meta = availableMeta(ext) ?: continueMeta(ext),
                        filePath = ext.filePath,
                        enabled = ext.enabled,
                        installedVersion = ext.version
                    )
                )
                extDao.deleteById(ext.internalName)
            }
            for (key in keys) {
                extDao.deleteByRepo(key)
                repoDao.deleteByUrl(key)
                repoManager.removeRepo(key)
            }
            if (_currentId.value.isNotBlank() && extDao.findById(_currentId.value) == null) {
                selectExtension("")
            }
            _repos.value = repoManager.listRepos()
            _available.value = repoManager.listAvailable()
            _installed.value = extDao.listInstalled()
        }.onFailure { _error.value = it.message }
    }

    fun install(meta: CsExtensionMeta, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            var ok = false
            try {
                val installed = actions.install(meta)
                extDao.upsert(
                    ExtEntity(
                        internalName = meta.internalName,
                        name = meta.name,
                        version = installed.installedVersion,
                        repoUrl = meta.repositoryUrl ?: "",
                        downloadUrl = meta.url,
                        filePath = installed.filePath,
                        iconUrl = meta.iconUrl,
                        language = meta.language,
                        tvTypes = meta.tvTypes.joinToString(","),
                        enabled = true,
                        fileHash = meta.fileHash,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
                _installed.value = extDao.listInstalled()
                if (_currentId.value.isBlank()) selectExtension(meta.internalName)
                ok = true
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
            onDone(ok)
        }

    fun deleteExtension(id: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val ext = extDao.findById(id) ?: return@runCatching
            actions.delete(
                com.gflix.extcore.InstalledExtension(
                    meta = availableMeta(ext) ?: continueMeta(ext),
                    filePath = ext.filePath,
                    enabled = ext.enabled,
                    installedVersion = ext.version
                )
            )
            extDao.deleteById(id)
            if (_currentId.value == id) selectExtension("")
            _installed.value = extDao.listInstalled()
        }.onFailure { _error.value = it.message }
    }

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val ext = extDao.findById(id) ?: return@runCatching
            extDao.upsert(ext.copy(enabled = enabled))
            _installed.value = extDao.listInstalled()
        }.onFailure { _error.value = it.message }
    }

    fun selectExtension(id: String, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.IO) {
            var ok = false
            runCatching {
                if (id.isNotBlank()) {
                    val ext = extDao.findById(id)
                        ?: throw IllegalStateException("Extension not installed: $id")
                    if (!ext.enabled) {
                        extDao.upsert(ext.copy(enabled = true))
                        _installed.value = extDao.listInstalled()
                    }
                }
                _currentId.value = id
                UserPreferences.currentExtensionId = id
                ExtensionEngine.getInstance(getApplication()).invalidateAll()
                ok = true
            }.onFailure { _error.value = it.message }
            onDone(ok)
        }

    fun clearError() {
        _error.value = null
    }

    private fun availableMeta(ext: ExtEntity): CsExtensionMeta? =
        _available.value.find { it.internalName == ext.internalName }

    private fun continueMeta(ext: ExtEntity): CsExtensionMeta =
        CsExtensionMeta(
            url = ext.downloadUrl,
            version = ext.version,
            name = ext.name,
            internalName = ext.internalName,
            repositoryUrl = ext.repoUrl.ifBlank { null },
            language = ext.language,
            iconUrl = ext.iconUrl,
            fileHash = ext.fileHash
        )
}
