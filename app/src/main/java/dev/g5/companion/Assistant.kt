package dev.g5.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.sqrt

data class Turn(val time: Long, val question: String, val answer: String, val imagePath: String? = null)

/** Routes phone audio I/O to the glasses' Bluetooth headset profile (mic + speaker). */
object BtAudio {
    const val SAMPLE_RATE = 16_000

    fun routeToGlasses(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        val dev = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: return false
        return am.setCommunicationDevice(dev)
    }

    fun release(ctx: Context) = ctx.getSystemService(AudioManager::class.java).clearCommunicationDevice()

    fun hasMicPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun newRecorder(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE),
        )
    }

    fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    /**
     * Record one utterance: stops after [SILENCE_MS] of quiet following speech, or at the max length.
     * ponytail: fixed RMS threshold; expose it as a setting if the glasses' mic gain differs between units.
     */
    fun recordUtterance(): ByteArray? {
        val rec = newRecorder()
        if (rec.state != AudioRecord.STATE_INITIALIZED) return null
        val out = ByteArrayOutputStream()
        val chunk = ShortArray(SAMPLE_RATE / 10)
        val bytes = ByteArray(chunk.size * 2)
        var heardSpeech = false
        var quietMs = 0
        var elapsedMs = 0
        var maxRms = 0.0
        rec.startRecording()
        try {
            while (elapsedMs < MAX_UTTERANCE_MS) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                for (i in 0 until n) { bytes[2 * i] = chunk[i].toByte(); bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte() }
                out.write(bytes, 0, n * 2)
                elapsedMs += n * 1000 / SAMPLE_RATE
                val currentRms = rms(chunk, n)
                if (currentRms > maxRms) maxRms = currentRms
                if (currentRms > SPEECH_RMS) { heardSpeech = true; quietMs = 0 } else quietMs += n * 1000 / SAMPLE_RATE
                if (heardSpeech && quietMs >= SILENCE_MS) break
                if (!heardSpeech && elapsedMs >= NO_SPEECH_TIMEOUT_MS) break
            }
        } finally {
            rec.stop()
            rec.release()
        }
        Log.i("BtAudio", "recordUtterance: heardSpeech=$heardSpeech, maxRms=${maxRms.toInt()}/$SPEECH_RMS, bytes=${out.size()}, elapsed=${elapsedMs}ms")
        return if (heardSpeech) wav(out.toByteArray(), SAMPLE_RATE) else null
    }

    fun wav(pcm: ByteArray, rate: Int): ByteArray = ByteArrayOutputStream().apply {
        fun le32(v: Int) { for (s in 0 until 4) write((v shr (8 * s)) and 0xFF) }
        fun le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
        write("RIFF".toByteArray()); le32(36 + pcm.size); write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        write("data".toByteArray()); le32(pcm.size); write(pcm)
    }.toByteArray()

    private const val SPEECH_RMS = 350.0
    private const val SILENCE_MS = 1_200
    private const val NO_SPEECH_TIMEOUT_MS = 6_000
    private const val MAX_UTTERANCE_MS = 15_000
}

object Assistant {
    private const val TAG = "Assistant"
    private const val MAX_HISTORY = 100
    private const val LOOK_QUESTION = "What am I looking at?"
    private const val SAY_WAIT_MAX_MS = 20_000L

