package com.gflix.app.extractors

/**
 * Holds the latest expiring-URL query string so the player OkHttp interceptor
 * can re-attach it to follow-up segment requests ([Video.maintainToken]).
 *
 * The per-site extractors that used to refresh this are gone (extension
 * runtime); extension links carry their own headers instead. Kept as a
 * nullable holder so playback code keeps compiling and any writer can
 * publish a token again later.
 */
object TokenManager {

    @Volatile
    var latestQuery: String? = null
}
