package com.gflix.extcore

/**
 * Thin host-side façade over a loaded CloudStream `MainAPI`.
 * The Dex implementation lives in `:app .../extensions/`; `:ext-core`
 * only defines the contract so it stays JVM-testable.
 */
interface ExtContentApi {
    val extensionId: String

    suspend fun search(query: String): List<ExtSearchItem>

    suspend fun mainPage(page: Int = 1): List<ExtSearchItem>

    suspend fun load(id: String): ExtLoadData

    /**
     * CloudStream `loadLinks` equivalent.
     * Returns every playable [ExtLink] (sorted best-first by caller).
     */
    suspend fun loadLinks(dataUrl: String): List<ExtLink>
}

/** Sort best-first: higher quality first, m3u8 preferred on ties. */
fun sortLinksBestFirst(links: List<ExtLink>): List<ExtLink> =
    links.sortedWith(
        compareByDescending<ExtLink> { it.quality }
            .thenByDescending { it.isM3u8 }
            .thenBy { it.url }
    )

/** Deterministic fake for JVM tests and UI previews (no network). */
class FakeExtApi(
    override val extensionId: String = "fake.demo",
    private val items: List<ExtSearchItem> = listOf(
        ExtSearchItem("1", "Demo Movie", null, "Movie", "fake.demo"),
        ExtSearchItem("2", "Demo Show", null, "TvSeries", "fake.demo")
    ),
    private val links: List<ExtLink> = listOf(
        ExtLink("https://cdn.example/1080.m3u8", "https://example/", 1080, "1080p", true),
        ExtLink("https://cdn.example/720.mp4", "https://example/", 720, "720p", false)
    )
) : ExtContentApi {
    override suspend fun search(query: String): List<ExtSearchItem> =
        if (query.isBlank()) items else items.filter { it.title.contains(query, true) }

    override suspend fun mainPage(page: Int): List<ExtSearchItem> = items

    override suspend fun load(id: String): ExtLoadData {
        val found = items.firstOrNull { it.id == id } ?: items.first()
        return ExtLoadData(found.id, found.title, found.posterUrl, null, null, "fake://$id")
    }

    override suspend fun loadLinks(dataUrl: String): List<ExtLink> =
        sortLinksBestFirst(links)
}
