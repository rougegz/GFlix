package com.gflix.extcore

import org.junit.Assert.*
import org.junit.Test

private fun meta(version: Int, status: Int = PROVIDER_STATUS_OK) = CsExtensionMeta(
    url = "https://cdn.example/demo.cs3",
    status = status,
    version = version,
    name = "Demo",
    internalName = "demo.demo",
    repositoryUrl = "https://example.com/repo.json"
)

class InstallerTest {

    @Test fun `fresh install needs download`() {
        val plan = ExtensionInstaller.planInstall(meta(2), null)

        assertTrue(plan.needsDownload)
        assertEquals("not-installed", plan.reason)
        assertTrue(plan.relativePath.startsWith("Extensions/"))
        assertTrue(plan.relativePath.endsWith(".cs3"))
    }

    @Test fun `same version is up to date`() {
        val installed = InstalledExtension(meta(2), "Extensions/x/demo.cs3", true, 2)
        val plan = ExtensionInstaller.planInstall(meta(2), installed)

        assertFalse(plan.needsDownload)
        assertEquals("up-to-date", plan.reason)
    }

    @Test fun `bumped version triggers update`() {
        val installed = InstalledExtension(meta(2), "Extensions/x/demo.cs3", true, 2)
        val plan = ExtensionInstaller.planInstall(meta(3), installed)

        assertTrue(plan.needsDownload)
        assertEquals("update-available", plan.reason)
    }

    @Test fun `disabled remote never downloads`() {
        val installed = InstalledExtension(meta(2), "Extensions/x/demo.cs3", true, 2)
        val plan = ExtensionInstaller.planInstall(meta(9, PROVIDER_STATUS_DOWN), installed)

        assertFalse(plan.needsDownload)
        assertEquals("disabled-remote", plan.reason)
        assertEquals(ExtensionStatus.DISABLED_REMOTE, installed.copy(meta = meta(9, PROVIDER_STATUS_DOWN)).status())
    }

    @Test fun `sha256 verification matches and skips when null`() {
        assertTrue(verifySha256Hex(null, "abc"))
        assertTrue(verifySha256Hex("sha256-ABC", "abc"))
        assertFalse(verifySha256Hex("sha256-abc", "def"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sha256 rejects unknown prefix`() {
        verifySha256Hex("md5-abc", "abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non cs3 url rejected`() {
        ExtensionInstaller.planInstall(meta(1).copy(url = "https://cdn.example/demo.apk"), null)
    }

    @Test fun `sanitize keeps storage layout safe`() {
        assertTrue(extensionFileName("demo.demo").endsWith(".cs3"))
        assertFalse(sanitizeExtensionFileName("../../evil").contains("/"))
    }
}
