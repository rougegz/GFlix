package com.gflix.app.backup

import com.gflix.app.database.dao.EpisodeDao
import com.gflix.app.database.dao.MovieDao
import com.gflix.app.database.dao.SeasonDao
import com.gflix.app.database.dao.TvShowDao
import com.gflix.app.providers.Provider

/**
 * Single-database backup unit. Hardcoded per-provider databases are gone:
 * there is exactly one [AppDatabase] ("extensions.db") behind
 * [ExtensionContentProvider], so callers pass a single context.
 */
data class ProviderBackupContext(
    val name: String,
    val movieDao: MovieDao,
    val tvShowDao: TvShowDao,
    val episodeDao: EpisodeDao,
    val seasonDao: SeasonDao,
    val provider: Provider
)
