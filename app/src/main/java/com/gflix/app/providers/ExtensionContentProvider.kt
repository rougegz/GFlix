package com.gflix.app.providers

import android.util.Log
import com.gflix.app.GFlixApp
import com.gflix.app.adapters.AppAdapter
import com.gflix.app.extensions.CloudStreamAdapter
import com.gflix.app.extensions.ExtensionEngine
import com.gflix.app.models.Category
import com.gflix.app.models.Episode
import com.gflix.app.models.Genre
import com.gflix.app.models.Movie
import com.gflix.app.models.People
import com.gflix.app.models.Season
import com.gflix.app.models.TvShow
import com.gflix.app.models.Video
import com.gflix.app.utils.UserPreferences
import com.gflix.extcore.ExtContentApi
import com.gflix.extcore.ExtLoadData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

object ExtensionContentProvider : Provider {

    override val baseUrl: String = "extensions://local"
    override val name: String = "Extensions"
    override val logo: String = ""
    override val language: String = "multi"

    private const val TAG = "ExtContentProvider"
    private const val TIMEOUT_MS = 10_000L

    private val showCache = LinkedHashMap<String, ExtLoadData>()

    private suspend fun engine(): ExtensionEngine =
        ExtensionEngine.getInstance(GFlixApp.instance)

    private suspend fun allApis(): List<ExtContentApi> = runCatching {
        engine().apis()
    }.getOrDefault(emptyList())

    private suspend fun currentApis(): List<ExtContentApi> {
        val all = allApis()
        val current = UserPreferences.currentExtensionId
        if (current.isBlank()) return all
        // Strict: a stale/unknown selection shows its own empty state instead
        // of silently falling back to every extension (which made selection
        // look broken). Selecting an extension auto-enables it (ViewModel).
        return all.filter { it.extensionId == current }
    }

    private fun extIdOf(id: String): String =
        id.removePrefix("ext:").substringBefore(":").takeIf { it.isNotEmpty() } ?: ""

    private fun itemIdOf(id: String): String =
        if (id.startsWith("ext:")) id.substringAfter("ext:").substringAfter(":", id) else id

    private suspend fun apiFor(extId: String): ExtContentApi? {
        val all = allApis()
        if (extId.isNotBlank()) all.find { it.extensionId == extId }?.let { return it }
        return all.firstOrNull()
    }

    override suspend fun getHome(): List<Category> = supervisorScope {
        val apis = currentApis()
        if (apis.isEmpty()) return@supervisorScope emptyList()
        val items = apis.map { api ->
            async {
                withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { api.mainPage(1) }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.awaitAll().flatten()
        if (items.isEmpty()) return@supervisorScope emptyList()
        listOf(Category(name = "Discover", list = CloudStreamAdapter.toSearchItems(items)))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = supervisorScope {
        if (query.isBlank()) return@supervisorScope emptyList()
        currentApis().map { api ->
            async {
                withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { api.search(query) }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.awaitAll().flatten().distinctBy { "${it.extensionId}:${it.id}" }
            .let { CloudStreamAdapter.toSearchItems(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovie(id: String): Movie {
        val data = loadData(id) ?: return Movie(id = id, title = id)
        return Movie(id = id, title = data.title, overview = data.plot, poster = data.posterUrl)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val data = loadData(id) ?: return TvShow(id = id, title = id)
        val show = TvShow(id = id, title = data.title, overview = data.plot, poster = data.posterUrl)
        val seasons = data.episodes.groupBy { it.season }.toSortedMap().map { (num, eps) ->
            val seasonId = "$id#S$num"
            val season = Season(id = seasonId, number = num, tvShow = show)
            season.episodes = eps.sortedBy { it.episode }.mapIndexed { index, ep ->
                Episode(
                    id = "$id#E$num:${ep.id.ifBlank { index.toString() }}",
                    number = ep.episode.takeIf { it > 0 } ?: (index + 1),
                    title = ep.name,
                    poster = ep.posterUrl,
                    tvShow = show,
                    season = season
                )
            }
            season
        }
        return show.copy(seasons = seasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("#S", seasonId)
        val seasonNum = seasonId.substringAfter("#S", "").toIntOrNull() ?: return emptyList()
        if (showId == seasonId) return emptyList()
        return try {
            getTvShow(showId).seasons.find { it.number == seasonNum }?.episodes ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "episodes failed for $seasonId: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre =
        Genre(id = id, name = id)

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val showId = id.substringBefore("#E", id)
        val dataUrl = episodeDataUrl(id) ?: showId.let { itemIdOf(it) }
        val api = apiFor(extIdOf(id)) ?: throw IllegalStateException("No extension loaded")
        val links = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { api.loadLinks(dataUrl) }.getOrDefault(emptyList())
        } ?: emptyList()
        val servers = mutableListOf<Video.Server>()
        var counter = 0
        for (link in CloudStreamAdapter.sortBestFirst(links)) {
            val video = CloudStreamAdapter.toVideoOrNull(link) ?: continue
            val server = CloudStreamAdapter.toServer(link, counter++)
            server.video = video
            servers += server
        }
        if (servers.isEmpty()) throw IllegalStateException("No extension links for $id")
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video =
        server.video ?: throw IllegalStateException("Server has no resolved video: ${server.name}")

    private suspend fun loadData(id: String): ExtLoadData? {
        val extId = extIdOf(id)
        val itemId = itemIdOf(id).substringBefore("#")
        val api = apiFor(extId) ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { api.load(itemId) }.getOrNull()
        }?.also { data ->
            synchronized(showCache) {
                showCache[id] = data
                if (showCache.size > 50) showCache.remove(showCache.keys.first())
            }
        }
    }

    private fun episodeDataUrl(episodeId: String): String? {
        val showId = episodeId.substringBefore("#E", "")
        if (showId.isEmpty() || showId == episodeId) return null
        val data = synchronized(showCache) { showCache[showId] } ?: return null
        val tail = episodeId.substringAfter("#E", "")
        val seasonNum = tail.substringBefore(":", "").toIntOrNull()
        val epKey = tail.substringAfter(":", "")
        val match = data.episodes.find {
            (seasonNum == null || it.season == seasonNum) &&
                (it.id == epKey || it.dataUrl == epKey)
        } ?: data.episodes.getOrNull(epKey.toIntOrNull() ?: -1)
        return match?.dataUrl?.ifBlank { null } ?: match?.id
    }
}
