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
 * Only this class touches Dex APIs; everything else depends on [LoadedPlugin].
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
        // Isolated loader: parent is the app classloader, no app classes exposed for write.
        val loader = PathClassLoader(cs3File.absolutePath, context.classLoader)
        val className = manifest.pluginClassName!!
        Log.d(TAG, "Loading extension $internalName -> $className")
        val clazz = loader.loadClass(className)
        val instance = clazz.getDeclaredConstructor().newInstance()
        cs3File.setReadOnly()
        return LoadedPlugin(internalName, manifest, loader, instance as Any, cs3File)
    }

    fun readManifest(cs3File: File): CsManifest {
        ZipFile(cs3File).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: throw IllegalStateException("No manifest.json in ${cs3File.name}")
            val text = zip.getInputStream(entry).bufferedReader().readText()
            return json.decodeFromString(CsManifest.serializer(), text)
        }
    }

    /** Best-effort removal of stale `oat/` compilation leftovers. */
    fun cleanupOatLeftovers(cs3File: File) {
        runCatching {
            val oatDir = File(cs3File.parent, "oat")
            if (oatDir.isDirectory) {
                oatDir.listFiles()?.forEach { file ->
                    if (file.name.contains(cs3File.nameWithoutExtension)) runCatching { file.delete() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DexPluginLoader"

        /** App-private storage: files/Extensions/<repoFolder>/<file>.cs3 */
        fun pluginFile(context: Context, repoFolder: String, fileName: String): File =
            File(File(context.filesDir, "Extensions/$repoFolder").apply { mkdirs() }, fileName)
    }
}
