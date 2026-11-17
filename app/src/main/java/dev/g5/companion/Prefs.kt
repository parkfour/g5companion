package dev.g5.companion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val MODEL_FLASH = "gemini-3.8-flash"
const val MODEL_LIVE = "gemini-3.8-live"
const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful, knowledgeable assistant built into the wearer's smart glasses. Your reply is read aloud, " +
        "so write plain spoken sentences: no markdown, lists, URLs or citations. The photo, when present, is exactly " +
        "what the wearer is looking at; use it whenever the question could be about their surroundings. Search the web " +
        "for anything current or factual you are not sure of (weather, news, hours, prices, scores) instead of saying " +
        "you lack access. Use the provided time and location as the wearer's own. Be direct and specific: lead with the " +
        "answer, keep it to about two to four sentences, and go longer only when the wearer asks for detail." +
        " If the wearer asks you to open an app, share the photo, display the photo, or perform an action like navigation, start your reply with a single " +
        "line: `ACTION: <verb> [target]` (for example `ACTION: open Spotify`, `ACTION: share WhatsApp`, `ACTION: navigate to Times Square`, or `ACTION: display`), " +
        "followed by your spoken confirmation."

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** App-wide theme choice, observed by the Compose theme so changes apply immediately. */
object ThemeState {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
}

/** An AI service the AI button can use. Only Gemini takes audio directly; the others get an on-device transcript. */
enum class Provider(val label: String, val defaultModel: String) {
    GEMINI("Gemini", MODEL_FLASH),
    CLAUDE("Claude", "claude-opus-5-5"),
    OPENAI("OpenAI", "gpt-5-mini"),
    CUSTOM("Custom", ""), // any OpenAI-compatible /chat/completions endpoint (OpenRouter, Groq, Ollama, …)
}

/** Plain settings in SharedPreferences; API keys are AES-GCM encrypted with a non-exportable Keystore key. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("g5", Context.MODE_PRIVATE)

    var lastDeviceMac: String?
        get() = sp.getString("mac", null)
        set(v) = sp.edit().putString("mac", v).apply()
    var lastDeviceName: String?
        get() = sp.getString("name", null)
        set(v) = sp.edit().putString("name", v).apply()
    /** User tapped "BT Disconnect"; suppresses auto-reconnect until they reconnect or re-pair. */
    var userDisconnected: Boolean
        get() = sp.getBoolean("userDisconnected", false)
        set(v) = sp.edit().putBoolean("userDisconnected", v).apply()
    var liveMode: Boolean
        get() = sp.getBoolean("live", false)
        set(v) = sp.edit().putBoolean("live", v).apply()
    var autoDelete: Boolean
        get() = sp.getBoolean("autoDelete", false)
        set(v) = sp.edit().putBoolean("autoDelete", v).apply()
    var videoLoop: Boolean
        get() = sp.getBoolean("videoLoop", false)
        set(v) = sp.edit().putBoolean("videoLoop", v).apply()
    /** On-device hands-free wake word. Off = stop the glasses firmware KWS (kills false triggers); AI button still works. */
    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wakeWord", true)
        set(v) = sp.edit().putBoolean("wakeWord", v).apply()
    /** Speak new phone notifications through the glasses while they're connected. */
    var readNotifications: Boolean
        get() = sp.getBoolean("readNotifications", false)
        set(v) = sp.edit().putBoolean("readNotifications", v).apply()
    /** Say "Call from <name>" through the glasses when the phone rings. */
    var announceCalls: Boolean
        get() = sp.getBoolean("announceCalls", false)
        set(v) = sp.edit().putBoolean("announceCalls", v).apply()
    /** "Call Alex" / "text Alex saying …" by voice; needs contacts, phone and SMS permissions. */
    var voiceCallsTexts: Boolean
        get() = sp.getBoolean("voiceCallsTexts", false)
        set(v) = sp.edit().putBoolean("voiceCallsTexts", v).apply()
    var systemPrompt: String
        get() = sp.getString("systemPrompt", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
        set(v) = sp.edit().putString("systemPrompt", v).apply()

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var provider: Provider
        get() = runCatching { Provider.valueOf(sp.getString("provider", null) ?: "") }.getOrDefault(Provider.GEMINI)
        set(v) = sp.edit().putString("provider", v.name).apply()

    /** Gemini keeps the original "apiKey" slot so existing installs keep their key. */
    private fun keySlot(p: Provider) = if (p == Provider.GEMINI) "apiKey" else "key_${p.name}"

    fun apiKey(p: Provider): String? = sp.getString(keySlot(p), null)?.let(::decrypt)
    fun setApiKey(p: Provider, v: String?) =
        sp.edit().apply { if (v.isNullOrBlank()) remove(keySlot(p)) else putString(keySlot(p), encrypt(v.trim())) }.apply()

    var apiKey: String?
        get() = apiKey(Provider.GEMINI)
        set(v) = setApiKey(Provider.GEMINI, v)

    fun model(p: Provider): String = sp.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModel(p: Provider, v: String) = sp.edit().putString("model_${p.name}", v.trim()).apply()

    /** Base URL for [Provider.CUSTOM], e.g. https://openrouter.ai/api/v1 or http://192.168.1.10:11434/v1 */
    var customUrl: String
        get() = sp.getString("customUrl", null).orEmpty()
        set(v) = sp.edit().putString("customUrl", v.trim().trimEnd('/')).apply()

    var selectedMusicAppId: String
        get() = sp.getString("music_app_id", "youtube_music").orEmpty().ifBlank { "youtube_music" }
        set(v) = sp.edit().putString("music_app_id", v).apply()

    var selectedMusicAppName: String
        get() = sp.getString("music_app_name", "YouTube Music").orEmpty().ifBlank { "YouTube Music" }
        set(v) = sp.edit().putString("music_app_name", v).apply()

    var selectedMusicAppPackage: String?
        get() = sp.getString("music_app_package", "com.google.android.apps.youtube.music")
        set(v) = sp.edit().putString("music_app_package", v).apply()

    fun customVerbs(): List<CustomVerb> {
        val raw = sp.getString("custom_verbs", null) ?: return defaultCustomVerbs()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CustomVerb.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(defaultCustomVerbs())
    }

    fun setCustomVerbs(list: List<CustomVerb>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("custom_verbs", arr.toString()).apply()
    }

    fun defaultCustomVerbs(): List<CustomVerb> = listOf(
        CustomVerb(
            id = "default_nav",
            phrase = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            packageName = "com.google.android.apps.maps",
            dataUriTemplate = "google.navigation:q={target}&mode=d",
            feedback = "Navigating to {target}.",
        ),
        CustomVerb(
            id = "default_share_msg",
            phrase = "share this with messages",
            action = Intent.ACTION_SEND,
            packageName = "com.google.android.apps.messaging",
            attachLatestPhoto = true,
            feedback = "Sharing photo with Messages.",
        ),
        CustomVerb(
            id = "default_display",
            phrase = "display it",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "{photo}",
            feedback = "Displaying photo.",
        ),
    )

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null if the blob is unreadable (e.g. Keystore key wiped); the user just re-enters the key. */
    private fun decrypt(blob: String): String? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "g5_gemini_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
