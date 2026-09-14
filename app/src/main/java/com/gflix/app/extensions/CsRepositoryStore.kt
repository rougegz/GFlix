package com.gflix.app.extensions

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Persisted CloudStream repository (user-added URL + cached index). */
@Entity(tableName = "ext_repos")
data class RepoEntity(
    @PrimaryKey val url: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val manifestVersion: Int = 1,
    val pluginListUrls: String = "",
    val lastRefreshMillis: Long = 0L
)

/** Persisted extension install state. */
@Entity(tableName = "ext_installs")
data class ExtEntity(
    @PrimaryKey val internalName: String,
    val name: String,
    val version: Int = 1,
    val repoUrl: String = "",
    val downloadUrl: String = "",
    val filePath: String = "",
    val iconUrl: String? = null,
    val language: String? = null,
    val tvTypes: String = "",
    val enabled: Boolean = true,
    val fileHash: String? = null,
    val updatedAtMillis: Long = 0L
)

@Dao
interface RepoDao {
    @Query("SELECT * FROM ext_repos ORDER BY name ASC")
    fun observeRepos(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM ext_repos ORDER BY name ASC")
    suspend fun listRepos(): List<RepoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: RepoEntity)

    @Query("DELETE FROM ext_repos WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}

@Dao
interface ExtDao {
    @Query("SELECT * FROM ext_installs ORDER BY name ASC")
    fun observeInstalled(): Flow<List<ExtEntity>>

    @Query("SELECT * FROM ext_installs ORDER BY name ASC")
    suspend fun listInstalled(): List<ExtEntity>

    @Query("SELECT * FROM ext_installs WHERE internalName = :id LIMIT 1")
    suspend fun findById(id: String): ExtEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ext: ExtEntity)

    @Query("DELETE FROM ext_installs WHERE internalName = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM ext_installs WHERE repoUrl = :repoUrl")
    suspend fun deleteByRepo(repoUrl: String)
}

@Database(entities = [RepoEntity::class, ExtEntity::class], version = 1, exportSchema = false)
abstract class ExtDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun extDao(): ExtDao

    companion object {
        @Volatile
        private var INSTANCE: ExtDatabase? = null

        fun getInstance(context: Context): ExtDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ExtDatabase::class.java,
                    "ext.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
