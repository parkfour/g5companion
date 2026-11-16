package dev.g5.companion

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A user-defined custom verb that maps a voice trigger phrase to an Android Intent.
 */
data class CustomVerb(
    val id: String = UUID.randomUUID().toString(),
    val phrase: String,
    val action: String = Intent.ACTION_VIEW,
    val packageName: String? = null,
    val dataUriTemplate: String? = null,
    val feedback: String? = null,
    val attachLatestPhoto: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("phrase", phrase)
        put("action", action)
        put("pkg", packageName.orEmpty())
        put("uri", dataUriTemplate.orEmpty())
        put("fb", feedback.orEmpty())
        put("attachPhoto", attachLatestPhoto)
        put("extras", JSONObject(extras))
    }

    companion object {
        fun fromJson(obj: JSONObject): CustomVerb {
            val extrasMap = mutableMapOf<String, String>()
            obj.optJSONObject("extras")?.let { ex ->
                for (k in ex.keys()) extrasMap[k] = ex.getString(k)
            }
            return CustomVerb(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                phrase = obj.getString("phrase"),
                action = obj.optString("action", Intent.ACTION_VIEW),
                packageName = obj.optString("pkg").ifBlank { null },
                dataUriTemplate = obj.optString("uri").ifBlank { null },
                feedback = obj.optString("fb").ifBlank { null },
                attachLatestPhoto = obj.optBoolean("attachPhoto", false),
                extras = extrasMap,
            )
        }
    }
}

fun CustomVerb.toIntentRule(name: String = phrase): IntentRule = IntentRule(
    id = id,
    name = name,
    triggerPattern = phrase,
    action = action,
    uriTemplate = dataUriTemplate,
    packageName = packageName,
    attachLatestPhoto = attachLatestPhoto,
    extras = extras,
    feedback = feedback,
)

fun IntentRule.toCustomVerb(): CustomVerb = CustomVerb(
    id = id,
    phrase = triggerPattern,
    action = action,
    packageName = packageName,
    dataUriTemplate = uriTemplate,
    feedback = feedback,
    attachLatestPhoto = attachLatestPhoto,
    extras = extras,
)

/** Information about an installed launcher application. */
data class InstalledApp(val label: String, val packageName: String)

/**
 * A documented intent template with a title, description, and suggested default values.
 */
data class IntentTemplate(
    val title: String,
    val description: String,
    val defaultPhrase: String,
    val action: String,
    val packageName: String? = null,
    val dataUriTemplate: String? = null,
    val feedback: String,
)

/** Result of executing an intent verb action. */
data class ActionResult(
    val executed: Boolean,
    val spokenFeedback: String,
    val turnAnswer: String = spokenFeedback,
    val intent: Intent? = null,
)

/** Parsed intent verb action. */
sealed interface ParsedAction {
    data class OpenApp(val appQuery: String) : ParsedAction
    data class SharePhoto(val appQuery: String? = null) : ParsedAction
    object DisplayPhoto : ParsedAction
    data class Custom(val verb: CustomVerb, val parameter: String? = null) : ParsedAction
}

/**
 * Handles voice/AI "verbs" (like "open <app>", "share with <app>", "display it", or custom verbs)
 * and dispatches them to standard Android Intents.
 */
object ActionDispatcher {
    private const val TAG = "ActionDispatcher"

