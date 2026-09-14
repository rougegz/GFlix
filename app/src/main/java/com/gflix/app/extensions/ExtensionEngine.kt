package com.gflix.app.extensions

import android.content.Context
import android.util.Log
import com.gflix.extcore.CsPluginApi
import com.gflix.extcore.ExtContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ExtensionEngine(private val context: Context) {

    private val loader = DexPluginLoader(context)
    private val cache = LinkedHashMap<String, ExtContentApi>()

    @Synchronized
    fun invalidate(id: String) {
        cache.remove(id)
    }

    @Synchronized
    fun invalidateAll() {
        cache.clear()
    }

    suspend fun apis(): List<ExtContentApi> = withContext(Dispatchers.IO) {
        val db = ExtDatabase.getInstance(context)
        val installed = runCatching { db.extDao().listInstalled() }.getOrDefault(emptyList())
        val result = mutableListOf<ExtContentApi>()
        for (ext in installed) {
            if (!ext.enabled) continue
            val cached = synchronized(this@ExtensionEngine) { cache[ext.internalName] }
            if (cached != null) {
                result += cached
                continue
            }
            val api = runCatching {
                val file = File(ext.filePath)
                if (!file.exists()) {
                    Log.w(TAG, "Missing file for ${ext.internalName}, pruning row")
                    db.extDao().deleteById(ext.internalName)
                    return@runCatching null
                }
                val loaded = loader.load(file, ext.internalName)
                CsPluginApi(loaded.pluginInstance, ext.internalName)
            }.getOrElse {
                Log.w(TAG, "Load failed for ${ext.internalName}: ${it.message}")
                null
            }
            if (api != null) {
                synchronized(this@ExtensionEngine) { cache[ext.internalName] = api }
                result += api
            }
        }
        result
    }

    companion object {
        private const val TAG = "ExtensionEngine"

        @Volatile
        private var INSTANCE: ExtensionEngine? = null

        fun getInstance(context: Context): ExtensionEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ExtensionEngine(context.applicationContext).also { INSTANCE = it }
            }
    }
}
