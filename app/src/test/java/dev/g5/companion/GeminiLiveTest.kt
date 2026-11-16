package dev.g5.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Covers the little-endian PCM16 VAD used to gate opportunistic POV capture in Live mode. */
class GeminiLiveTest {

    /** Writes [sample] as a little-endian signed 16-bit PCM frame repeated [frames] times. */
    private fun pcm16(sample: Int, frames: Int): ByteArray {
        val buf = ByteArray(frames * 2)
        for (i in 0 until frames) {
            buf[i * 2] = (sample and 0xff).toByte()
            buf[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return buf
    }

    @Test
    fun `pure silence is not speech`() {
        val buf = pcm16(0, 100)
        assertFalse(GeminiLive.isSpeech(buf, buf.size))
    }

    @Test
    fun `low-amplitude noise below threshold is not speech`() {
        // mean |amp| = 100, well under the 450 threshold
        val buf = pcm16(100, 100)
        assertFalse(GeminiLive.isSpeech(buf, buf.size))
    }

    @Test
    fun `loud tone above threshold is speech`() {
        val buf = pcm16(8000, 100)
        assertTrue(GeminiLive.isSpeech(buf, buf.size))
    }

    @Test
    fun `negative samples count by magnitude not sign`() {
        // A loud negative-valued frame must read as speech (verifies sign handling).
        val buf = pcm16(-8000, 100)
        assertTrue(GeminiLive.isSpeech(buf, buf.size))
    }

    @Test
    fun `sine wave at speech volume is detected`() {
        val frames = 320
        val buf = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val s = (sin(i * 0.3) * 6000).toInt()
            buf[i * 2] = (s and 0xff).toByte()
            buf[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        assertTrue(GeminiLive.isSpeech(buf, buf.size))
    }

    @Test
    fun `respects n bound and ignores trailing bytes`() {
        // Loud frames followed by a stale/garbage tail; only the first 2 loud frames count.
        val loud = pcm16(9000, 2)
        val padded = loud + ByteArray(50) // zeros past n
        assertTrue(GeminiLive.isSpeech(padded, loud.size))
    }

    @Test
    fun `empty chunk is not speech`() {
        assertFalse(GeminiLive.isSpeech(ByteArray(0), 0))
    }
}
