package com.streamflixreborn.extcore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Minimal HTTP boundary so JVM tests run without network/OkHttp. */
interface HttpGet {
    suspend fun get(url: String): String
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Normalize a user-pasted repo URL. Throws IllegalArgumentException when unusable. */
fun normalizeRepoUrl(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "Repository URL must not be empty" }
    require(!trimmed.contains(" ")) { "Repository URL must not contain spaces" }
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    if (withScheme.startsWith("http://")) {
        val host = withScheme.removePrefix("http://").substringBefore("/").substringBefore(":")
        require(host == "localhost" || host == "127.0.0.1") {
            "Only https URLs allowed (http permitted for localhost tests only)"
        }
    }
    return withScheme.trimEnd('/')
}

fun parseRepository(json: String, sourceUrl: String = ""): CsRepo {
    val repo = lenientJson.decodeFromString(CsRepo.serializer(), json).copy(url = sourceUrl)
    repo.validate()
    return repo
}

fun parsePluginList(json: String): List<CsExtensionMeta> {
    val plugins = lenientJson.decodeFromString(ListSerializer(CsExtensionMeta.serializer()), json)
    plugins.forEach { it.requireValid() }
    return plugins
}

/** Pure in-memory repo registry (Android Room implementation lives in :app). */
class RepoManager(private val http: HttpGet) {
    private val mutex = Mutex()
    private val repos = linkedMapOf<String, CsRepo>()
    private val listings = linkedMapOf<String, List<CsExtensionMeta>>()

    suspend fun addRepo(rawUrl: String): CsRepo = mutex.withLock {
        val url = normalizeRepoUrl(rawUrl)
        val repoJson = http.get(url)
        val repo = parseRepository(repoJson, url)
        val plugins = mutableListOf<CsExtensionMeta>()
        for (listUrl in repo.pluginLists) {
            val listJson = http.get(listUrl)
            plugins += parsePluginList(listJson).map {
                it.copy(repositoryUrl = it.repositoryUrl ?: url)
            }
        }
        repos[url] = repo
        listings[url] = plugins.distinctBy { it.internalName }
        repo
    }

    suspend fun removeRepo(rawUrl: String): Boolean = mutex.withLock {
        val url = normalizeRepoUrl(rawUrl)
        listings.remove(url)
        repos.remove(url) != null
    }

    suspend fun refreshRepo(rawUrl: String): CsRepo {
        // Re-fetch outside the add lock path to keep semantics simple.
        val url = normalizeRepoUrl(rawUrl)
        val known = mutex.withLock { repos.containsKey(url) }
        require(known) { "Unknown repository: $url" }
        // addRepo re-acquires the lock; do not hold it across the call.
        return addRepo(url)
    }

    suspend fun listRepos(): List<CsRepo> = mutex.withLock { repos.values.toList() }

    suspend fun listAvailable(repoUrl: String? = null): List<CsExtensionMeta> = mutex.withLock {
        if (repoUrl != null) {
            val url = normalizeRepoUrl(repoUrl)
            return listings[url] ?: emptyList()
        }
        return listings.values.flatten().distinctBy { it.internalName }
    }
}
