package dev.g5.companion

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Transcribes speech recorded from the glasses' mic using the phone's on-device
 * or system speech recognizer, with Whisper cloud fallback if configured.
 */
object Speech {
    private const val TAG = "Speech"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** [wav] is 16 kHz mono 16-bit WAV from [BtAudio.recordUtterance]. */
    suspend fun transcribe(ctx: Context, wav: ByteArray): String? = withContext(Dispatchers.Main) {
        if (wav.size <= 44) return@withContext null

        // 1. Try on-device recognition first (zero cloud latency)
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            val onDeviceResult = recognize(ctx, wav, preferOnDevice = true)
            if (!onDeviceResult.isNullOrBlank()) {
                Log.i(TAG, "On-device recognition successful: '$onDeviceResult'")
                return@withContext onDeviceResult
            }
            Log.d(TAG, "On-device recognition gave no match, falling back to system recognizer")
        }

        // 2. Fallback to standard system recognizer
        val systemResult = recognize(ctx, wav, preferOnDevice = false)
        if (!systemResult.isNullOrBlank()) {
            Log.i(TAG, "System recognition successful: '$systemResult'")
            return@withContext systemResult
        }

        // 3. Fallback to Whisper API if OpenAI key is configured
        val prefs = Prefs(ctx)
        val openAiKey = prefs.apiKey(Provider.OPENAI)
        if (!openAiKey.isNullOrBlank()) {
            Log.d(TAG, "Local recognizers yielded no text; attempting OpenAI Whisper fallback")
            val whisperResult = transcribeWithWhisper(openAiKey, wav)
            if (!whisperResult.isNullOrBlank()) {
                Log.i(TAG, "Whisper recognition successful: '$whisperResult'")
                return@withContext whisperResult
            }
        }

        Log.w(TAG, "All speech recognition tiers returned no match")
        null
    }

    private suspend fun recognize(ctx: Context, wav: ByteArray, preferOnDevice: Boolean): String? {
        val recognizer = try {
            if (preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create recognizer (preferOnDevice=$preferOnDevice)", e)
            return null
        }

        val (read, write) = ParcelFileDescriptor.createPipe()
        val ready = CompletableDeferred<Unit>()

        return try {
            withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine { continuation ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "Recognizer ready for speech (preferOnDevice=$preferOnDevice)")
                            ready.complete(Unit)
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "Recognizer detected beginning of speech")
                        }

                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() {
                            Log.d(TAG, "Recognizer detected end of speech")
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "SpeechRecognizer error: $error (${errorName(error)}) (preferOnDevice=$preferOnDevice)")
                            ready.complete(Unit) // unblock pipe writer if error occurs early
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onResults(results: Bundle) {
                            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim()
                            Log.i(TAG, "SpeechRecognizer results: '$text' (all: $matches)")
                            if (continuation.isActive) continuation.resume(text?.takeIf { it.isNotBlank() })
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
                                Log.d(TAG, "Partial speech result: '$partial'")
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    continuation.invokeOnCancellation {
                        runCatching { write.close() }
                        runCatching { read.close() }
                    }

                    // Feed PCM into pipe: wait for onReadyForSpeech, then pace chunks smoothly
                    Glasses.scope.launch(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(2000) { ready.await() }
                            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                                val pcmOffset = 44
                                val pcmLen = (wav.size - pcmOffset).coerceAtLeast(0)
                                val chunkSize = 3200 // 100ms at 16kHz 16-bit mono
                                var i = 0
                                while (i < pcmLen) {
                                    val len = minOf(chunkSize, pcmLen - i)
                                    out.write(wav, pcmOffset + i, len)
                                    out.flush()
                                    i += len
                                    delay(50) // Paced steady stream
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Audio pipe feed failed", e)
                        }
                    }

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                        if (preferOnDevice) {
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        }
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, BtAudio.SAMPLE_RATE)
                    }

                    recognizer.startListening(intent)
                }
            }
        } finally {
            recognizer.destroy()
            runCatching { read.close() }
            runCatching { write.close() }
        }
    }

    private suspend fun transcribeWithWhisper(apiKey: String, wav: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file",
                    "speech.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Whisper transcription failed: HTTP ${resp.code} - ${resp.body?.string()}")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val text = json.optString("text").trim()
                Log.i(TAG, "Whisper transcription succeeded: '$text'")
                text.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Whisper transcription exception", e)
            null
        }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        else -> "UNKNOWN_$error"
    }
}
