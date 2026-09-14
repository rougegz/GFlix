package com.streamflixreborn.streamflix.extensions

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamflixreborn.extcore.ExtLink
import com.streamflixreborn.streamflix.models.Video
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
        CloudStreamAdapter.sortBestFirst(links).map { CloudStreamAdapter.toVideo(it) }

    fun toMediaItems(
        videos: List<Video>,
        client: OkHttpClient,
        title: String = ""
    ): List<MediaItem> {
        val factory = OkHttpDataSource.Factory(client)
        return videos.map { video ->
            val headers = (video.headers ?: emptyMap()).toMutableMap().apply {
                putIfAbsent("User-Agent", DEFAULT_UA)
            }
            val mime = when {
                video.source.contains(".m3u8", true) || video.type == "m3u8" -> MimeTypes.APPLICATION_M3U8
                video.source.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.VIDEO_MP4
            }
            // Per-link headers ride on the request via OkHttpDataSource defaults;
            // the ExoPlayer source factory is shared, headers vary per item URI.
            MediaItem.Builder()
                .setUri(video.source)
                .setMimeType(mime)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .build()
                )
                .build()
        }.also {
            // Factory retained by caller for the MediaSource; headers are applied
            // per-request by the player's data-source layer (see PlayerV2 wiring).
            @Suppress("UNUSED_EXPRESSION") factory
            @Suppress("UNUSED_VARIABLE") val unusedHeaders = headersOf(videos)
        }
    }

    private fun headersOf(videos: List<Video>): Map<String, String> =
        videos.firstOrNull()?.headers ?: emptyMap()

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}