    val INTENT_TEMPLATES = listOf(
        IntentTemplate(
            title = "Navigate to Destination",
            description = "Starts Google Maps turn-by-turn navigation to any spoken destination.",
            defaultPhrase = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "google.navigation:q={target}&mode=d",
            feedback = "Navigating to {target}.",
        ),
        IntentTemplate(
            title = "Music Search / Play",
            description = "Searches and plays any spoken song, artist, album, or playlist in your default music player.",
            defaultPhrase = "play {query}",
            action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            dataUriTemplate = null,
            feedback = "Playing {query}.",
        ),
        IntentTemplate(
            title = "Google Search",
            description = "Searches Google for any query spoken aloud.",
            defaultPhrase = "search {query}",
            action = "android.intent.action.WEB_SEARCH",
            dataUriTemplate = "https://www.google.com/search?q={query}",
            feedback = "Searching Google for {query}.",
        ),
        IntentTemplate(
            title = "Display POV Photo",
            description = "Opens the captured POV photo in the default full-screen image viewer.",
            defaultPhrase = "display it",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "{photo}",
            feedback = "Displaying photo.",
        ),
        IntentTemplate(
            title = "Share POV Photo",
            description = "Opens the system share sheet (or a specific app) with the POV photo attached.",
            defaultPhrase = "share it",
            action = Intent.ACTION_SEND,
            dataUriTemplate = "{photo}",
            feedback = "Sharing photo.",
        ),
        IntentTemplate(
            title = "Open Web URL",
            description = "Opens a designated website or web app in the default web browser.",
            defaultPhrase = "open website",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "https://example.com",
            feedback = "Opening web page.",
        ),
        IntentTemplate(
            title = "Phone Call / Dialer",
            description = "Opens the phone dialer with a contact number pre-filled.",
            defaultPhrase = "call contact",
            action = Intent.ACTION_DIAL,
            dataUriTemplate = "tel:+15551234567",
            feedback = "Opening phone dialer.",
        ),
        IntentTemplate(
            title = "Send SMS / Text",
            description = "Opens the default messaging app with recipient pre-filled.",
            defaultPhrase = "send text",
            action = Intent.ACTION_SENDTO,
            dataUriTemplate = "smsto:+15551234567",
            feedback = "Opening message.",
        ),
        IntentTemplate(
            title = "System Settings",
            description = "Opens the Android system settings screen.",
            defaultPhrase = "open settings",
            action = "android.settings.SETTINGS",
            feedback = "Opening settings.",
        ),
        IntentTemplate(
            title = "Bluetooth Settings",
            description = "Opens the Bluetooth pairing and connected devices settings screen.",
            defaultPhrase = "bluetooth settings",
            action = "android.settings.BLUETOOTH_SETTINGS",
            feedback = "Opening Bluetooth settings.",
        ),
        IntentTemplate(
            title = "Wi-Fi Settings",
            description = "Opens the Wi-Fi network settings screen.",
            defaultPhrase = "wifi settings",
            action = "android.settings.WIFI_SETTINGS",
            feedback = "Opening Wi-Fi settings.",
        ),
    )

