package com.abrogani.servetorr.settings

import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class SizeFormatTest {
    @Test
    fun `formats bytes with binary units`() {
        expectThat(formatBytes(0)).isEqualTo("0 B")
        expectThat(formatBytes(1023)).isEqualTo("1023 B")
        expectThat(formatBytes(1536)).isEqualTo("1.5 KB")
        expectThat(formatBytes(25L * 1024 * 1024)).isEqualTo("25 MB")
        expectThat(formatBytes(3L * 1024 * 1024 * 1024 / 2)).isEqualTo("1.5 GB")
    }

    @Test
    fun `formats speeds per second`() {
        expectThat(formatSpeed(2048)).isEqualTo("2.0 KB/s")
    }

    @Test
    fun `describes cache clearing`() {
        expectThat(describeClear(0, 0)).isEqualTo("Nothing to clear.")
        expectThat(describeClear(1, 1024L * 1024)).isEqualTo("Removed 1 torrent, freed 1.0 MB.")
        expectThat(describeClear(3, 5L * 1024 * 1024 * 1024)).isEqualTo("Removed 3 torrents, freed 5.0 GB.")
    }
}
