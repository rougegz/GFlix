package com.streamflixreborn.extcore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class FakeHttp(private val bodies: Map<String, String>) : HttpGet {
    override suspend fun get(url: String): String =
        bodies[url] ?: error("Unexpected GET $url")
}

private const val REPO_URL = "https://example.com/repo.json"
private const val PLUGINS_URL = "https://example.com/plugins.json"

private val repoJson = """
{
  "name": "Demo Repo",
  "description": "test",
  "manifestVersion": 1,
  "pluginLists": ["$PLUGINS_URL"]
}
""".trimIndent()

private val pluginsJson = """
[
  {
    "url": "https://cdn.example/demo.cs3",
    "status": 1,
    "version": 3,
    "apiVersion": 1,
    "name": "Demo",
    "internalName": "demo.demo",
    "authors": ["tester"],
    "language": "en",
    "tvTypes": ["Movie"]
  }
]
""".trimIndent()

class RepoManagerTest {

    private fun manager() = RepoManager(FakeHttp(mapOf(REPO_URL to repoJson, PLUGINS_URL to pluginsJson)))

    @Test fun `add repo stores and lists one extension`() = runTest {
        val mgr = manager()
        val repo = mgr.addRepo(REPO_URL)

        assertEquals("Demo Repo", repo.name)
        assertEquals(listOf(REPO_URL), mgr.listRepos().map { it.url })
        assertEquals(listOf("demo.demo"), mgr.listAvailable().map { it.internalName })
    }

    @Test fun `add repo trims and defaults to https`() = runTest {
        val mgr = manager()
        mgr.addRepo("  example.com/repo.json  ")

        assertEquals(1, mgr.listRepos().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `add repo rejects blank url`() = runTest {
        manager().addRepo("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `add repo rejects plain http`() = runTest {
        manager().addRepo("http://example.com/repo.json")
    }

    @Test fun `remove repo deletes listing`() = runTest {
        val mgr = manager()
        mgr.addRepo(REPO_URL)

        assertTrue(mgr.removeRepo(REPO_URL))
        assertTrue(mgr.listAvailable().isEmpty())
        assertFalse(mgr.removeRepo(REPO_URL))
    }

    @Test fun `refresh repo re-fetches`() = runTest {
        val mgr = manager()
        mgr.addRepo(REPO_URL)
        val refreshed = mgr.refreshRepo(REPO_URL)

        assertEquals("Demo Repo", refreshed.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refresh unknown repo fails`() = runTest {
        manager().refreshRepo(REPO_URL)
    }

    @Test fun `empty query returns all in fake api`() = runTest {
        val api = FakeExtApi()

        assertEquals(2, api.search("").size)
        assertEquals(1, api.search("show").size)
        assertEquals(2, api.loadLinks("fake://1").size)
        // 1080 first (best-first sort).
        assertEquals(1080, api.loadLinks("fake://1").first().quality)
    }
}