    private val OPEN_REGEX = Regex("""^(?:open|launch|start)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SHARE_REGEX = Regex(
        """^share(?:\s+(?:the\s+)?(?:photo|picture|image|it))?(?:(?:\s+(?:with|to))?\s+(.+))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val DISPLAY_REGEX = Regex(
        """^(?:display|show|view)(?:\s+(?:it|this|(?:the\s+)?(?:photo|picture|image)))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_PHOTO_TARGETS = setOf("it", "this", "photo", "the photo", "picture", "the picture", "image", "the image")
    private val PLACEHOLDER_PATTERN = Regex("""[\{<](target|query|arg|text|destination)[\}>]""", RegexOption.IGNORE_CASE)

    fun hasPlaceholder(text: String): Boolean = PLACEHOLDER_PATTERN.containsMatchIn(text)

    fun sampleParameterFor(verb: CustomVerb): String? {
        val combined = "${verb.phrase} ${verb.dataUriTemplate.orEmpty()}"
        return when {
            Regex("""[\{<](?:target|destination)[\}>]""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "Times Square"
            Regex("""[\{<]query[\}>]""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "Miles Davis"
            Regex("""[\{<](?:arg|text)[\}>]""", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "Hello world"
            hasPlaceholder(combined) -> "sample"
            else -> null
        }
    }

    /**
     * Parses a text command into a [ParsedAction], checking custom verbs first (including
     * dynamic patterns like "navigate to {target}" or "play {query}"), followed by built-in
     * patterns: "open <app>", "share with <app>", "display it".
     */
    fun parse(text: String, customVerbs: List<CustomVerb> = emptyList()): ParsedAction? {
        val clean = text.trim().trimEnd('.', '!', '?').trim()
        if (clean.isBlank()) return null

        // 1. Exact static matches on custom verbs (checked first so specific verbs don't get swallowed)
        val staticMatch = customVerbs.firstOrNull { verb ->
            !hasPlaceholder(verb.phrase) &&
                (verb.dataUriTemplate == null || !hasPlaceholder(verb.dataUriTemplate)) &&
                verb.phrase.equals(clean, ignoreCase = true)
        }
        if (staticMatch != null) return ParsedAction.Custom(staticMatch, parameter = null)

        // 2. Parameterized custom verbs
        for (verb in customVerbs) {
            if (hasPlaceholder(verb.phrase)) {
                // Phrase contains placeholder e.g. "navigate to {target}" or "find {query} nearby"
                val parts = verb.phrase.trim().split(PLACEHOLDER_PATTERN)
                val prefix = parts.getOrNull(0)?.trim().orEmpty()
                val suffix = parts.getOrNull(1)?.trim().orEmpty()

                val prefixPattern = if (prefix.isNotEmpty()) {
                    prefix.split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                } else null
                val suffixPattern = if (suffix.isNotEmpty()) {
                    suffix.split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                } else null

                val regex = when {
                    prefixPattern != null && suffixPattern != null ->
                        Regex("""^$prefixPattern\s+(.+)\s+$suffixPattern$""", RegexOption.IGNORE_CASE)
                    prefixPattern != null ->
                        Regex("""^$prefixPattern\s+(.+)$""", RegexOption.IGNORE_CASE)
                    suffixPattern != null ->
                        Regex("""^(.+)\s+$suffixPattern$""", RegexOption.IGNORE_CASE)
                    else ->
                        Regex("""^(.+)$""", RegexOption.IGNORE_CASE)
                }

                val m = regex.matchEntire(clean)
                if (m != null) {
                    val param = m.groupValues.getOrNull(1)?.trim().orEmpty()
                    if (param.isNotBlank()) {
                        return ParsedAction.Custom(verb, parameter = param)
                    }
                }
            } else if (verb.dataUriTemplate != null && hasPlaceholder(verb.dataUriTemplate)) {
                // Phrase is a prefix like "navigate to", and the template has {target} / {query}
                val prefixPattern = verb.phrase.trim().split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                val prefixRegex = Regex("""^$prefixPattern\s+(.+)$""", RegexOption.IGNORE_CASE)
                val m = prefixRegex.matchEntire(clean)
                if (m != null) {
                    val param = m.groupValues.getOrNull(1)?.trim().orEmpty()
                    if (param.isNotBlank()) {
                        return ParsedAction.Custom(verb, parameter = param)
                    }
                }
            } else {
                if (verb.phrase.equals(clean, ignoreCase = true)) {
                    return ParsedAction.Custom(verb, parameter = null)
                }
            }
        }

        // 2. Open <app>
        OPEN_REGEX.matchEntire(clean)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotBlank()) return ParsedAction.OpenApp(target)
        }

        // 3. Share with / to <app> or bare share ("share it", "share photo")
        SHARE_REGEX.matchEntire(clean)?.let { m ->
            val rawTarget = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val target = if (rawTarget.isBlank() || rawTarget.lowercase() in BARE_PHOTO_TARGETS) null else rawTarget
            return ParsedAction.SharePhoto(target)
        }

        // 4. Display it / show it / view it
        if (DISPLAY_REGEX.matches(clean)) {
            return ParsedAction.DisplayPhoto
        }

        return null
    }

    /**
     * Parses an LLM directive formatted as `ACTION: <verb> [target]`.
     * Returns the parsed action along with the remaining conversational text (if any).
     */
    fun parseActionDirective(llmReply: String, customVerbs: List<CustomVerb> = emptyList()): Pair<ParsedAction, String>? {
        val firstLine = llmReply.lineSequence().firstOrNull().orEmpty().trim()
        if (!firstLine.startsWith("ACTION:", ignoreCase = true)) return null

        val directive = firstLine.substringAfter(':').trim()
        val action = parse(directive, customVerbs) ?: return null
        val remaining = llmReply.substringAfter('\n', "").trim()
        return action to remaining
    }

    /**
     * Executes the given action via an Android Intent, utilizing the current or latest POV photo.
     */
    fun execute(context: Context, action: ParsedAction, photoBytes: ByteArray? = null): ActionResult {
        return try {
            when (action) {
                is ParsedAction.OpenApp -> executeOpenApp(context, action.appQuery)
                is ParsedAction.SharePhoto -> executeSharePhoto(context, action.appQuery, photoBytes)
                is ParsedAction.DisplayPhoto -> executeDisplayPhoto(context, photoBytes)
                is ParsedAction.Custom -> executeCustom(context, action.verb, action.parameter, photoBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute action: $action", e)
            ActionResult(
                executed = false,
                spokenFeedback = "Failed to perform that action: ${e.message ?: "unknown error"}",
            )
        }
    }

    private fun executeOpenApp(context: Context, appQuery: String): ActionResult {
        val app = findInstalledApp(context, appQuery)
            ?: return ActionResult(false, "Could not find an app named $appQuery.")

        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ActionResult(false, "Cannot launch ${app.label}.")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult(
            executed = true,
            spokenFeedback = "Opening ${app.label}.",
            intent = intent,
        )
    }

    private fun executeSharePhoto(context: Context, appQuery: String?, photoBytes: ByteArray?): ActionResult {
        val photoFile = resolvePhotoFile(context, photoBytes)
            ?: return ActionResult(false, "No photo available to share.")

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (appQuery != null) {
            val app = findInstalledApp(context, appQuery)
            if (app != null) {
                shareIntent.setPackage(app.packageName)
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(shareIntent)
                    ActionResult(true, "Sharing photo with ${app.label}.", intent = shareIntent)
                } catch (_: Exception) {
                    // Fall back to chooser if the app doesn't have a direct SEND handler
                    val chooser = Intent.createChooser(shareIntent, "Share Photo").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(chooser)
                    ActionResult(true, "Sharing photo with ${app.label}.", intent = chooser)
                }
            }
        }

        val chooser = Intent.createChooser(shareIntent, "Share Photo").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(chooser)
        return ActionResult(true, "Sharing photo.", intent = chooser)
    }

    private fun executeDisplayPhoto(context: Context, photoBytes: ByteArray?): ActionResult {
        val photoFile = resolvePhotoFile(context, photoBytes)
            ?: return ActionResult(false, "No photo available to display.")

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(viewIntent)
        return ActionResult(true, "Displaying photo.", intent = viewIntent)
    }

    private fun executeCustom(
        context: Context,
        verb: CustomVerb,
        parameter: String?,
        photoBytes: ByteArray?,
    ): ActionResult {
        val photoFile = resolvePhotoFile(context, photoBytes)
        val photoUri = photoFile?.let {
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }.getOrNull()
        }

        val intent = Intent(verb.action).apply {
            verb.packageName?.let { setPackage(it) }
            verb.dataUriTemplate?.let { tpl ->
                var resolved = tpl
                if (photoUri != null) {
                    resolved = resolved.replace("{photo}", photoUri.toString())
                }
                if (parameter != null) {
                    val encoded = if (tpl.contains("?") || tpl.contains(":q=")) {
                        java.net.URLEncoder.encode(parameter, "UTF-8")
                    } else {
                        java.net.URLEncoder.encode(parameter, "UTF-8").replace("+", "%20")
                    }
                    resolved = resolved
                        .replace("{target}", encoded, ignoreCase = true)
                        .replace("<target>", encoded, ignoreCase = true)
                        .replace("{query}", encoded, ignoreCase = true)
                        .replace("<query>", encoded, ignoreCase = true)
                        .replace("{arg}", encoded, ignoreCase = true)
                        .replace("<arg>", encoded, ignoreCase = true)
                        .replace("{text}", encoded, ignoreCase = true)
                        .replace("<text>", encoded, ignoreCase = true)
                        .replace("{destination}", encoded, ignoreCase = true)
                        .replace("<destination>", encoded, ignoreCase = true)
                }
                if (resolved.isNotBlank() && (resolved.contains("://") || resolved.contains(":"))) {
                    runCatching { Uri.parse(resolved) }.getOrNull()?.let { setData(it) }
                }
            }
            if (verb.attachLatestPhoto && photoBytes != null) {
                val processed = PovImageProcessor.process(context, photoBytes)
                if (processed != null) {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, processed.uri)
                    clipData = ClipData.newRawUri(null, processed.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else if (photoUri != null) {
                clipData = ClipData.newRawUri(null, photoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (parameter != null) {
                putExtra(Intent.EXTRA_TEXT, parameter)
            }
            for ((k, v) in verb.extras) {
                putExtra(k, v)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)

        var feedback = verb.feedback?.takeIf { it.isNotBlank() } ?: "Executed ${verb.phrase}."
        if (parameter != null) {
            feedback = feedback
                .replace("{target}", parameter, ignoreCase = true)
                .replace("<target>", parameter, ignoreCase = true)
                .replace("{query}", parameter, ignoreCase = true)
                .replace("<query>", parameter, ignoreCase = true)
                .replace("{arg}", parameter, ignoreCase = true)
                .replace("<arg>", parameter, ignoreCase = true)
                .replace("{text}", parameter, ignoreCase = true)
                .replace("<text>", parameter, ignoreCase = true)
                .replace("{destination}", parameter, ignoreCase = true)
                .replace("<destination>", parameter, ignoreCase = true)
        }

        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val isLocked = keyguard?.isKeyguardLocked == true
        val isAuthGated = verb.action == Intent.ACTION_SEND || verb.action == Intent.ACTION_SENDTO ||
            verb.packageName?.contains("messaging") == true || verb.packageName?.contains("whatsapp") == true
        if (isLocked && isAuthGated) {
            feedback = "Ready in Messages, unlock phone to send."
        }

        return ActionResult(true, feedback, intent = intent)
    }

    /** Resolves the photo file from current bytes or latest saved history photo. */
    fun resolvePhotoFile(context: Context, photoBytes: ByteArray?): File? {
        if (photoBytes != null && photoBytes.isNotEmpty()) {
            val dir = File(context.cacheDir, "shared_pov").apply { mkdirs() }
            val file = File(dir, "latest_pov.jpg")
            file.writeBytes(photoBytes)
            return file
        }
        val povDir = File(context.filesDir, "pov")
        return povDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Lists all launchable installed apps on the device. */
    fun listInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0).mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString()?.trim()
            val pkg = info.activityInfo?.packageName
            if (!label.isNullOrBlank() && !pkg.isNullOrBlank()) InstalledApp(label, pkg) else null
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    /** Finds the best-matching installed application for the given query string. */
    fun findInstalledApp(context: Context, query: String): InstalledApp? {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return null
        val apps = listInstalledApps(context)

        // 1. Exact label match (case-insensitive)
        apps.firstOrNull { it.label.equals(clean, ignoreCase = true) }?.let { return it }

        // 2. Query equals package name or ends with query (e.g. "spotify" for "com.spotify.music")
        apps.firstOrNull { it.packageName.equals(clean, ignoreCase = true) || it.packageName.endsWith(".$clean", ignoreCase = true) }
            ?.let { return it }

        // 3. Label starts with query (e.g. "maps" -> "Maps")
        apps.firstOrNull { it.label.lowercase().startsWith(clean) }?.let { return it }

        // 4. Label contains query as word or substring (e.g. "maps" -> "Google Maps")
        apps.firstOrNull { it.label.lowercase().contains(clean) }?.let { return it }

        // 5. Package name contains query
        apps.firstOrNull { it.packageName.lowercase().contains(clean) }?.let { return it }

        return null
    }
}
