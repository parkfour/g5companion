package dev.g5.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OggOpusTest {
    // TOC 0x08: config 1 (SILK NB 20 ms), mono, 1 frame -> 960 samples per packet.
    private fun packet(size: Int, toc: Int = 0x08) = ByteArray(size) { if (it == 0) toc.toByte() else (it * 7).toByte() }

    @Test
    fun `wraps fixed 40-byte packets into valid ogg pages`() {
        val raw = (0 until 100).fold(ByteArray(0)) { acc, _ -> acc + packet(40) }
        val ogg = requireNotNull(OggOpus.wrap(raw))

        val pages = parsePages(ogg)
        assertEquals("OpusHead", String(pages[0].body, 0, 8))
        assertEquals("OpusTags", String(pages[1].body, 0, 8))
        assertEquals(100 * 960L, pages.last().granule)
        assertTrue(pages.last().type and 0x04 != 0)
        // Audio payload round-trips byte for byte.
        assertArrayEquals(raw, pages.drop(2).fold(ByteArray(0)) { acc, p -> acc + p.body })
        // Every page CRC verifies.
        pages.forEach { p ->
            val copy = p.raw.copyOf().also { for (i in 22..25) it[i] = 0 }
            assertEquals(p.crc, OggOpus.crc(copy))
        }
    }

    @Test
    fun `existing ogg is returned untouched and garbage is rejected`() {
        val ogg = "OggS".toByteArray() + ByteArray(30)
        assertArrayEquals(ogg, OggOpus.wrap(ogg))
        assertNull(OggOpus.wrap(ByteArray(37) { it.toByte() }))
    }

    @Test
    fun `toc duration covers CELT and multi-frame packets`() {
        assertEquals(960, OggOpus.samplesIn(byteArrayOf(0xF8.toByte()))) // CELT FB 20 ms
        assertEquals(1920, OggOpus.samplesIn(byteArrayOf(0x09))) // SILK 20 ms, 2 frames
        assertEquals(3 * 480, OggOpus.samplesIn(byteArrayOf(0x03, 0x03))) // SILK 10 ms, code 3, 3 frames
    }

    private class Page(val raw: ByteArray, val type: Int, val granule: Long, val crc: Int, val body: ByteArray)

    private fun parsePages(b: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var i = 0
        while (i < b.size) {
            assertEquals("OggS", String(b, i, 4))
            val type = b[i + 5].toInt()
            var granule = 0L
            for (s in 0 until 8) granule = granule or ((b[i + 6 + s].toLong() and 0xFF) shl (8 * s))
            var crc = 0
            for (s in 0 until 4) crc = crc or ((b[i + 22 + s].toInt() and 0xFF) shl (8 * s))
            val nSeg = b[i + 26].toInt() and 0xFF
            val bodyLen = (0 until nSeg).sumOf { b[i + 27 + it].toInt() and 0xFF }
            val headerLen = 27 + nSeg
            out += Page(b.copyOfRange(i, i + headerLen + bodyLen), type, granule, crc,
                b.copyOfRange(i + headerLen, i + headerLen + bodyLen))
            i += headerLen + bodyLen
        }
        return out
    }
}
