package com.abrogani.servetorr.engine

import com.abrogani.servetorr.engine.EngineInstaller.Companion.toHex
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipDirectoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val payloads = linkedMapOf(
        "AndroidManifest.xml" to "<manifest/>".toByteArray(),
        "jni/arm64-v8a/libgojni.so" to Random(1).nextBytes(200_000),
        "jni/x86_64/libgojni.so" to Random(2).nextBytes(150_000),
        "R.txt" to ByteArray(0),
    )

    private fun archive(comment: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            comment?.let { zip.setComment(it) }
            payloads.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun tailOf(zip: ByteArray) = zip.copyOfRange(maxOf(0, zip.size - ZipDirectory.TAIL_SIZE), zip.size)

    private fun entries(zip: ByteArray): Pair<ZipDirectory.EndRecord, List<ZipDirectory.CentralEntry>> {
        val end = ZipDirectory.readEndRecord(tailOf(zip))
        val central = zip.copyOfRange(end.centralOffset.toInt(), (end.centralOffset + end.centralSize).toInt())
        return end to ZipDirectory.readCentralDirectory(central, end.entryCount)
    }

    @Test
    fun `central directory lists every entry`() {
        val (end, entries) = entries(archive())

        expectThat(end.entryCount).isEqualTo(4)
        expectThat(entries.map { it.name }).containsExactly(payloads.keys)
        expectThat(entries.first { it.name == "jni/x86_64/libgojni.so" }.uncompressedSize).isEqualTo(150_000)
    }

    @Test
    fun `end record is found behind an archive comment`() {
        val (end, entries) = entries(archive(comment = "PK\u0005\u0006 looks like a signature"))

        expectThat(end.entryCount).isEqualTo(4)
        expectThat(entries.map { it.name }).containsExactly(payloads.keys)
    }

    @Test
    fun `located slice inflates to the entry`() {
        val zip = archive()
        val (end, entries) = entries(zip)

        val name = "jni/arm64-v8a/libgojni.so"
        val range = ZipDirectory.locate(entries, end, name)
        expectThat(range).isNotNull()

        val slice = zip.copyOfRange(range!!.start.toInt(), range.endExclusive.toInt())
        val expected = MessageDigest.getInstance("SHA-256").digest(payloads.getValue(name)).toHex()
        val out = temp.newFile()

        EngineInstaller.extractEntry(slice.inputStream(), name, out, expected)

        expectThat(out.readBytes().contentEquals(payloads.getValue(name))).isEqualTo(true)
    }

    @Test
    fun `last entry runs up to the central directory`() {
        val zip = archive()
        val (end, entries) = entries(zip)

        val range = ZipDirectory.locate(entries, end, "R.txt")!!

        expectThat(range.endExclusive).isEqualTo(end.centralOffset)
    }

    @Test
    fun `whole archive stream also yields the entry`() {
        val name = "jni/x86_64/libgojni.so"
        val expected = MessageDigest.getInstance("SHA-256").digest(payloads.getValue(name)).toHex()
        val out = temp.newFile()

        EngineInstaller.extractEntry(archive().inputStream(), name, out, expected)

        expectThat(out.length()).isEqualTo(150_000)
    }

    @Test
    fun `checksum mismatch is rejected`() {
        val out = temp.newFile()

        expectThrows<SecurityException> {
            EngineInstaller.extractEntry(archive().inputStream(), "jni/x86_64/libgojni.so", out, "00".repeat(32))
        }
    }

    @Test
    fun `missing entry is not located`() {
        val (end, entries) = entries(archive())

        expectThat(ZipDirectory.locate(entries, end, "jni/mips/libgojni.so")).isNull()
    }

    @Test
    fun `garbage has no end record`() {
        expectThrows<ZipException> { ZipDirectory.readEndRecord(Random(3).nextBytes(1000)) }
    }
}
