package com.gflix.extcore

/** Pure decision logic for install/update/delete; I/O stays in :app. */
object ExtensionInstaller {

    data class InstallPlan(
        val meta: CsExtensionMeta,
        /** Relative storage path: Extensions/<repoFolder>/<file>.cs3 */
        val relativePath: String,
        val needsDownload: Boolean,
        val reason: String
    )

    fun planInstall(
        meta: CsExtensionMeta,
        installed: InstalledExtension?
    ): InstallPlan {
        meta.requireValid()
        val repoPart = repoFolderName(meta.repositoryUrl ?: "default")
        val relativePath = "Extensions/$repoPart/${extensionFileName(meta.internalName)}"
        if (installed == null) {
            return InstallPlan(meta, relativePath, true, "not-installed")
        }
        if (meta.isDisabled()) {
            return InstallPlan(meta, installed.filePath, false, "disabled-remote")
        }
        val outdated = meta.isOutdated(installed.installedVersion)
        return InstallPlan(
            meta, installed.filePath, outdated,
            if (outdated) "update-available" else "up-to-date"
        )
    }

    fun applyEnabled(installed: InstalledExtension, enabled: Boolean): InstalledExtension =
        installed.copy(enabled = enabled)

    fun markUpdated(installed: InstalledExtension, meta: CsExtensionMeta): InstalledExtension {
        meta.requireValid()
        return installed.copy(meta = meta, installedVersion = meta.version)
    }

    /** Files the host must delete on uninstall (cs3 + optional oat sidecar). */
    fun filesToDelete(installed: InstalledExtension): List<String> =
        listOf(installed.filePath, installed.filePath + ".oat")
}