    private val _history = MutableStateFlow<List<Turn>>(emptyList())
    val history = _history.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private val busy = AtomicBoolean(false)
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        _history.value = loadHistory()
        tts = TextToSpeech(app) { ok ->
            if (ok == TextToSpeech.SUCCESS) tts?.apply {
                language = Locale.getDefault()
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { id?.let { spoken.remove(it)?.invoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { id?.let { spoken.remove(it)?.invoke() } }
                })
            }
        }
    }

    private val spoken = ConcurrentHashMap<String, () -> Unit>()

    /** Speaks [text] and suspends until it has finished (so the mic doesn't hear it). */
    suspend fun sayAndWait(text: String) {
        withTimeoutOrNull(SAY_WAIT_MAX_MS) {
            suspendCancellableCoroutine { c ->
                val id = "g5-${System.nanoTime()}"
                spoken[id] = { if (c.isActive) c.resume(Unit) }
                c.invokeOnCancellation { spoken.remove(id) }
                if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                    spoken.remove(id)
                    c.resume(Unit)
                }
            }
        }
    }

    /** Speaks [prompt], records the answer from the glasses mic, and returns the transcript. */
    suspend fun askAndListen(prompt: String): String? {
        sayAndWait(prompt)
        val audio = withContext(Dispatchers.IO) { listen() } ?: return null
        return Speech.transcribe(app, audio).also { _status.value = null }
    }

    /** Physical AI button (notify 0x03) or UI test trigger. Routes via [GlassesCompanionService]. */
    fun onAiButton() {
        if (prefs.liveMode && prefs.provider == Provider.GEMINI) { GeminiLive.toggle(app); return }
        GlassesCompanionService.start(app)
        Glasses.triggerAiButton()
    }

    /** Typed question from the app, optionally with a fresh POV photo. */
    fun askTyped(text: String, withPhoto: Boolean) {
        if (text.isBlank() || !busy.compareAndSet(false, true)) return
        Glasses.scope.launch {
            try {
                ask(audio = null, image = if (withPhoto) capturePov() else null, typed = text.trim())
            } finally {
                busy.set(false)
            }
        }
    }

    suspend fun capturePov(): ByteArray? {
        if (!Glasses.state.value.connected) return null
        _status.value = "Capturing photo…"
        return Glasses.capturePhoto()
    }

    private fun listen(): ByteArray? {
        if (!BtAudio.hasMicPermission(app)) return null
        _status.value = "Listening…"
        val routed = BtAudio.routeToGlasses(app)
        Log.i(TAG, "mic routed to glasses: $routed")
        return try { BtAudio.recordUtterance() } finally { BtAudio.release(app) }
    }

    suspend fun ask(audio: ByteArray?, image: ByteArray?, typed: String?) {
        if (audio == null && typed == null && image == null) {
            Log.i(TAG, "nothing to ask: no speech heard and no photo")
            say("I didn't hear a question.")
            return
        }
        val provider = prefs.provider
        // Gemini hears the audio itself; everyone else gets an on-device transcript.
        val transcript = if (audio != null && provider != Provider.GEMINI) {
            _status.value = "Transcribing…"
            Speech.transcribe(app, audio) ?: run { say("Sorry, I didn't catch that."); return }
        } else null
        val question = typed ?: transcript ?: if (audio != null) "Answer the question in the audio." else LOOK_QUESTION

        // Fast-path: detect direct action verbs on-device (zero latency, offline)
        val textPrompt = typed ?: transcript
        if (textPrompt != null && HandsFree.handle(app, textPrompt)) return
        if (textPrompt != null) {
            val directAction = ActionDispatcher.parse(textPrompt, prefs.customVerbs())
            if (directAction != null) {
                _status.value = "Executing action…"
                val res = withContext(Dispatchers.Main) {
                    ActionDispatcher.execute(app, directAction, image)
                }
                addTurn(textPrompt, res.turnAnswer, image)
                say(res.spokenFeedback)
                return
            }
        }

        _status.value = "Asking ${provider.label}…"
        val system = prefs.systemPrompt + "\n\n" + situation()
        val result = withContext(Dispatchers.IO) {
            runCatching { Llm.ask(prefs, system, question, image, audio.takeIf { provider == Provider.GEMINI }) }
        }
        val reply = result.getOrElse { e ->
            Log.e(TAG, "${provider.label} request failed", e)
            say("Sorry, ${provider.label} failed: ${e.message ?: "unknown error"}")
            return
        }
        Log.i(TAG, "${provider.label}: q=${question.take(80)} heard=${reply.heard?.take(80)} answer=${reply.answer.length} chars")
        val heard = typed ?: transcript ?: reply.heard ?: if (audio != null) "(voice)" else LOOK_QUESTION

        // Check for LLM-emitted ACTION: directive
        val directive = ActionDispatcher.parseActionDirective(reply.answer, prefs.customVerbs())
        val (finalAnswer, speechText) = if (directive != null) {
            val (action, remainingText) = directive
            val res = withContext(Dispatchers.Main) {
                ActionDispatcher.execute(app, action, image)
            }
            val display = if (remainingText.isNotBlank()) remainingText else res.turnAnswer
            val speech = if (remainingText.isNotBlank()) remainingText else res.spokenFeedback
            display to speech
        } else {
            val ans = reply.answer.ifBlank { "I don't have an answer for that." }
            ans to ans
        }

        addTurn(heard, finalAnswer, image)
        say(speechText)
    }

    /** Date, time and (if the location permission is granted) approximate place, for grounding answers. */
    suspend fun situation(): String {
        val now = ZonedDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()))
        val place = withContext(Dispatchers.IO) { runCatching { approximatePlace() }.getOrNull() }
        return "Current local time: $time (${now.zone.id})." + (place?.let { " The wearer is near $it." } ?: "")
    }

    @SuppressLint("MissingPermission")
    private suspend fun approximatePlace(): String? {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = app.getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000 }
            ?: withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Location?> { c ->
                    val provider = if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
                    lm.getCurrentLocation(provider, null, app.mainExecutor) { if (c.isActive) c.resume(it) }
                }
            }
            ?: return null
        val coords = "%.3f, %.3f".format(Locale.US, loc.latitude, loc.longitude)
        val name = withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine<String?> { c ->
                Geocoder(app, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<Address>) {
                        val a = list.firstOrNull()
                        if (c.isActive) c.resume(a?.let { listOfNotNull(it.subLocality ?: it.locality, it.adminArea, it.countryName).distinct().joinToString(", ") })
                    }
                    override fun onError(errorMessage: String?) { if (c.isActive) c.resume(null) }
                })
            }
        }
        return if (name.isNullOrBlank()) coords else "$name ($coords)"
    }

    /** [flush] = interrupt whatever is playing; false queues behind it (notifications). */
    fun say(text: String, flush: Boolean = true) {
        if (flush) _status.value = null
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "g5-${System.nanoTime()}")
    }

    fun addTurn(question: String, answer: String, image: ByteArray? = null) {
        val path = image?.let { bytes ->
            File(app.filesDir, "pov").apply { mkdirs() }.resolve("${System.currentTimeMillis()}.jpg")
                .also { it.writeBytes(bytes) }.absolutePath
        }
        val updated = (listOf(Turn(System.currentTimeMillis(), question, answer, path)) + _history.value).take(MAX_HISTORY)
        _history.value = updated
        saveHistory(updated)
    }

    fun clearHistory() {
        _history.value = emptyList()
        File(app.filesDir, "pov").deleteRecursively()
        saveHistory(emptyList())
    }

    fun setStatus(s: String?) = _status.update { s }

    private fun historyFile() = File(app.filesDir, "history.json")

    private fun saveHistory(list: List<Turn>) {
        val arr = JSONArray()
        list.forEach { t -> arr.put(JSONObject().put("t", t.time).put("q", t.question).put("a", t.answer).put("img", t.imagePath)) }
        historyFile().writeText(arr.toString())
    }

    private fun loadHistory(): List<Turn> = runCatching {
        val arr = JSONArray(historyFile().readText())
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Turn(it.getLong("t"), it.getString("q"), it.getString("a"), it.optString("img").ifBlank { null }) }
        }
    }.getOrDefault(emptyList())

    fun isRunning() = busy.get()
}
