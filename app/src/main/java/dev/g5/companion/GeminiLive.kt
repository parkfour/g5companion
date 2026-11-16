package dev.g5.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Gemini Live (BidiGenerateContent) over a raw WebSocket: glasses mic -> 16 kHz PCM up, 24 kHz PCM down to the
 * glasses speaker, with opportunistic POV snapshot capture and trailing audio deferral on speech turns,
 * plus context window compression.
 */
object GeminiLive {
    private const val TAG = "GeminiLive"
    private const val URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val OUTPUT_RATE = 24_000
    private const val PERIODIC_SNAPSHOT_INTERVAL_MS = 15_000L
    private const val MIN_STILL_COOLDOWN_MS = 6_000L
    private const val SPEECH_MEAN_THRESHOLD = 450
    // ponytail: BLE POV capture runs a few seconds; hold trailing silence that long so server VAD
    // doesn't close the turn before the image lands. Cap bounds the stall if capture hangs.
    private const val MAX_HELD_SILENCE_CHUNKS = 40 // 40 x 100ms = 4s max hold

    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var micJob: Job? = null
    private var snapJob: Job? = null
    private var player: AudioTrack? = null
    private val heard = StringBuilder()
    private val said = StringBuilder()

    // Audio deferral and opportunistic capture state
    private val audioLock = Any()
    private val delayedAudio = ArrayDeque<ByteArray>()
    @Volatile private var deferAudioForVision = false
    @Volatile private var captureInProgress = false
    @Volatile private var userSpeaking = false
    @Volatile private var lastStillCaptureMs = 0L

    fun toggle(ctx: Context) = if (_active.value) stop(ctx) else start(ctx)

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val key = prefs.apiKey
        if (key.isNullOrBlank()) { Assistant.say("Add your Gemini API key in settings first."); return }
        if (!BtAudio.hasMicPermission(app)) { Assistant.setStatus("Microphone permission needed for Live mode"); return }
        if (_active.value) return
        _active.value = true
        clearDelayedAudio()
        captureInProgress = false
        deferAudioForVision = false
        userSpeaking = false
        lastStillCaptureMs = 0L
        Assistant.setStatus("Live: connecting…")
        BtAudio.routeToGlasses(app)
        player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUTPUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(OUTPUT_RATE * 2)
            .build().also { it.play() }

