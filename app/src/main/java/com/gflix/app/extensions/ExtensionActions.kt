package com.gflix.app.extensions

import android.content.Context
import android.util.Log
import com.gflix.extcore.CsExtensionMeta
import com.gflix.extcore.InstalledExtension
import com.gflix.extcore.extensionFileName
import com.gflix.extcore.hostOf
import com.gflix.extcore.isBlockedHost
import com.gflix.extcore.isHttpsOrLocalhost
import com.gflix.extcore.repoFolderName
import com.gflix.extcore.verifySha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Extension lifecycle: install / update / enable / disable / delete.
 * Downloads the `.cs3` to a temp file, verifies `sha256-<hex>`
 * (`SitePlugin.fileHash`; required except `localhost` test URLs), then moves
 * it into app-private `files/Extensions/<repo>/<id>.cs3` (mirrors CloudStream
 * `getPluginPath`). Dex executes in-process (see [DexPluginLoader]), so
 * unsigned remote code is never loaded silently.
 */
class ExtensionActions(
    private val context: Context,
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun install(meta: CsExtensionMeta): InstalledExtension = withContext(Dispatchers.IO) {
        meta.requireValid()
        val repoFolder = repoFolderName(meta.repositoryUrl ?: "default")
        val dest = DexPluginLoader.pluginFile(context, repoFolder, extensionFileName(meta.internalName))
        downloadTo(meta, dest)
        InstalledExtension(meta, dest.absolutePath, enabled = true, installedVersion = meta.version)
    }

    suspend fun update(installed: InstalledExtension, meta: CsExtensionMeta): InstalledExtension =
        withContext(Dispatchers.IO) {
            meta.requireValid()
            val dest = safeDest(installed.filePath)
            downloadTo(meta, dest)
            installed.copy(meta = meta, installedVersion = meta.version)
        }

    fun setEnabled(installed: InstalledExtension, enabled: Boolean): InstalledExtension =
        installed.copy(enabled = enabled)

    fun delete(installed: InstalledExtension): Boolean {
        var ok = true
        for (path in com.gflix.extcore.ExtensionInstaller.filesToDelete(installed)) {
            runCatching { safeDest(path).delete() }.onFailure { ok = false }
        }
        Log.d(TAG, "Deleted extension ${installed.meta.internalName}: $ok")
        return ok
    }

    private fun downloadTo(meta: CsExtensionMeta, dest: File) {
        require(isHttpsOrLocalhost(meta.url)) {
            "Refusing non-https extension url: ${meta.url}"
        }
        require(!isBlockedHost(hostOf(meta.url))) {
            "Refusing blocked host: ${meta.url}"
        }
        // Unsigned Dex = silent RCE: require a hash for remote files.
        // Localhost stays unsigned so JVM/fake tests work without signing.
        val allowUnsigned = meta.url.startsWith("http://localhost") ||
            meta.url.startsWith("http://127.0.0.1")
        require(allowUnsigned || !meta.fileHash.isNullOrBlank()) {
            "Refusing unsigned extension ${meta.internalName}: missing sha256 fileHash"
        }
        dest.parentFile?.mkdirs()
        val prefix = (dest.nameWithoutExtension + "___").take(16)
        val tmp = File.createTempFile(prefix, ".tmp", context.cacheDir)
        try {
            val req = Request.Builder().url(meta.url)
                .header("User-Agent", "GFlix-Extensions/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} for ${meta.url}" }
                val body = resp.body ?: error("Empty body for ${meta.url}")
                val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                check(contentLength <= MAX_CS3_BYTES) {
                    "Extension too large ($contentLength bytes, max $MAX_CS3_BYTES)"
                }
                var written = 0L
                body.byteStream().use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read: Int
                        while (ins.read(buf).also { read = it } > 0) {
                            written += read
                            check(written <= MAX_CS3_BYTES) {
                                "Extension too large (>${MAX_CS3_BYTES} bytes)"
                            }
                            out.write(buf, 0, read)
                        }
                    }
                }
            }
            if (!meta.fileHash.isNullOrBlank()) {
                val actual = sha256Hex(tmp)
                check(verifySha256Hex(meta.fileHash, actual)) {
                    "Extension hash mismatch for ${meta.internalName}"
                }
            }
            runCatching {
                Files.move(
                    tmp.toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.recoverCatching {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                throw IllegalStateException("Move failed: ${tmp.absolutePath} -> ${dest.absolutePath}", it)
            }
            check(dest.setReadOnly()) { "Failed to set extension read-only" }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (ins.read(buf).also { read = it } > 0) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Canonical-path guard: DB/backup rows must never escape files/Extensions. */
    fun safeDest(path: String): File {
        val base = File(context.filesDir, "Extensions").canonicalFile
        val f = File(path).canonicalFile
        require((f.path + File.separator).startsWith(base.path + File.separator) || f.path == base.path) {
            "path escape: $path"
        }
        return f
    }

    companion object {
        private const val TAG = "ExtensionActions"
        const val MAX_CS3_BYTES = 50L * 1024L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
