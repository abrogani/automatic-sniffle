package com.abrogani.streamplay.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginIndexTest {
    private fun index(status: Int = 1, hash: String = "sha256-ABCDEF") = """
        [
          {"internalName":"Other","version":1,"url":"https://example.com/Other.cs3","fileHash":"sha256-00","status":1},
          {"internalName":"StreamPlay","version":687,"url":"https://example.com/StreamPlay.cs3","fileHash":"$hash","status":$status}
        ]
    """.trimIndent()

    @Test
    fun `parses the StreamPlay entry`() {
        val plugin = PluginIndex.parse(index())

        assertEquals("https://example.com/StreamPlay.cs3", plugin.url)
        assertEquals(687, plugin.version)
    }

    @Test
    fun `strips the hash prefix and lowercases it`() {
        assertEquals("abcdef", PluginIndex.parse(index()).sha256)
    }

    @Test
    fun `picks the entry by internal name`() {
        assertEquals(1, PluginIndex.parse(index(), name = "Other").version)
    }

    @Test
    fun `fails when the plugin is missing`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse(index(), name = "Missing")
        }
    }

    @Test
    fun `fails when the plugin is marked down`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse(index(status = 0))
        }
    }
}
