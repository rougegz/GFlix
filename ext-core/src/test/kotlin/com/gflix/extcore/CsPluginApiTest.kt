package com.gflix.extcore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

// Mimics CloudStream MainAPI shapes (structural, no dependency).
class FakeSearchResponse(val url: String, val name: String, val posterUrl: String, val type: String)

class FakeLoadResponse(
    val url: String,
    val name: String,
    val posterUrl: String,
    val plot: String,
    val year: Int
)

class FakeExtractorLink(
    val url: String,
    val name: String,
    val referer: String,
    val quality: Int,
    val isM3u8: Boolean,
    val headers: Map<String, String> = emptyMap()
)

class FakeMainApi {
    @Suppress("unused")
    suspend fun search(query: String): List<FakeSearchResponse> =
        listOf(FakeSearchResponse("s1", "Telugu $query", "p1", "Movie"))

    @Suppress("unused")
    suspend fun getMainPage(page: Int): List<FakeSearchResponse> =
        listOf(FakeSearchResponse("h$page", "Home $page", "", "TvSeries"))

    @Suppress("unused")
    suspend fun load(url: String): FakeLoadResponse =
        FakeLoadResponse(url, "Title", "poster", "plot", 2024)

    @Suppress("unused")
    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (Any?) -> Unit,
        callback: suspend (Any?) -> Unit
    ): Boolean {
        callback(FakeExtractorLink("$data/720.mp4", "720p", "https://r/", 720, false))
        callback(FakeExtractorLink("$data/1080.m3u8", "1080p", "", 1080, true))
        return true
    }
}

class CsPluginApiTest {

    private val api = CsPluginApi(FakeMainApi(), "fake.test")

    @Test fun `search maps structural response`() = runTest {
        val items = api.search("Movie")

        assertEquals(1, items.size)
        assertEquals("s1", items[0].id)
        assertEquals("fake.test", items[0].extensionId)
    }

    @Test fun `mainPage maps structural response`() = runTest {
        val items = api.mainPage(2)

        assertEquals(1, items.size)
        assertEquals("h2", items[0].id)
    }

    @Test fun `load maps structural response`() = runTest {
        val data = api.load("s1")

        assertEquals("Title", data.title)
        assertEquals(2024, data.year)
    }

    @Test fun `loadLinks collects callback links best first`() = runTest {
        val links = api.loadLinks("https://cdn/x")

        assertEquals(2, links.size)
        assertEquals(1080, links[0].quality)
        assertTrue(links[0].isM3u8)
    }

    @Test fun `unknown plugin yields empty not throw`() = runTest {
        val empty = CsPluginApi(object {}, "empty")

        assertTrue(empty.search("q").isEmpty())
        assertTrue(empty.mainPage(1).isEmpty())
        assertTrue(empty.loadLinks("x").isEmpty())
        assertEquals("x", empty.load("x").id)
    }
}
