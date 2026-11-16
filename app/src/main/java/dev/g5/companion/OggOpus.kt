package dev.g5.companion

import java.io.ByteArrayOutputStream

/**
 * The glasses store `.opus` recordings as raw Opus packets with no container (the official app decodes them
 * with `hasHead=false, packetSize=40`). This wraps them into a standard Ogg/Opus stream (RFC 7845) so any
 * Android player can open them.
 */
object OggOpus {
    private const val SAMPLE_RATE = 48_000
    private val CANDIDATE_PACKET_SIZES = intArrayOf(40, 60, 80, 100, 120, 160, 200, 240, 320)

    /** Returns playable Ogg/Opus bytes, or null if the layout is not recognized. */
    fun wrap(raw: ByteArray, inputSampleRate: Int = 16_000): ByteArray? {
        if (isOgg(raw)) return raw
        val packets = splitPackets(raw) ?: return null
        return encode(packets, inputSampleRate)
    }

    fun isOgg(b: ByteArray) = b.size >= 4 && b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() &&
        b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()

    /** Fixed-size packets first (what the firmware writes), then u16-LE length-prefixed as a fallback. */
    internal fun splitPackets(raw: ByteArray): List<ByteArray>? {
        for (size in CANDIDATE_PACKET_SIZES) {
            if (raw.size % size != 0 || raw.size / size < 3) continue
            val packets = (0 until raw.size / size).map { raw.copyOfRange(it * size, (it + 1) * size) }
            if (consistentToc(packets)) return packets
        }
        return lengthPrefixed(raw)?.takeIf(::consistentToc)
    }

    private fun lengthPrefixed(raw: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 2 <= raw.size) {
            val len = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            if (len !in 1..1500 || i + len > raw.size) return null
            out += raw.copyOfRange(i, i + len)
            i += len
        }
        return if (i == raw.size && out.size >= 3) out else null
    }

    /** An encoder at fixed settings emits the same TOC config/stereo bits in every packet. */
    private fun consistentToc(packets: List<ByteArray>): Boolean {
        val toc = packets.first()[0].toInt() and 0xFC
        return packets.all { (it[0].toInt() and 0xFC) == toc && samplesIn(it) > 0 }
    }

    /** Samples (at 48 kHz) in one Opus packet, from its TOC byte (RFC 6716 §3.1). */
    internal fun samplesIn(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
            config < 16 -> intArrayOf(480, 960)[config % 2]
            else -> intArrayOf(120, 240, 480, 960)[config % 4]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0
        }
        return frameSamples * frames
    }

    internal fun encode(packets: List<ByteArray>, inputSampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val serial = (System.nanoTime() and 0x7FFFFFFF).toInt()
        val channels = if ((packets.first()[0].toInt() and 0x04) != 0) 2 else 1
        var seq = 0
        writePage(out, serial, seq++, 0, 0x02, listOf(opusHead(channels, inputSampleRate)))
        writePage(out, serial, seq++, 0, 0x00, listOf(opusTags()))

        var granule = 0L
        var i = 0
        while (i < packets.size) {
            val page = ArrayList<ByteArray>()
            var segments = 0
            while (i < packets.size) {
                val need = packets[i].size / 255 + 1
                if (segments + need > 255) break
                page += packets[i]
                granule += samplesIn(packets[i])
                segments += need
                i++
            }
            writePage(out, serial, seq++, granule, if (i >= packets.size) 0x04 else 0x00, page)
        }
        return out.toByteArray()
    }

    private fun opusHead(channels: Int, inputRate: Int) = ByteArrayOutputStream().apply {
        write("OpusHead".toByteArray())
        write(1)
        write(channels)
        le16(0) // pre-skip
        le32(inputRate)
        le16(0) // output gain
        write(0) // mapping family
    }.toByteArray()

    private fun opusTags() = ByteArrayOutputStream().apply {
        val vendor = "G5Companion".toByteArray()
        write("OpusTags".toByteArray())
        le32(vendor.size)
        write(vendor)
        le32(0)
    }.toByteArray()

    private fun writePage(out: ByteArrayOutputStream, serial: Int, seq: Int, granule: Long, type: Int, packets: List<ByteArray>) {
        val lacing = ByteArrayOutputStream()
        packets.forEach { p ->
            repeat(p.size / 255) { lacing.write(255) }
            lacing.write(p.size % 255)
        }
        val page = ByteArrayOutputStream().apply {
            write("OggS".toByteArray())
            write(0)
            write(type)
            for (s in 0 until 8) write(((granule shr (8 * s)) and 0xFF).toInt())
            le32(serial)
            le32(seq)
            le32(0) // CRC placeholder
            write(lacing.size())
            write(lacing.toByteArray())
            packets.forEach { write(it) }
        }.toByteArray()
        val crc = crc(page)
        for (s in 0 until 4) page[22 + s] = (crc ushr (8 * s)).toByte()
        out.write(page)
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    internal fun crc(data: ByteArray): Int {
        var c = 0
        for (b in data) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Int) { for (s in 0 until 4) write((v shr (8 * s)) and 0xFF) }
}
