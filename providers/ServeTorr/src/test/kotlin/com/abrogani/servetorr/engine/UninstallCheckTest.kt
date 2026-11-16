package com.abrogani.servetorr.engine

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.io.File

class UninstallCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val self = "com.abrogani.servetorr.ServeTorr"

    @Test
    fun `reads the flx path from a path class loader description`() {
        val text = "dalvik.system.PathClassLoader[DexPathList[[zip file \"/storage/emulated/0/Android/data/com.flixclusive/files/" +
            "providers/user-1/abrogani-automatic-sniffle/ServeTorr.flx\"],nativeLibraryDirectories=[/system/lib64, /system_ext/lib64]]]"
        expectThat(UninstallCheck.flxPathFromLoaderText(text)).isEqualTo(
            "/storage/emulated/0/Android/data/com.flixclusive/files/providers/user-1/abrogani-automatic-sniffle/ServeTorr.flx"
        )
    }

    @Test
    fun `prefers the flx among several elements`() {
        val text = "PathClassLoader[DexPathList[[dex file \"/data/x/other.dex\", zip file \"/data/x/ServeTorr.flx\"],nativeLibraryDirectories=[]]]"
        expectThat(UninstallCheck.flxPathFromLoaderText(text)).isEqualTo("/data/x/ServeTorr.flx")
    }

    @Test
    fun `no path in an unrelated loader`() {
        expectThat(UninstallCheck.flxPathFromLoaderText("java.net.URLClassLoader@1234")).isNull()
    }

    @Test
    fun `candidates include the same provider of other users`() {
        val root = temp.newFolder("providers")
        val own = File(root, "user-1/repo/ServeTorr.flx")
        File(root, "user-2/repo").mkdirs()
        File(root, "user-3").mkdirs()
        File(root, "debug").mkdirs()
        expectThat(UninstallCheck.installCandidates(own).map { it.relativeTo(root).path }).containsExactlyInAnyOrder(
            "user-1/repo/ServeTorr.flx".replace('/', File.separatorChar),
            "user-2/repo/ServeTorr.flx".replace('/', File.separatorChar),
            "user-3/repo/ServeTorr.flx".replace('/', File.separatorChar),
        )
    }

    @Test
    fun `debug providers only check their own file`() {
        val own = File(temp.root, "Flixclusive/providers/debug/ServeTorr.flx")
        expectThat(UninstallCheck.installCandidates(own)).isEqualTo(listOf(own))
    }

    @Test
    fun `uninstalled only when the plugin is gone from the host and every file is gone`() {
        val flx = listOf(File("/p/user-1/r/ServeTorr.flx"), File("/p/user-2/r/ServeTorr.flx"))
        val gone: (File) -> Boolean = { false }

        expectThat(UninstallCheck.isUninstalled(listOf("other.Provider"), self, flx, gone)).isTrue()
        expectThat(UninstallCheck.isUninstalled(emptyList(), self, flx, gone)).isTrue()
    }

    @Test
    fun `not uninstalled while any copy is still loaded`() {
        expectThat(UninstallCheck.isUninstalled(listOf("other.Provider", self), self, listOf(File("/x.flx"))) { false }).isFalse()
    }

    @Test
    fun `not uninstalled while a file still exists`() {
        val flx = listOf(File("/p/user-1/r/ServeTorr.flx"), File("/p/user-2/r/ServeTorr.flx"))
        expectThat(UninstallCheck.isUninstalled(emptyList(), self, flx) { it.path.contains("user-2") }).isFalse()
        expectThat(UninstallCheck.isUninstalled(emptyList(), self, flx) { it.path.contains("user-1") }).isFalse()
    }

    @Test
    fun `unknown host state or file never deletes`() {
        expectThat(UninstallCheck.isUninstalled(null, self, listOf(File("/x.flx"))) { false }).isFalse()
        expectThat(UninstallCheck.isUninstalled(emptyList(), self, null) { false }).isFalse()
        expectThat(UninstallCheck.isUninstalled(emptyList(), self, emptyList()) { false }).isFalse()
    }
}
