package dev.g5.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EyevueTest {
    @Test
    fun `frames match the vendor wire format`() {
        // AB 55, len = payload + 2, cmd, payload, crc = cmd + payload
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x17, 0x00, 0x17), Eyevue.battery())
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x02, 0x64, 0x64), Eyevue.customer())
        assertEquals(0x22 + 0x31, Eyevue.photo().last().toInt() and 0xFF)
    }

    @Test
    fun `decoder reassembles split frames and drops bad crc`() {
        val d = Eyevue.Decoder()
        val reply = Eyevue.frame(Eyevue.GET_BATTERY, 0x08, 0x05, 0x01).also { it[0] = 0xAC.toByte() } // glasses->app SOF
        val bad = Eyevue.frame(Eyevue.GET_MEDIA_COUNT, 1, 2).also { it[it.size - 1] = 0 }
        val stream = byteArrayOf(0x00) + reply + bad + Eyevue.frame(Eyevue.THUMB_COUNT, 0x01, 0x02)
        assertTrue(d.append(stream.copyOfRange(0, 5)).isEmpty())
        val frames = d.append(stream.copyOfRange(5, stream.size))
        assertEquals(listOf(Eyevue.GET_BATTERY, Eyevue.THUMB_COUNT), frames.map { it.cmd })
        assertEquals(85 to true, Eyevue.parseBattery(frames[0]))
        assertEquals(258, Eyevue.u16(frames[1]))
    }

    @Test
    fun `photo assembler strips chunk address and trailer`() {
        val a = Eyevue.PhotoAssembler()
        fun pkt(cmd: Int, body: ByteArray = ByteArray(0)) =
            byteArrayOf(0xAB.toByte(), 0x55, 0, 0, cmd.toByte(), 0, 0, 0, 0) + body + byteArrayOf(0, 0, 0)
        a.append(pkt(Eyevue.STREAM_START))
        a.append(pkt(Eyevue.STREAM_DATA, byteArrayOf(1, 2, 3)))
        a.append(pkt(Eyevue.STREAM_DATA, byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), a.append(pkt(Eyevue.STREAM_END)))
    }
}
