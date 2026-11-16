package com.abrogani.servetorr.engine

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import java.io.File

class EngineUpdateTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `updates when a previous version was installed or the user wanted the engine`() {
        expectThat(EngineUpdate.shouldUpdate(installed = false, wanted = false, previousVersions = listOf("1"))).isTrue()
        expectThat(EngineUpdate.shouldUpdate(installed = false, wanted = true, previousVersions = emptyList())).isTrue()
    }

    @Test
    fun `does nothing when installed or never wanted`() {
        expectThat(EngineUpdate.shouldUpdate(installed = true, wanted = true, previousVersions = listOf("1"))).isFalse()
        expectThat(EngineUpdate.shouldUpdate(installed = false, wanted = false, previousVersions = emptyList())).isFalse()
    }

    @Test
    fun `previous versions are other dirs holding the engine library`() {
        val root = temp.newFolder("engine")
        File(root, "1").apply { mkdirs(); File(this, EngineSource.LIBRARY_NAME).writeText("x") }
        File(root, "st-1").apply { mkdirs(); File(this, EngineSource.LIBRARY_NAME).writeText("x") }
        File(root, "2").apply { mkdirs(); File(this, EngineSource.LIBRARY_NAME).writeText("x") }
        File(root, "empty").mkdirs()
        File(root, "stray.bin").writeText("x")

        expectThat(EngineUpdate.previousVersions(root, current = "2")).isEqualTo(listOf("1", "st-1"))
        expectThat(EngineUpdate.previousVersions(File(temp.root, "missing"), current = "2")).isEqualTo(emptyList())
    }
}
