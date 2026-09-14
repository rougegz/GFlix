package com.gflix.app.extensions

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gflix.extcore.ExtLink
import com.gflix.app.models.Video
import okhttp3.OkHttpClient

/**
 * CloudStream-style link → player pipeline (no server grid).
 *
 * `loadLinks` already produced [ExtLink]s; this resolver sorts best-first,
 * builds one [Video]/[MediaItem] per link with proper headers, and lets the
 * player auto-fallback to the next link on failure.
 */
object ExtLinkResolver {

    fun resolveVideos(links: List<ExtLink>): List<Video> =
        CloudStreamAdapter.sortBestFirst(links).mapNotNull { CloudStreamAdapter.toVideoOrNull(it) }

    /** Headers actually sent for a link (Referer + stored headers + UA). */
    fun requestHeaders(link: ExtLink): Map<String, String> =
        (link.headers + mapOf("Referer" to link.referer))
            .filterValues { it.isNotBlank() }
            .toMutableMap()
            .apply { putIfAbsent("User-Agent", DEFAULT_UA) }

    /** Per-link ExoPlayer data-source factory: headers are set as request defaults. */
    fun dataSourceFactory(link: ExtLink, client: OkHttpClient): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).setDefaultRequestProperties(requestHeaders(link))

    fun mediaItem(link: ExtLink, title: String = ""): MediaItem {
        val mime = when {
            link.url.contains(".m3u8", true) || link.isM3u8 -> MimeTypes.APPLICATION_M3U8
            link.url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> MimeTypes.VIDEO_MP4
        }
        return MediaItem.Builder()
            .setUri(link.url)
            .setMimeType(mime)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
            .build()
    }

    /**
     * Paired items + factories for a link list. The player must use
     * `factories[i]` for `items[i]` (or rebuild per link on fallback) —
     * headers differ per link and cannot ride on one shared factory.
     */
    fun toMediaItems(
        links: List<ExtLink>,
        client: OkHttpClient,
        title: String = ""
    ): Pair<List<MediaItem>, List<OkHttpDataSource.Factory>> {
        val sorted = CloudStreamAdapter.sortBestFirst(links)
        return Pair(sorted.map { mediaItem(it, title) }, sorted.map { dataSourceFactory(it, client) })
    }

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}
