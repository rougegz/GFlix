package com.gflix.app.extensions

import com.gflix.app.adapters.AppAdapter
import com.gflix.app.models.Category
import com.gflix.app.models.Episode
import com.gflix.app.models.Genre
import com.gflix.app.models.Movie
import com.gflix.app.models.People
import com.gflix.app.models.Season
import com.gflix.app.models.TvShow
import com.gflix.app.models.Video
import com.gflix.app.providers.ExtensionContentProvider
import com.gflix.app.providers.Provider
import com.gflix.extcore.ExtLink
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Milestone 1 facade name (kept for compat + verify gate).
 * Thin delegate to [ExtensionContentProvider] (the real Provider impl backed
 * by [ExtensionEngine]); exists so callers referencing the original
 * `ExtProviderFacade` name keep working.
 */
class ExtProviderFacade : Provider {
    override val baseUrl: String get() = ExtensionContentProvider.baseUrl
    override val name: String get() = ExtensionContentProvider.name
    override val logo: String get() = ExtensionContentProvider.logo
    override val language: String get() = ExtensionContentProvider.language

    override suspend fun getHome(): List<Category> =
        ExtensionContentProvider.getHome()

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> =
        ExtensionContentProvider.search(query, page)

    override suspend fun getMovies(page: Int): List<Movie> =
        ExtensionContentProvider.getMovies(page)

    override suspend fun getTvShows(page: Int): List<TvShow> =
        ExtensionContentProvider.getTvShows(page)

    override suspend fun getMovie(id: String): Movie =
        ExtensionContentProvider.getMovie(id)

    override suspend fun getTvShow(id: String): TvShow =
        ExtensionContentProvider.getTvShow(id)

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        ExtensionContentProvider.getEpisodesBySeason(seasonId)

    override suspend fun getGenre(id: String, page: Int): Genre =
        ExtensionContentProvider.getGenre(id, page)

    override suspend fun getPeople(id: String, page: Int): People =
        ExtensionContentProvider.getPeople(id, page)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        supervisorScope {
            withTimeoutOrNull(PER_API_TIMEOUT_MS) {
                ExtensionContentProvider.getServers(id, videoType)
            } ?: throw IllegalStateException("ExtProviderFacade getServers timed out for $id")
        }

    override suspend fun getVideo(server: Video.Server): Video =
        ExtensionContentProvider.getVideo(server)

    fun toVideoOrNull(link: ExtLink): Video? =
        CloudStreamAdapter.toVideoOrNull(link)

    companion object {
        const val PER_API_TIMEOUT_MS = 10_000L

        @Volatile
        private var INSTANCE: ExtProviderFacade? = null

        fun getInstance(): ExtProviderFacade =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ExtProviderFacade().also { INSTANCE = it }
            }
    }
}
