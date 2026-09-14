package com.gflix.app.backup

import android.content.Context
import android.util.Log
import com.gflix.app.database.AppDatabase
import com.gflix.app.models.Episode
import com.gflix.app.models.Movie
import com.gflix.app.models.Season
import com.gflix.app.models.TvShow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * User-data + database backup against the single extensions database.
 *
 * JSON backup carries full rows (Gson) per backup context so favorites,
 * resume positions and watch history survive reinstalls. Database-zip backup
 * copies the underlying SQLite files; restore validates entry names against
 * path traversal before replacing anything.
 */
class BackupRestoreManager(
    private val context: Context,
    private val contexts: List<ProviderBackupContext>
) {

    private val gson = Gson()

    private data class ContextBackup(
        val name: String,
        val movies: List<Movie> = emptyList(),
        val tvShows: List<TvShow> = emptyList(),
        val seasons: List<Season> = emptyList(),
        val episodes: List<Episode> = emptyList()
    )

    fun exportUserData(): String? = runCatching {
        val payload = contexts.map { ctx ->
            ContextBackup(
                name = ctx.name,
                movies = runCatching { ctx.movieDao.getAll() }.getOrDefault(emptyList()),
                tvShows = runCatching { ctx.tvShowDao.getAllForBackup() }.getOrDefault(emptyList()),
                seasons = runCatching { ctx.seasonDao.getAllForBackup() }.getOrDefault(emptyList()),
                episodes = runCatching { ctx.episodeDao.getAllForBackup() }.getOrDefault(emptyList())
            )
        }
        gson.toJson(payload)
    }.onFailure { Log.e(TAG, "exportUserData failed", it) }.getOrNull()

    fun importUserData(jsonData: String): Boolean = runCatching {
        val type = object : TypeToken<List<ContextBackup>>() {}.type
        val payload: List<ContextBackup> = gson.fromJson(jsonData, type) ?: return false
        if (payload.isEmpty()) return false
        val byName = contexts.associateBy { it.name }
        var restoredAny = false
        for (backup in payload) {
            val ctx = byName[backup.name] ?: contexts.firstOrNull() ?: continue
            if (backup.movies.isNotEmpty()) {
                runCatching { ctx.movieDao.insertAll(backup.movies) }.onSuccess { restoredAny = true }
            }
            if (backup.tvShows.isNotEmpty()) {
                runCatching { ctx.tvShowDao.insertAll(backup.tvShows) }.onSuccess { restoredAny = true }
            }
            if (backup.seasons.isNotEmpty()) {
                runCatching { ctx.seasonDao.insertAll(backup.seasons) }.onSuccess { restoredAny = true }
            }
            if (backup.episodes.isNotEmpty()) {
                runCatching { ctx.episodeDao.insertAll(backup.episodes) }.onSuccess { restoredAny = true }
            }
        }
        restoredAny
    }.onFailure { Log.e(TAG, "importUserData failed", it) }.getOrDefault(false)

    fun exportDatabaseZip(): ByteArray? = runCatching {
        val dbFile = databaseFile() ?: return null
        if (!dbFile.exists()) return null
        val files = listOf("", "-wal", "-shm")
            .map { suffix -> File(dbFile.path + suffix) }
            .filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) return null
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zip ->
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            bos.toByteArray()
        }
    }.onFailure { Log.e(TAG, "exportDatabaseZip failed", it) }.getOrNull()

    fun importDatabaseZip(zipBytes: ByteArray): Boolean = runCatching {
        val dbFile = databaseFile() ?: return false
        val dir = dbFile.parentFile ?: return false
        val tmp = File.createTempFile("gflix_restore", ".zip", context.cacheDir)
        try {
            tmp.writeBytes(zipBytes)
            val names = mutableListOf<String>()
            ZipFile(tmp).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.isEmpty()) return false
                for (entry in entries) {
                    val name = entry.name
                    require(name.isNotBlank() && !name.contains("..") &&
                        !name.startsWith("/") && !name.contains(File.separator)) {
                        "Rejected zip entry: $name"
                    }
                    names += name
                }
                require(names.any { it.endsWith(".db") }) { "No database file in backup" }
                AppDatabase.resetInstance()
                for (entry in entries) {
                    val dest = File(dir, File(entry.name).name)
                    require(dest.canonicalPath.startsWith(dir.canonicalPath)) {
                        "Path escape: ${entry.name}"
                    }
                    zip.getInputStream(entry).use { ins ->
                        dest.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
            }
            true
        } finally {
            runCatching { tmp.delete() }
        }
    }.onFailure { Log.e(TAG, "importDatabaseZip failed", it) }.getOrDefault(false)

    fun refreshCachesFromDatabase(): Boolean = runCatching {
        for (ctx in contexts) {
            runCatching {
                com.gflix.app.utils.HomeCacheStore.clear(context, ctx.provider)
            }
        }
        com.gflix.app.utils.CacheUtils.clearAppCache(context)
        true
    }.onFailure { Log.e(TAG, "refreshCachesFromDatabase failed", it) }.getOrDefault(false)

    private fun databaseFile(): File? = runCatching {
        val name = contexts.firstOrNull()?.let {
            AppDatabase.databaseNameFor(it.name)
        } ?: return null
        context.getDatabasePath(name)
    }.getOrNull()

    companion object {
        private const val TAG = "BackupRestore"
    }
}
