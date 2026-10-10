package com.abrogani.streamplay.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginIndexTest {
    private fun index(status: Int = 1, hash: String = "sha256-" + "ABCDEF".repeat(10) + "ABCD") = """
        [
          {"internalName":"Other","version":1,"url":"https://example.com/Other.cs3","fileHash":"sha256-0000000000000000000000000000000000000000000000000000000000000000","status":1},
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
        assertEquals("abcdef".repeat(10) + "abcd", PluginIndex.parse(index()).sha256)
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

    private val validHash = "sha256-" + "a".repeat(64)

    private fun entry(fields: String) =
        """[{"internalName":"StreamPlay","status":1,$fields}]"""

    @Test
    fun `fails on a non json response`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse("<html>captive portal</html>")
        }
    }

    @Test
    fun `fails when the root is not an array`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse("""{"internalName":"StreamPlay"}""")
        }
    }

    @Test
    fun `ignores entries that are not objects`() {
        val json = """[1, "x", null, {"internalName":"StreamPlay","version":3,"url":"https://e.com/a.cs3","fileHash":"$validHash"}]"""

        assertEquals(3, PluginIndex.parse(json).version)
    }

    @Test
    fun `fails when the url is missing`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse(entry(""""version":1,"fileHash":"$validHash""""))
        }
    }

    @Test
    fun `fails when the version is not a number`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse(entry(""""version":"abc","url":"https://e.com/a.cs3","fileHash":"$validHash""""))
        }
    }

    @Test
    fun `rejects a hash that is not sha256`() {
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse(entry(""""version":1,"url":"https://e.com/a.cs3","fileHash":"sha1-abc""""))
        }
        assertThrows(IllegalStateException::class.java) {
            PluginIndex.parse(entry(""""version":1,"url":"https://e.com/a.cs3","fileHash":"sha256-abc""""))
        }
    }

    @Test
    fun `rejects a non https url`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginIndex.parse(entry(""""version":1,"url":"http://e.com/a.cs3","fileHash":"$validHash""""))
        }
    }

    @Test
    fun `uses the highest version when an entry is duplicated`() {
        val json = """
            [
              {"internalName":"StreamPlay","version":5,"url":"https://e.com/5.cs3","fileHash":"$validHash"},
              {"internalName":"StreamPlay","version":9,"url":"https://e.com/9.cs3","fileHash":"$validHash"},
              {"internalName":"StreamPlay","version":7,"url":"https://e.com/7.cs3","fileHash":"$validHash"}
            ]
        """.trimIndent()

        assertEquals(9, PluginIndex.parse(json).version)
    }
}
