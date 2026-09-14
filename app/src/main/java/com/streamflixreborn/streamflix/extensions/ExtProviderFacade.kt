package com.streamflixreborn.streamflix.extensions

import android.util.Log
import com.streamflixreborn.extcore.ExtContentApi
import com.streamflixreborn.extcore.ExtSearchItem
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Unified [Provider] façade over all *enabled* CloudStream extensions.
 *
 * Each extension is isolated: one failing source never breaks the merged
 * result (mirrors CloudStream multi-provider search). Catalog/metadata
 * still comes from TMDB; extensions only supply playable links, so most
 * methods delegate to TMDB-shaped shells and only search/servers/video fan
 * out to [ExtContentApi] instances supplied by [apiFor].
 */
class ExtProviderFacade(
    private val apis: () -> List<ExtContentApi> = { emptyList() },
    private val tmdbFallback: Provider? = null
) : Provider {

    override val baseUrl: String = "extensions://local"
    override val name: String = "Extensions"
    override val logo: String = ""
    override val language: String = "multi"

    private fun enabled(): List<ExtContentApi> = runCatching { apis() }.getOrDefault(emptyList())

    override suspend fun getHome(): List<Category> {
        // Home stays TMDB-driven; extensions contribute via search. Keep empty
        // here so HomeViewModel falls back to TMDB without per-extension fan-out.
        return tmdbFallback?.getHome() ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val results = enabled().map { api ->
            async {
                runCatching { api.search(query).map { it.toItem(api.extensionId) } }
                    .getOrElse { e ->
                        Log.w(TAG, "search failed for ${api.extensionId}: ${e.message}")
                        emptyList()
                    }
            }
        }.awaitAll().flatten()
        // De-duplicate by (extensionId, id) to keep stable keys.
        results.distinctBy { "${it.extId}" }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        tmdbFallback?.getMovies(page) ?: emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        tmdbFallback?.getTvShows(page) ?: emptyList()

    override suspend fun getMovie(id: String): Movie {
        val extId = id.substringAfter("ext:", "").substringBefore(":").ifBlank { null }
        // Extension ids are opaque; metadata resolution stays TMDB-side for now.
        return tmdbFallback?.getMovie(id) ?: Movie(id = id, title = extId ?: id)
    }

    override suspend fun getTvShow(id: String): TvShow =
        tmdbFallback?.getTvShow(id) ?: TvShow(id = id, title = id)

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        tmdbFallback?.getEpisodesBySeason(seasonId) ?: emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre =
        tmdbFallback?.getGenre(id, page) ?: Genre(id = id, name = id)

    override suspend fun getPeople(id: String, page: Int): People =
        tmdbFallback?.getPeople(id, page) ?: People(id = id, name = id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val dataUrl = id.substringAfter("ext:", id)
        val servers = mutableListOf<Video.Server>()
        for (api in enabled()) {
            val links = runCatching { api.loadLinks(dataUrl) }.getOrElse {
                Log.w(TAG, "loadLinks failed for ${api.extensionId}: ${it.message}")
                emptyList()
            }
            CloudStreamAdapter.sortBestFirst(links).forEachIndexed { index, link ->
                val server = CloudStreamAdapter.toServer(link, servers.size + index)
                server.video = CloudStreamAdapter.toVideo(link)
                servers += server
            }
        }
        if (servers.isEmpty()) throw IllegalStateException("No extension links for $id")
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Facade pre-resolves Video at getServers time (server.video); keep the
        // lookup cheap and offline so the player never shows a server grid fetch.
        return server.video ?: throw IllegalStateException("Server has no resolved video: ${server.name}")
    }

    private fun ExtSearchItem.toItem(extensionId: String): ExtItem {
        val mapped: AppAdapter.Item = if (tvType.equals("Movie", true)) {
            Movie(id = "ext:$extensionId:$id", title = title, poster = posterUrl)
        } else {
            TvShow(id = "ext:$extensionId:$id", title = title, poster = posterUrl)
        }
        return ExtItem(mapped, "ext:$extensionId:$id")
    }

    /** Stable wrapper so distinctBy keys survive Movie/TvShow equality quirks. */
    data class ExtItem(val item: AppAdapter.Item, val extId: String) : AppAdapter.Item by item

    companion object {
        private const val TAG = "ExtProviderFacade"
    }
}