        val request = Request.Builder().url("$URL?key=$key").build()
        Glasses.scope.launch {
            val system = prefs.systemPrompt + "\n\n" + Assistant.situation()
            if (_active.value) socket = http.newWebSocket(request, Listener(app, system))
        }
    }

    fun stop(ctx: Context) {
        if (!_active.value) return
        _active.value = false
        micJob?.cancel(); snapJob?.cancel()
        socket?.close(1000, "bye"); socket = null
        player?.run { runCatching { stop() }; release() }; player = null
        clearDelayedAudio()
        captureInProgress = false
        deferAudioForVision = false
        userSpeaking = false
        BtAudio.release(ctx.applicationContext)
        flushTurn()
        Assistant.setStatus(null)
    }

    private fun clearDelayedAudio() {
        synchronized(audioLock) {
            delayedAudio.clear()
        }
    }

    private fun flushDelayedAudio(ws: WebSocket) {
        synchronized(audioLock) {
            while (delayedAudio.isNotEmpty()) {
                val chunk = delayedAudio.removeFirst()
                ws.send(realtime("audio", Base64.encodeToString(chunk, 0, chunk.size, Base64.NO_WRAP), "audio/pcm;rate=${BtAudio.SAMPLE_RATE}"))
            }
        }
    }

    private fun realtime(kind: String, b64: String, mime: String) =
        JSONObject().put("realtimeInput", JSONObject().put(kind, JSONObject().put("data", b64).put("mimeType", mime))).toString()

    private class Listener(private val app: Context, private val systemPrompt: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setup = JSONObject().put(
                "setup",
                JSONObject()
                    .put("model", "models/$MODEL_LIVE")
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
                    .put(
                        "contextWindowCompression",
                        JSONObject()
                            .put("triggerTokens", 25000)
                            .put("slidingWindow", JSONObject().put("targetTokens", 8000)),
                    )
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject()),
            )
            webSocket.send(setup.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Live socket failed (${response?.code})", t)
            Assistant.say("Live session ended: ${t.message ?: "connection error"}")
            stop(app)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closed $code $reason")
            if (_active.value) stop(app)
        }

        private fun handle(ws: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (msg.has("setupComplete")) { onReady(ws); return }
            val content = msg.optJSONObject("serverContent") ?: return
            if (content.optBoolean("interrupted")) {
                player?.flush()
                clearDelayedAudio()
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.let { heard.append(it) }
            content.optJSONObject("outputTranscription")?.optString("text")?.let { said.append(it) }
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                    val pcm = Base64.decode(data, Base64.DEFAULT)
                    player?.write(pcm, 0, pcm.size)
                }
            }
            if (content.optBoolean("turnComplete")) flushTurn()
        }

        private fun onReady(ws: WebSocket) {
            Assistant.setStatus("Live: listening")
            lastStillCaptureMs = System.currentTimeMillis()

            micJob = Glasses.scope.launch(Dispatchers.IO) {
                val rec = BtAudio.newRecorder()
                val buf = ByteArray(BtAudio.SAMPLE_RATE / 10 * 2) // 100 ms chunks
                rec.startRecording()
                try {
                    while (isActive) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        processAudioChunk(ws, buf, n)
                    }
                } finally {
                    rec.stop(); rec.release()
                }
            }

            // Fallback periodic refresh if user is idle for a while
            snapJob = Glasses.scope.launch {
                while (isActive) {
                    delay(PERIODIC_SNAPSHOT_INTERVAL_MS)
                    if (isActive && !captureInProgress && System.currentTimeMillis() - lastStillCaptureMs >= PERIODIC_SNAPSHOT_INTERVAL_MS) {
                        captureAndSendSnapshot(ws)
                    }
                }
            }
        }

        private fun processAudioChunk(ws: WebSocket, buf: ByteArray, n: Int) {
            val speech = isSpeech(buf, n)
            val now = System.currentTimeMillis()

            if (speech) {
                if (!userSpeaking) {
                    userSpeaking = true
                    if (!captureInProgress && now - lastStillCaptureMs >= MIN_STILL_COOLDOWN_MS) {
                        triggerOpportunisticCapture(ws)
                    }
                }
                // When speaking, stream out queued chunks and current audio immediately
                flushDelayedAudio(ws)
                ws.send(realtime("audio", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP), "audio/pcm;rate=${BtAudio.SAMPLE_RATE}"))
            } else {
                if (userSpeaking) {
                    userSpeaking = false
                }
                synchronized(audioLock) {
                    if (deferAudioForVision && captureInProgress) {
                        // Hold silence chunk so server VAD does not close the turn before POV image is sent
                        if (delayedAudio.size < MAX_HELD_SILENCE_CHUNKS) {
                            delayedAudio.addLast(buf.copyOf(n))
                        } else {
                            // Reached safety limit (~1.5s): flush to prevent stalling
                            flushDelayedAudio(ws)
                            deferAudioForVision = false
                        }
                    } else {
                        flushDelayedAudio(ws)
                        ws.send(realtime("audio", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP), "audio/pcm;rate=${BtAudio.SAMPLE_RATE}"))
                    }
                }
            }
        }

        private fun triggerOpportunisticCapture(ws: WebSocket) {
            if (captureInProgress) return
            captureInProgress = true
            deferAudioForVision = true
            lastStillCaptureMs = System.currentTimeMillis()
            Glasses.scope.launch {
                try {
                    val jpg = Assistant.capturePov()
                    if (jpg != null && _active.value) {
                        ws.send(realtime("video", Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg"))
                        Assistant.setStatus("Live: vision updated")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Opportunistic POV capture error", e)
                } finally {
                    captureInProgress = false
                    flushDelayedAudio(ws)
                    deferAudioForVision = false
                }
            }
        }

        private suspend fun captureAndSendSnapshot(ws: WebSocket) {
            if (captureInProgress) return
            captureInProgress = true
            lastStillCaptureMs = System.currentTimeMillis()
            try {
                val jpg = Assistant.capturePov()
                if (jpg != null && _active.value) {
                    ws.send(realtime("video", Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Periodic POV snapshot failed", e)
            } finally {
                captureInProgress = false
            }
        }

        private fun isSpeech(buf: ByteArray, n: Int) = GeminiLive.isSpeech(buf, n)
    }

    /** True if the mean absolute amplitude of the little-endian PCM16 chunk clears the speech threshold. */
    internal fun isSpeech(buf: ByteArray, n: Int): Boolean {
        var sum = 0L
        var samples = 0
        var i = 0
        val len = n.coerceAtMost(buf.size)
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += abs(sample).toLong()
            samples++
            i += 2
        }
        if (samples == 0) return false
        return (sum / samples) >= SPEECH_MEAN_THRESHOLD
    }

    private fun flushTurn() {
        if (said.isBlank()) return
        Assistant.addTurn(heard.toString().trim().ifBlank { "(live)" }, said.toString().trim())
        heard.clear(); said.clear()
    }
}
