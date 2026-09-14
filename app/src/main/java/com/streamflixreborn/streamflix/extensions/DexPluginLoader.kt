package com.streamflixreborn.streamflix.extensions

import android.content.Context
import android.util.Log
import com.streamflixreborn.extcore.CsManifest
import dalvik.system.PathClassLoader
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * Loads CloudStream `.cs3` (zip with `classes.dex` + `manifest.json` at root)
 * via an isolated [PathClassLoader]. Mirrors upstream
 * `PluginManager.loadPlugin` (PathClassLoader + manifest.json reflection).
 *
 * Security note: [PathClassLoader] does NOT sandbox the plugin — Dex code runs
 * in-process with full reflection access to app classes. Mitigations applied
 * here: app-private read-only storage, `sha256-<hex>` verification before
 * first load (see [ExtensionActions]), manifest allow-listing, and no secrets
 * passed through the plugin classloader. A remote-process host is future work.
 */
class DexPluginLoader(private val context: Context) {

    data class LoadedPlugin(
        val internalName: String,
        val manifest: CsManifest,
        val classLoader: PathClassLoader,
        val pluginInstance: Any,
        val file: File
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(cs3File: File, internalName: String): LoadedPlugin {
        require(cs3File.exists()) { "Extension file missing: ${cs3File.absolutePath}" }
        require(cs3File.extension == "cs3" || cs3File.extension == "zip") {
            "Extension must be .cs3 or .zip: ${cs3File.name}"
        }
        val manifest = readManifest(cs3File).also { it.requireValid() }
        cleanupOatLeftovers(cs3File)
        // Parent is the app classloader so the plugin can call the extension
        // API surface; this is NOT a sandbox (see class KDoc).
        val loader = PathClassLoader(cs3File.absolutePath, context.classLoader)
        val className = manifest.pluginClassName!!
        // Allow-list class names: FQCN with 1+ dots, no spaces/separators (defense-in-depth;
        // Dex still runs in-process by design, so hash verification stays the real gate).
        require(className.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$"))) {
            "Refusing suspicious plugin class: $className"
        }
        Log.d(TAG, "Loading extension $internalName -> $className")
        val clazz = runCatching { loader.loadClass(className) }
            .getOrElse { throw IllegalStateException("Plugin class not found: $className", it) }
        val instance = runCatching { clazz.getDeclaredConstructor().newInstance() }
            .getOrElse { throw IllegalStateException("Plugin has no public no-arg constructor: $className", it) }
            ?: throw IllegalStateException("Plugin instantiation returned null: $className")
        check(cs3File.setReadOnly()) { "Failed to set extension read-only: ${cs3File.absolutePath}" }
        return LoadedPlugin(internalName, manifest, loader, instance, cs3File)
    }

    fun readManifest(cs3File: File): CsManifest {
        ZipFile(cs3File).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: throw IllegalStateException("No manifest.json in ${cs3File.name}")
            // Zip-bomb guard: reject absurd declared sizes before reading.
            val declared = entry.size
            require(declared in 1..MAX_MANIFEST_CHARS) { "Bad manifest size: $declared" }
            val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_CHARS + 1) }
            require(bytes.size <= MAX_MANIFEST_CHARS) { "manifest.json too large" }
            val text = bytes.toString(Charsets.UTF_8)
            return json.decodeFromString(CsManifest.serializer(), text)
        }
    }

    /** Best-effort removal of stale `oat/` compilation leftovers. */
    fun cleanupOatLeftovers(cs3File: File) {
        runCatching {
            val oatDir = File(cs3File.parent, "oat")
            if (oatDir.isDirectory) {
                val prefix = cs3File.nameWithoutExtension + "."
                oatDir.listFiles()?.forEach { file ->
                    if (file.name == cs3File.nameWithoutExtension || file.name.startsWith(prefix)) {
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DexPluginLoader"
        private const val MAX_MANIFEST_CHARS = 1_048_576

        /** App-private storage: files/Extensions/<repoFolder>/<file>.cs3 */
        fun pluginFile(context: Context, repoFolder: String, fileName: String): File =
            File(File(context.filesDir, "Extensions/$repoFolder").apply { mkdirs() }, fileName)
    }
}
