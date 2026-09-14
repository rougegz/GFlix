package com.gflix.extcore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Minimal HTTP boundary so JVM tests run without network/OkHttp. */
interface HttpGet {
    suspend fun get(url: String): String
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val SHORTCUT_PART = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,38}[A-Za-z0-9])?$")

fun expandRepoShortcut(raw: String): String {
    val input = raw.trim()
    if (input.isEmpty() || input.contains("://") || input.contains(".") || input.contains("/")) {
        if (input.contains("/") && !input.contains(".") && !input.contains("://")) {
            val parts = input.split("/")
            if (parts.size == 2 && parts.all { SHORTCUT_PART.matches(it) }) {
                return "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/builds/repo.json"
            }
        }
        return input
    }
    require(SHORTCUT_PART.matches(input)) { "Invalid repository shortcut: $input" }
    return "https://raw.githubusercontent.com/$input/cs-repo/builds/repo.json"
}

/** Normalize a user-pasted repo URL. Throws IllegalArgumentException when unusable. */
fun normalizeRepoUrl(raw: String): String {
    val expanded = expandRepoShortcut(raw.trim())
    val trimmed = expanded.trim()
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
    require(!isBlockedHost(hostOf(withScheme))) {
        "Blocked host in repository URL: $withScheme"
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

    suspend fun addRepo(rawUrl: String): CsRepo {
        // Network I/O outside the lock: holding a mutex across http.get()
        // stalls every other repo op for seconds.
        val url = normalizeRepoUrl(rawUrl)
        val failures = mutableListOf<String>()
        var lastError: Exception? = null
        for (candidate in repoCandidates(url)) {
            try {
                return fetchRepo(candidate)
            } catch (e: Exception) {
                lastError = e
                failures += "$candidate: ${e.message?.take(160)}"
            }
        }
        throw IllegalStateException(
            buildString {
                append("Repository unreachable")
                lastError?.message?.let { append(" ($it)") }
                append(". Tried:\n")
                failures.distinct().forEach { append("- ").append(it).append('\n') }
            }
        )
    }

    private suspend fun fetchRepo(url: String): CsRepo {
        val repoJson = http.get(url)
        val trimmed = repoJson.trimStart()
        if (trimmed.startsWith("[")) {
            val plugins = parsePluginList(repoJson).map {
                it.copy(repositoryUrl = it.repositoryUrl ?: url)
            }
            val repo = CsRepo(
                name = (hostOf(url) ?: url).ifBlank { url },
                description = "Pasted plugin list",
                pluginLists = listOf(url),
                url = url
            )
            mutex.withLock {
                repos[url] = repo
                listings[url] = plugins.distinctBy { "${it.repositoryUrl}|${it.internalName}" }
            }
            return repo
        }
        val repo = parseRepository(repoJson, url)
        val plugins = mutableListOf<CsExtensionMeta>()
        for (rawListUrl in repo.pluginLists) {
            // Plugin-list URLs come from repo content: enforce https too so a
            // malicious repository.json cannot downgrade us to http (MITM).
            val listUrl = normalizeRepoUrl(rawListUrl)
            val listJson = http.get(listUrl)
            plugins += parsePluginList(listJson).map {
                it.copy(repositoryUrl = it.repositoryUrl ?: url)
            }
        }
        val deduped = plugins.distinctBy { "${it.repositoryUrl}|${it.internalName}" }
        mutex.withLock {
            repos[url] = repo
            listings[url] = deduped
        }
        return repo
    }

    companion object {
        fun repoCandidates(url: String): List<String> {
            val out = linkedSetOf<String>()
            out += url
            val lower = url.substringBefore("?").lowercase()
            if (lower.endsWith("repo.json")) {
                out += url.replaceRange(url.length - "repo.json".length, url.length, "repository.json")
            } else if (lower.endsWith("repository.json")) {
                out += url.replaceRange(url.length - "repository.json".length, url.length, "repo.json")
            } else if (!lower.endsWith(".json")) {
                val base = url.trimEnd('/')
                out += "$base/repository.json"
                out += "$base/repo.json"
                out += "$base/builds/repository.json"
                out += "$base/builds/repo.json"
            }
            return out.toList()
        }
    }

    suspend fun removeRepo(rawUrl: String): Boolean = mutex.withLock {
        val url = normalizeRepoUrl(rawUrl)
        val key = resolveKeyLocked(url) ?: url
        listings.remove(key)
        repos.remove(key) != null
    }

    suspend fun refreshRepo(rawUrl: String): CsRepo {
        // Re-fetch outside the add lock path to keep semantics simple.
        val url = normalizeRepoUrl(rawUrl)
        val known = mutex.withLock { resolveKeyLocked(url) != null }
        require(known) { "Unknown repository: $url" }
        // addRepo re-acquires the lock; do not hold it across the call.
        return addRepo(url)
    }

    private fun resolveKeyLocked(url: String): String? {
        if (repos.containsKey(url)) return url
        return repoCandidates(url).firstOrNull { repos.containsKey(it) }
    }

    suspend fun listRepos(): List<CsRepo> = mutex.withLock { repos.values.toList() }

    suspend fun listAvailable(repoUrl: String? = null): List<CsExtensionMeta> = mutex.withLock {
        if (repoUrl != null) {
            val url = normalizeRepoUrl(repoUrl)
            return listings[url] ?: emptyList()
        }
        // Namespace by repo so a rogue repo cannot shadow another repo's id.
        return listings.values.flatten().distinctBy { "${it.repositoryUrl}|${it.internalName}" }
    }
}
