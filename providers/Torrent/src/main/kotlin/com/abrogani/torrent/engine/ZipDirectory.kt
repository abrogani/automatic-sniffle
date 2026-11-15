package com.abrogani.torrent.engine

import java.util.zip.ZipException

/**
 * Just enough of the ZIP format to find one entry's byte range from the archive's tail, so the installer
 * can range-fetch a single ABI's library instead of the whole AAR. ZIP64 isn't supported.
 */
internal object ZipDirectory {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val EOCD_SIZE = 22
    private const val CENTRAL_HEADER_SIZE = 46
    private const val MAX_COMMENT_SIZE = 0xFFFF

    /** Bytes to fetch from the end of an archive to be sure the end-of-central-directory record is included. */
    const val TAIL_SIZE = EOCD_SIZE + MAX_COMMENT_SIZE

    data class EndRecord(val centralOffset: Long, val centralSize: Long, val entryCount: Int)

    data class CentralEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    /** Where an entry's local header and data live: `[start, endExclusive)` in archive offsets. */
    data class EntryRange(val entry: CentralEntry, val start: Long, val endExclusive: Long) {
        val length get() = endExclusive - start
    }

    fun readEndRecord(tail: ByteArray): EndRecord {
        var i = tail.size - EOCD_SIZE
        while (i >= 0) {
            if (tail.u32(i) == EOCD_SIGNATURE && i + EOCD_SIZE + tail.u16(i + 20) == tail.size) {
                val count = tail.u16(i + 10)
                val size = tail.u32(i + 12)
                val offset = tail.u32(i + 16)
                if (count == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
                    throw ZipException("ZIP64 archives aren't supported")
                }
                return EndRecord(centralOffset = offset, centralSize = size, entryCount = count)
            }
            i--
        }
        throw ZipException("End of central directory not found")
    }

    fun readCentralDirectory(bytes: ByteArray, expectedCount: Int): List<CentralEntry> {
        val entries = ArrayList<CentralEntry>(expectedCount)
        var i = 0
        repeat(expectedCount) {
            if (i + CENTRAL_HEADER_SIZE > bytes.size || bytes.u32(i) != CENTRAL_SIGNATURE) {
                throw ZipException("Malformed central directory at $i")
            }
            val nameLength = bytes.u16(i + 28)
            val extraLength = bytes.u16(i + 30)
            val commentLength = bytes.u16(i + 32)
            val nameEnd = i + CENTRAL_HEADER_SIZE + nameLength
            if (nameEnd > bytes.size) throw ZipException("Truncated central directory")

            entries += CentralEntry(
                name = String(bytes, i + CENTRAL_HEADER_SIZE, nameLength, Charsets.UTF_8),
                method = bytes.u16(i + 10),
                compressedSize = bytes.u32(i + 20),
                uncompressedSize = bytes.u32(i + 24),
                localHeaderOffset = bytes.u32(i + 42),
            )
            i = nameEnd + extraLength + commentLength
        }
        return entries
    }

    /**
     * The range from [name]'s local header up to the next entry (or the central directory), which covers
     * the local header, its extra field and any data descriptor without having to read the local header first.
     */
    fun locate(entries: List<CentralEntry>, end: EndRecord, name: String): EntryRange? {
        val entry = entries.firstOrNull { it.name == name } ?: return null
        val next = entries.asSequence()
            .map { it.localHeaderOffset }
            .filter { it > entry.localHeaderOffset }
            .minOrNull()
            ?: end.centralOffset

        if (next <= entry.localHeaderOffset || next - entry.localHeaderOffset < entry.compressedSize) {
            throw ZipException("Inconsistent offsets for $name")
        }
        return EntryRange(entry, entry.localHeaderOffset, next)
    }

    private fun ByteArray.u16(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int): Long =
        (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
}
