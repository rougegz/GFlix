package com.streamflixreborn.streamflix.extensions

import com.streamflixreborn.extcore.ExtLink
import com.streamflixreborn.extcore.ExtSearchItem
import com.streamflixreborn.extcore.sortLinksBestFirst
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video

/**
 * Maps CloudStream-style extension payloads onto Streamflix models.
 * Keeps the single-URL [Video] shape during the transition: each [ExtLink]
 * becomes one `Video.Server` + one [Video] so the current player keeps
 * working while `PlayerViewModelV2` moves to multi-link resolution.
 */
object CloudStreamAdapter {

    fun toServer(link: ExtLink, index: Int): Video.Server {
        val label = link.qualityLabel.ifBlank {
            if (link.quality > 0) "${link.quality}p" else (link.extractorName.ifBlank { "Ext" })
        }
        return Video.Server(id = "ext:$index:${link.url.hashCode()}", name = label, src = link.url)
    }

    fun toVideo(link: ExtLink): Video {
        link.requireValid()
        val subtitles = emptyList<Video.Subtitle>()
        return Video(
            source = link.url,
            subtitles = subtitles,
            headers = link.headers + mapOf(
                "Referer" to link.referer
            ).filterValues { it.isNotBlank() },
            type = if (link.isM3u8) "m3u8" else "mp4"
        )
    }

    /** Non-throwing variant: one bad link never kills the whole list. */
    fun toVideoOrNull(link: ExtLink): Video? = runCatching { toVideo(link) }.getOrNull()

    /** Best-first ordering shared with `:ext-core` (quality desc, m3u8 first). */
    fun sortBestFirst(links: List<ExtLink>): List<ExtLink> = sortLinksBestFirst(links)

    fun toSearchItems(items: List<ExtSearchItem>): List<AppAdapter.Item> {
        // Minimal mapping: movies vs shows by tvType; details fragments resolve the rest.
        return items.map { item ->
            if (item.tvType.equals("Movie", true)) {
                Movie(id = "ext:${item.extensionId}:${item.id}", title = item.title, poster = item.posterUrl)
            } else {
                TvShow(id = "ext:${item.extensionId}:${item.id}", title = item.title, poster = item.posterUrl)
            }
        }
    }

    fun episodeShell(
        id: String,
        number: Int,
        title: String?,
        showId: String,
        showTitle: String,
        seasonNumber: Int
    ): Episode {
        // Placeholder shell; real metadata comes from TMDB + extension load().
        val show = TvShow(id = showId, title = showTitle)
        val season = Season(id = "$showId:$seasonNumber", number = seasonNumber, tvShow = show)
        return Episode(
            id = id,
            number = number,
            title = title,
            tvShow = show,
            season = season
        )
    }
}
