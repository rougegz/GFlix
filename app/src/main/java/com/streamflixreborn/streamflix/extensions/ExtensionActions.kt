package com.streamflixreborn.streamflix.extensions

import android.content.Context
import android.util.Log
import com.streamflixreborn.extcore.CsExtensionMeta
import com.streamflixreborn.extcore.InstalledExtension
import com.streamflixreborn.extcore.extensionFileName
import com.streamflixreborn.extcore.repoFolderName
import com.streamflixreborn.extcore.verifySha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Extension lifecycle: install / update / enable / disable / delete.
 * Downloads the `.cs3` to a temp file, optionally verifies `sha256-<hex>`
 * (`SitePlugin.fileHash`), then atomically moves it into app-private
 * `files/Extensions/<repo>/<id>.cs3` (mirrors CloudStream `getPluginPath`).
 */
class ExtensionActions(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient()
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
            val dest = File(installed.filePath)
            downloadTo(meta, dest)
            installed.copy(meta = meta, installedVersion = meta.version)
        }

    fun setEnabled(installed: InstalledExtension, enabled: Boolean): InstalledExtension =
        installed.copy(enabled = enabled)

    fun delete(installed: InstalledExtension): Boolean {
        var ok = true
        for (path in com.streamflixreborn.extcore.ExtensionInstaller.filesToDelete(installed)) {
            runCatching { File(path).delete() }.onFailure { ok = false }
        }
        Log.d(TAG, "Deleted extension ${installed.meta.internalName}: $ok")
        return ok
    }

    private fun downloadTo(meta: CsExtensionMeta, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File.createTempFile(dest.nameWithoutExtension, ".tmp", context.cacheDir)
        try {
            val req = Request.Builder().url(meta.url)
                .header("User-Agent", "Streamflix-Extensions/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code} for ${meta.url}" }
                val body = resp.body ?: error("Empty body for ${meta.url}")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (meta.fileHash != null) {
                val actual = sha256Hex(tmp)
                check(verifySha256Hex(meta.fileHash, actual)) {
                    "Extension hash mismatch for ${meta.internalName}"
                }
            }
            check(tmp.renameTo(dest)) { "Atomic move failed: ${tmp.absolutePath} -> ${dest.absolutePath}" }
            dest.setReadOnly()
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

    companion object {
        private const val TAG = "ExtensionActions"
    }
}
