package com.gflix.extcore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors CloudStream `Repository` (RepositoryManager.kt:33-40). */
@Serializable
data class CsRepo(
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
    /** Original repo index URL (not part of upstream JSON; host bookkeeping). */
    val url: String = ""
) {
    fun validate() {
        require(name.isNotBlank()) { "Repository name must not be blank" }
        require(manifestVersion >= SUPPORTED_MANIFEST_MIN) {
            "Unsupported manifestVersion=$manifestVersion"
        }
        require(pluginLists.isNotEmpty()) { "Repository has no pluginLists" }
    }
}

/**
 * Mirrors CloudStream `SitePlugin` (RepositoryManager.kt:49-76).
 * `url` points at the `.cs3`/`.zip` payload.
 */
@Serializable
data class CsExtensionMeta(
    val url: String,
    val status: Int = PROVIDER_STATUS_OK,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val name: String,
    val internalName: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val repositoryUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    val iconUrl: String? = null,
    val fileSize: Long? = null,
    /** Gradle-generated form: "sha256-<hex>". Null = skip verification. */
    val fileHash: String? = null
) {
    /** Update trigger: remote version bump or always-update sentinel. */
    fun isOutdated(installedVersion: Int): Boolean =
        version > installedVersion || version == PLUGIN_VERSION_ALWAYS_UPDATE

    fun isDisabled(): Boolean = status == PROVIDER_STATUS_DOWN

    fun requireValid() {
        require(name.isNotBlank()) { "Extension name must not be blank" }
        require(internalName.isNotBlank()) { "internalName must not be blank" }
        require(url.isNotBlank()) { "Extension url must not be blank" }
        val clean = url.substringBefore("?").lowercase()
        require(clean.endsWith(".cs3") || clean.endsWith(".zip")) {
            "Extension url must end with .cs3 or .zip: $url"
        }
        require(isHttpsOrLocalhost(url)) {
            "Extension url must be https (localhost http allowed for tests): $url"
        }
        require(!isBlockedHost(hostOf(url))) {
            "Blocked host in extension url: $url"
        }
    }
}

/** `.cs3` zip manifest (`BasePlugin.Manifest`): manifest.json at zip root. */
@Serializable
data class CsManifest(
    val name: String? = null,
    val pluginClassName: String? = null,
    val requiresResources: Boolean = false,
    val version: Int? = null
) {
    fun requireValid() {
        require(!pluginClassName.isNullOrBlank()) { "manifest.json: pluginClassName missing" }
    }
}

enum class ExtensionStatus {
    INSTALLED,
    ENABLED,
    UPDATE_AVAILABLE,
    DISABLED_REMOTE
}

@Serializable
data class InstalledExtension(
    val meta: CsExtensionMeta,
    /** Absolute path of the stored `.cs3` (app-private files). */
    val filePath: String,
    val enabled: Boolean = true,
    val installedVersion: Int = meta.version
) {
    fun status(): ExtensionStatus = when {
        meta.isDisabled() -> ExtensionStatus.DISABLED_REMOTE
        meta.isOutdated(installedVersion) -> ExtensionStatus.UPDATE_AVAILABLE
        enabled -> ExtensionStatus.ENABLED
        else -> ExtensionStatus.INSTALLED
    }
}

/** Playback link: CloudStream `ExtractorLink` equivalent (host-side subset). */
@Serializable
data class ExtLink(
    val url: String,
    val referer: String = "",
    val quality: Int = 0,
    val qualityLabel: String = "",
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val extractorName: String = ""
) {
    fun requireValid() {
        require(url.isNotBlank()) { "ExtLink url must not be blank" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "ExtLink url must be http(s): $url"
        }
    }
}

@Serializable
data class ExtSubtitle(
    val label: String,
    val url: String
)

@Serializable
data class ExtSearchItem(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val tvType: String = "Movie",
    val extensionId: String = ""
)

@Serializable
data class ExtLoadData(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    /** Opaque episode/season payload; extension resolves it in loadLinks. */
    val dataUrl: String = ""
)

const val PROVIDER_STATUS_DOWN = 0
const val PROVIDER_STATUS_OK = 1
const val PROVIDER_STATUS_SLOW = 2
const val PROVIDER_STATUS_BETA_ONLY = 3
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1
const val SUPPORTED_MANIFEST_MIN = 1

/** Storage layout: files/Extensions/<repoHash>/<sanitized>.cs3 */
fun sanitizeExtensionFileName(name: String): String {
    val base = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "ext" }
    // toUInt hex avoids Int.MIN_VALUE abs() bug and keeps names filesystem-safe.
    val suffix = name.hashCode().toUInt().toString(16)
    return "$base.$suffix"
}


/** Hosts that must never be fetched (SSRF): loopback (except explicit localhost tests), RFC1918, link-local, etc. */
fun isBlockedHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return true
    val h = host.lowercase().substringBefore(":")
    if (h == "localhost") return false
    if (h == "127.0.0.1" || h == "0.0.0.0" || h == "::1" || h == "[::1]") return true
    if (h.contains("@") || h.contains(" ") || h.contains("\\")) return true
    fun ipv4(s: String): List<Int>? {
        val parts = s.split(".")
        if (parts.size != 4) return null
        return runCatching { parts.map { it.toInt() } }.getOrNull()
            ?.takeIf { it.all { n -> n in 0..255 } }
    }
    val ip = ipv4(h)
    if (ip != null) {
        if (ip[0] == 10) return true
        if (ip[0] == 172 && ip[1] in 16..31) return true
        if (ip[0] == 192 && ip[1] == 168) return true
        if (ip[0] == 169 && ip[1] == 254) return true
        if (ip[0] == 127) return true
        if (ip[0] == 0) return true
        return false
    }
    if (h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")) return true
    if (h == "metadata.google.internal") return true
    return false
}

fun hostOf(url: String): String? = runCatching {
    val afterScheme = url.substringAfter("://", "")
    afterScheme.substringBefore("/").substringBefore("?").substringBefore("#")
}.getOrNull()
/** True for https://… or http://localhost|127.0.0.1 (tests only). */
fun isHttpsOrLocalhost(url: String): Boolean {
    if (url.startsWith("https://")) return true
    if (!url.startsWith("http://")) return false
    val host = url.removePrefix("http://").substringBefore("/").substringBefore(":")
    return host == "localhost" || host == "127.0.0.1"
}

fun repoFolderName(repositoryUrl: String): String =
    sanitizeExtensionFileName(repositoryUrl.ifBlank { "default" })

fun extensionFileName(internalName: String): String =
    sanitizeExtensionFileName(internalName) + ".cs3"

/** "sha256-<hex>" check; null expected = skip (legacy unsigned builds). */
fun verifySha256Hex(expectedFileHash: String?, actualHex: String): Boolean {
    if (expectedFileHash == null) return true
    val prefix = "sha256-"
    require(expectedFileHash.startsWith(prefix)) {
        "Unsupported hash format (want 'sha256-<hex>'): $expectedFileHash"
    }
    return expectedFileHash.removePrefix(prefix).equals(actualHex, ignoreCase = true)
}
