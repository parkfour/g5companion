package dev.g5.companion

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.UUID

/**
 * DPVR G5 BLE protocol (Eyevue platform, JieLi SoC). Ported from CyanBridge's EyevueProtocol.
 * Frame: [AB 55 (to glasses) / AC 55 (from glasses) len_hi len_lo cmd payload… crc], len = payload + 2, crc = (cmd + Σpayload) & 0xFF.
 * Commands go to AA13 (write); replies and events arrive on AA14; AI-photo JPEG chunks on AA15.
 */
object Eyevue {
    val SERVICE: UUID = UUID.fromString("0000aa12-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000aa14-0000-1000-8000-00805f9b34fb")
    val PHOTO: UUID = UUID.fromString("0000aa15-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val RECORD_TIME = 2
    const val GET_BATTERY = 23
    const val TAKE_PHOTO = 34
    const val RECORD_VIDEO = 35
    const val STOP_RECORD = 36
    const val WIFI_SSID = 37
    const val RECORD_AUDIO = 52
    const val GET_WIFI_INFO = 57
    const val GET_MEDIA_COUNT = 64 // reply 64 or 66, u16 count of files not yet pulled
    const val THUMB_COUNT = 66
    const val FILE_DOWNLOAD_FINISH = 68
    const val DEVICE_STATUS = 69 // 9 flags: photo, audio, video, vol+, vol-, nod, shake, music, worn
    const val VOICE_DATA = 70
    const val BATTERY_EVENT = 83
    const val STOP_VOICE = 86
    const val DEVICE_INFO = 85 // reply: bt x.y.z, isp x.y.z, hardware rev
    const val SET_TIME = 89
    const val CUSTOMER = 100
    const val GET_VOICE_STATUS = 113
    const val SET_VOICE_STATUS = 114
    const val STREAM_START = 151 // AI wake word / button started a voice stream (also AA15 photo start)
    const val STREAM_DATA = 152
    const val STREAM_END = 153

    class Frame(val cmd: Int, val payload: ByteArray) {
        fun u(i: Int) = payload.getOrNull(i)?.toInt()?.and(0xFF)
        override fun toString() = "cmd=$cmd ${payload.hex()}"
    }

    fun frame(cmd: Int, vararg payload: Int): ByteArray {
        val out = ByteArray(6 + payload.size)
        out[0] = 0xAB.toByte(); out[1] = 0x55
        out[2] = ((payload.size + 2) shr 8).toByte(); out[3] = (payload.size + 2).toByte()
        out[4] = cmd.toByte()
        var crc = cmd
        payload.forEachIndexed { i, b -> out[5 + i] = b.toByte(); crc += b and 0xFF }
        out[out.size - 1] = crc.toByte()
        return out
    }

    fun battery() = frame(GET_BATTERY, 0)
    fun mediaCount() = frame(GET_MEDIA_COUNT, 0)
    fun photo() = frame(TAKE_PHOTO, 0x31) // 0x31 = high quality; the only mode verified to stream AA15
    fun video(start: Boolean) = if (start) frame(RECORD_VIDEO, 1) else frame(STOP_RECORD, 0)
    fun audio(start: Boolean) = frame(RECORD_AUDIO, if (start) 1 else 0)
    fun stopVoice() = frame(STOP_VOICE, 0)
    fun customer() = frame(CUSTOMER)
    fun deviceInfo() = frame(DEVICE_INFO, 0)
    fun voiceStatus() = frame(GET_VOICE_STATUS, 0)
    fun recordSeconds(s: Int) = frame(RECORD_TIME, s shr 8, s)

    fun setTime(c: Calendar = Calendar.getInstance()) = frame(
        SET_TIME, c.get(Calendar.YEAR) - 2000, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
    )

    /** Battery from either the polled reply (BCD-ish digits) or the unsolicited event. */
    fun parseBattery(f: Frame): Pair<Int, Boolean>? = when (f.cmd) {
        GET_BATTERY -> if (f.payload.size < 2) null else
            ((f.payload[0].toInt() and 0x0F) * 10 + (f.payload[1].toInt() and 0x0F)) to (f.u(2) == 1)
        BATTERY_EVENT -> if (f.payload.size < 2) null else (f.u(1) ?: 0) to (f.u(0) == 1)
        else -> null
    }

    fun u16(f: Frame): Int? = if (f.payload.size < 2) null else (f.u(0)!! shl 8) or f.u(1)!!

    /** Reassembles frames split across ATT notifications. */
    class Decoder {
        private val buf = ByteArrayOutputStream()

        fun append(chunk: ByteArray): List<Frame> {
            buf.write(chunk)
            val b = buf.toByteArray()
            val out = ArrayList<Frame>()
            var i = 0
            while (true) {
                // Device->app frames start AC 55 (vendor SOF_BLE_APP); app->device AB 55. Accept both.
                while (i + 1 < b.size && !((b[i] == 0xAC.toByte() || b[i] == 0xAB.toByte()) && b[i + 1] == 0x55.toByte())) i++
                if (b.size - i < 4) break
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                if (len < 2) { i++; continue }
                if (b.size - i < len + 4) break
                val cmd = b[i + 4].toInt() and 0xFF
                val payload = b.copyOfRange(i + 5, i + len + 3)
                if ((cmd + payload.sumOf { it.toInt() and 0xFF }) and 0xFF == (b[i + len + 3].toInt() and 0xFF)) {
                    out += Frame(cmd, payload)
                }
                i += len + 4
            }
            buf.reset()
            if (i < b.size) buf.write(b, i, b.size - i)
            return out
        }

        fun reset() = buf.reset()
    }

    /** AA15 photo stream: START, DATA (4-byte address then JPEG bytes, 2-byte trailer), END. */
    class PhotoAssembler {
        private val buf = ByteArrayOutputStream()
        private var receiving = false

        fun append(p: ByteArray): ByteArray? {
            if (p.size < 8) return null
            return when (p[4].toInt() and 0xFF) {
                STREAM_START -> { buf.reset(); receiving = true; null }
                STREAM_DATA -> { if (receiving && p.size - 3 > 9) buf.write(p, 9, p.size - 3 - 9); null }
                STREAM_END -> if (!receiving) null else {
                    receiving = false
                    buf.toByteArray().takeIf { it.isNotEmpty() }.also { buf.reset() }
                }
                else -> null
            }
        }
    }
}

fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
