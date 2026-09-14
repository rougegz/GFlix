package com.gflix.extcore

/**
 * Binds a loaded CloudStream-style plugin instance to [ExtContentApi]
 * using structural reflection (no CloudStream classes on the classpath).
 *
 * Expected shapes (CloudStream MainAPI):
 * - search(query: String): List<SearchResponse{url, name, posterUrl, type}>
 * - getMainPage(page: Int): ... (list-bearing response) — single-arg variant
 * - load(url: String): LoadResponse{url, name, posterUrl, plot, year}
 * - loadLinks(data, isCasting, subtitleCallback, callback): Boolean where
 *   callback is `suspend (link) -> Unit` and link has url/name/referer/
 *   quality/isM3u8/headers.
 * Every call is isolated: unsupported shapes yield empty results, never throw.
 */
class CsPluginApi(
    private val plugin: Any,
    override val extensionId: String
) : ExtContentApi {

    override suspend fun search(query: String): List<ExtSearchItem> = runCatching {
        val raw = callSuspend(plugin, "search", query) as? List<*> ?: return emptyList()
        raw.filterNotNull().map { item ->
            ExtSearchItem(
                id = strProp(item, "url", "id"),
                title = strProp(item, "name", "title"),
                posterUrl = strProp(item, "posterUrl", "poster").ifBlank { null },
                tvType = strProp(item, "type", "tvType").ifBlank { "Movie" },
                extensionId = extensionId
            )
        }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    override suspend fun mainPage(page: Int): List<ExtSearchItem> = runCatching {
        val methods = plugin.javaClass.methods.map { it.name }.toSet()
        val raw: List<*> = when {
            methods.contains("getMainPage") ->
                flatten(callSuspend(plugin, "getMainPage", page))
            methods.contains("mainPage") ->
                flatten(callSuspend(plugin, "mainPage", page))
            else -> return emptyList()
        }
        raw.filterNotNull().map { item ->
            ExtSearchItem(
                id = strProp(item, "url", "id"),
                title = strProp(item, "name", "title"),
                posterUrl = strProp(item, "posterUrl", "poster").ifBlank { null },
                tvType = strProp(item, "type", "tvType").ifBlank { "Movie" },
                extensionId = extensionId
            )
        }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    override suspend fun load(id: String): ExtLoadData = runCatching {
        val raw = callSuspend(plugin, "load", id) ?: return ExtLoadData(id, id)
        ExtLoadData(
            id = strProp(raw, "url", "id").ifBlank { id },
            title = strProp(raw, "name", "title").ifBlank { id },
            posterUrl = strProp(raw, "posterUrl", "poster").ifBlank { null },
            plot = strProp(raw, "plot", "overview", "description").ifBlank { null },
            year = intProp(raw, "year").takeIf { it > 0 },
            dataUrl = strProp(raw, "dataUrl", "url").ifBlank { id },
            episodes = listProp(raw, "episodes").filterNotNull().map { ep ->
                ExtEpisode(
                    id = strProp(ep, "url", "id", "data").ifBlank { id },
                    name = strProp(ep, "name", "title").ifBlank { null },
                    season = intProp(ep, "season").takeIf { it > 0 } ?: 1,
                    episode = intProp(ep, "episode", "number", "position"),
                    posterUrl = strProp(ep, "posterUrl", "poster", "thumbUrl").ifBlank { null },
                    dataUrl = strProp(ep, "data", "url").ifBlank { id }
                )
            }
        )
    }.getOrDefault(ExtLoadData(id, id))

    override suspend fun loadLinks(dataUrl: String): List<ExtLink> = runCatching {
        val found = mutableListOf<ExtLink>()
        val linkCb: suspend (Any?) -> Unit = { link ->
            if (link != null) {
                val url = strProp(link, "url", "source", "data")
                if (url.isNotBlank()) {
                    @Suppress("UNCHECKED_CAST")
                    val headers = (readProp(link, "headers") as? Map<*, *>)
                        ?.entries?.associate { it.key.toString() to it.value.toString() }
                        ?: emptyMap()
                    found += ExtLink(
                        url = url,
                        referer = strProp(link, "referer", "referrer"),
                        quality = intProp(link, "quality"),
                        qualityLabel = strProp(link, "name", "qualityLabel", "qualityString"),
                        isM3u8 = boolProp(link, "isM3u8", "isM3U8") ||
                            url.contains(".m3u8", ignoreCase = true),
                        headers = headers,
                        extractorName = extensionId
                    )
                }
            }
        }
        val subCb: (Any?) -> Unit = {}
        val methods = plugin.javaClass.methods.map { it.name }.toSet()
        val variants: List<List<Any?>> = listOf(
            listOf(dataUrl, false, subCb, linkCb),
            listOf(dataUrl, subCb, linkCb),
            listOf(dataUrl, linkCb)
        )
        var invoked = false
        for (args in variants) {
            if (!methods.contains("loadLinks")) break
            runCatching {
                callSuspend(plugin, "loadLinks", *args.toTypedArray())
                invoked = true
            }
            if (invoked) break
        }
        sortLinksBestFirst(found)
    }.getOrDefault(emptyList())

    private fun flatten(raw: Any?): List<*> {
        if (raw is List<*>) {
            if (raw.isEmpty()) return raw
            val first = raw.firstOrNull()
            val nested = listProp(first ?: return raw, "list", "items", "entries")
            if (nested.isNotEmpty()) return raw.flatMap { flattenList(it) }
            return raw
        }
        val nested = raw?.let { listProp(it, "list", "items", "entries") } ?: emptyList<Any?>()
        if (nested.isNotEmpty()) return nested.flatMap { flattenList(it) }
        return emptyList<Any?>()
    }

    private fun flattenList(item: Any?): List<*> =
        if (item is List<*>) item else listOf(item)
}
