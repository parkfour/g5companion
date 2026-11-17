package dev.g5.companion

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ResolvedIntentSpec(
    val action: String,
    val resolvedDataUri: String?,
    val packageName: String?,
    val type: String?,
    val streamUri: Uri?,
    val extraText: String?,
    val extras: Map<String, String>,
    val flags: Int,
    val feedback: String,
)

data class CommandResult(
    val success: Boolean,
    val feedback: String,
    val executedLocally: Boolean,
    val intent: Intent? = null,
    val spec: ResolvedIntentSpec? = null,
    val requiresUnlock: Boolean = false,
)

/**
 * On-Device Command Router for DPVR G5 Glasses.
 *
 * Implements BAL-compliant screen-off intent execution, Keyguard-aware feedback,
 * and lazy LiteRT-LM integration on Tensor G5 NPU/GPU with automatic fallback to cloud LLMs.
 */
class GlassesCommandRouter(
    private val context: Context,
    private val ruleStore: IntentRuleStore,
) {
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private var liteRtEngine: Engine? = null
    private var isEngineInitializing = false

    /**
     * Lazily initialize LiteRT-LM engine with multi-tier hardware fallback:
     * 1. Google Tensor (Pixel / Tensor G5 NPU) if on Google hardware
     * 2. GPU (Adreno / Mali via OpenCL / Vulkan)
     * 3. CPU (Universal fallback for all Android devices)
     */
    suspend fun ensureLiteRtInitialized() = withContext(Dispatchers.IO) {
        if (liteRtEngine != null || isEngineInitializing) return@withContext
        isEngineInitializing = true
        try {
            val modelFile = File(context.filesDir, "models/gemma.litertlm")
            if (modelFile.exists() && modelFile.canRead()) {
                val backends = getPreferredBackends()
                var initialized = false
                for ((name, backend) in backends) {
                    try {
                        val config = EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = backend,
                        )
                        val engine = Engine(config)
                        engine.initialize()
                        liteRtEngine = engine
                        Log.i(TAG, "LiteRT-LM engine initialized using backend: $name")
                        initialized = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "LiteRT-LM initialization failed on $name backend: ${e.message}")
                    }
                }
                if (!initialized) {
                    Log.w(TAG, "LiteRT-LM could not initialize on any hardware backend; using deterministic pattern router")
                }
            } else {
                Log.d(TAG, "No local .litertlm model file at ${modelFile.path}; using deterministic pattern router")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LiteRT-LM initialization skipped/failed: ${e.message}")
        } finally {
            isEngineInitializing = false
        }
    }

    /**
     * Tool 1: executeIntentRule
     *
     * Looks up the rule, sanitizes target with Uri.encode, builds the Intent with
     * FLAG_ACTIVITY_NEW_TASK, attaches downscaled 768x768 FileProvider Uri if requested,
     * applies extras, checks lockscreen state, and dispatches the intent.
     */
    fun executeIntentRule(
        ruleId: String,
        target: String? = null,
        rawPhotoBytes: ByteArray? = null,
    ): CommandResult {
        val rule = ruleStore.getById(ruleId) ?: ruleStore.getActive().firstOrNull { it.id == ruleId }
        if (rule == null) {
            val msg = "Unknown rule ID: $ruleId"
            Log.e(TAG, msg)
            return CommandResult(false, msg, executedLocally = true)
        }

        return try {
            val processedPhoto = if (rule.attachLatestPhoto && rawPhotoBytes != null) {
                PovImageProcessor.process(context, rawPhotoBytes)
            } else null

            val spec = resolveIntentSpec(rule, target, processedPhoto?.uri)
            val intent = buildIntentFromSpec(spec)

            // Lockscreen & keyguard awareness
            val isLocked = keyguardManager?.isKeyguardLocked == true
            val requiresUnlock = isLocked && isAuthGatedAction(rule)

            val feedback = if (requiresUnlock) {
                val appName = friendlyAppName(rule.packageName) ?: "app"
                "Ready in $appName, unlock phone to send."
            } else {
                spec.feedback
            }

            // Launch the intent
            context.startActivity(intent)

            // Deliver spoken audio feedback to glasses
            Assistant.say(feedback)

            CommandResult(
                success = true,
                feedback = feedback,
                executedLocally = true,
                intent = intent,
                spec = spec,
                requiresUnlock = requiresUnlock,
            )
        } catch (e: ActivityNotFoundException) {
            val err = "No installed app found to handle ${rule.name}."
            Log.w(TAG, err, e)
            Assistant.say(err)
            CommandResult(false, err, executedLocally = true)
        } catch (e: Exception) {
            val err = "Failed to launch ${rule.name}: ${e.message ?: "unknown error"}"
            Log.e(TAG, err, e)
            Assistant.say("Could not perform action.")
            CommandResult(false, err, executedLocally = true)
        }
    }

    /**
     * Tool 2: delegateToCloud
     *
     * Fallback tool routing open-ended queries (with 768x768 POV photo if needed)
     * to the configured cloud LLM (Gemini / Claude / OpenAI / Custom Server).
     */
    suspend fun delegateToCloud(
        transcribedQuery: String,
        requiresImage: Boolean,
        rawPhotoBytes: ByteArray? = null,
    ): CommandResult {
        Log.i(TAG, "Delegating to cloud: '$transcribedQuery', requiresImage=$requiresImage")
        val imagePayload = if (requiresImage && rawPhotoBytes != null) {
            PovImageProcessor.process(context, rawPhotoBytes)?.bytes ?: rawPhotoBytes
        } else null

        Assistant.askTyped(transcribedQuery, withPhoto = requiresImage && imagePayload != null)
        return CommandResult(
            success = true,
            feedback = "Delegated to cloud assistant",
            executedLocally = false,
        )
    }

    /**
     * Main dispatch pipeline for voice commands received from glasses.
     */
    suspend fun route(
        voicePrompt: String,
        rawPhotoBytes: ByteArray? = null,
    ): CommandResult {
        val clean = voicePrompt.trim()
        if (clean.isBlank()) {
            return CommandResult(false, "No speech recognized", executedLocally = true)
        }

        // 0. Built-in hands-free commands (reply, call, text, timers, walking directions, "what did I miss")
        if (HandsFree.handle(context, clean)) return CommandResult(true, "Handled hands-free", executedLocally = true)

        // 1. Fast-Path Pattern Match: Instant on-device execution (0ms cloud latency)
        val match = ruleStore.findMatchingRule(clean)
        if (match != null) {
            val (rule, target) = match
            Log.i(TAG, "Fast-path matched rule '${rule.name}' with target '$target'")
            return executeIntentRule(rule.id, target, rawPhotoBytes)
        }

        // 2. Fallback to Cloud LLM
        val requiresImage = looksLikeVisualQuery(clean) || rawPhotoBytes != null
        return delegateToCloud(clean, requiresImage, rawPhotoBytes)
    }

    private fun isAuthGatedAction(rule: IntentRule): Boolean {
        if (rule.action == Intent.ACTION_SEND || rule.action == Intent.ACTION_SENDTO) return true
        val pkg = rule.packageName?.lowercase().orEmpty()
        return pkg.contains("messaging") || pkg.contains("whatsapp") || pkg.contains("slack") ||
            pkg.contains("telegram") || pkg.contains("signal") || pkg.contains("mms")
    }

    private fun friendlyAppName(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return when {
            packageName.contains("messaging") || packageName.contains("mms") -> "Messages"
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("maps") -> "Google Maps"
            packageName.contains("youtube.music") -> "YouTube Music"
            packageName.contains("sirius") -> "SiriusXM"
            packageName.contains("musicolet") -> "Musicolet"
            packageName.contains("tunein") -> "TuneIn Radio"
            packageName.contains("spotify") -> "Spotify"
            packageName.contains("apple.music") -> "Apple Music"
            packageName.contains("amazon.mp3") -> "Amazon Music"
            packageName.contains("tidal") -> "Tidal"
            packageName.contains("vlc") -> "VLC"
            packageName.contains("chrome") -> "Chrome"
            packageName.contains("youtube") -> "YouTube"
            packageName.contains("slack") -> "Slack"
            packageName.contains("signal") -> "Signal"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun looksLikeVisualQuery(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("look at") || lower.contains("what am i") ||
            lower.contains("what is this") || lower.contains("see this") ||
            lower.contains("read this") || lower.contains("who is this")
    }

    companion object {
        private const val TAG = "GlassesCommandRouter"

        // Backend names in the order ensureLiteRtInitialized() should try them.
        internal const val BACKEND_TENSOR = "Google Tensor"
        internal const val BACKEND_GPU = "GPU"
        internal const val BACKEND_CPU = "CPU"

        /** Pure, testable backend ordering: Tensor first only on Google hardware, then GPU, then CPU. */
        internal fun backendOrder(
            manufacturer: String?,
            hardware: String?,
            soc: String?,
        ): List<String> {
            val isTensorHardware = manufacturer.equals("Google", ignoreCase = true) ||
                hardware?.contains("tensor", ignoreCase = true) == true ||
                soc.equals("Google", ignoreCase = true)
            return buildList {
                if (isTensorHardware) add(BACKEND_TENSOR)
                add(BACKEND_GPU)
                add(BACKEND_CPU)
            }
        }

        internal fun getPreferredBackends(): List<Pair<String, Backend>> {
            val manufacturer = runCatching { Build.MANUFACTURER }.getOrNull()
            val hardware = runCatching { Build.HARDWARE }.getOrNull()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { Build.SOC_MANUFACTURER }.getOrNull()
            } else null
            // Backend.* is constructed here (not at class-init) so its Java-21 class
            // doesn't get linked in the Java-17 unit-test JVM.
            return backendOrder(manufacturer, hardware, soc).map { name ->
                name to when (name) {
                    BACKEND_TENSOR -> Backend.GOOGLE_TENSOR()
                    BACKEND_GPU -> Backend.GPU()
                    else -> Backend.CPU()
                }
            }
        }

        fun sanitizeTarget(target: String): String {
            return runCatching { Uri.encode(target) }.getOrElse {
                java.net.URLEncoder.encode(target, "UTF-8").replace("+", "%20")
            }
        }

        fun resolveIntentSpec(rule: IntentRule, target: String?, photoUri: Uri? = null): ResolvedIntentSpec {
            val sanitized = target?.let { sanitizeTarget(it) }
            var resolvedUri = rule.uriTemplate
            if (!sanitized.isNullOrBlank() && resolvedUri != null) {
                resolvedUri = resolvedUri
                    .replace("{target}", sanitized, ignoreCase = true)
                    .replace("<target>", sanitized, ignoreCase = true)
                    .replace("{query}", sanitized, ignoreCase = true)
                    .replace("<query>", sanitized, ignoreCase = true)
                    .replace("{arg}", sanitized, ignoreCase = true)
                    .replace("<arg>", sanitized, ignoreCase = true)
                    .replace("{text}", sanitized, ignoreCase = true)
                    .replace("<text>", sanitized, ignoreCase = true)
                    .replace("{destination}", sanitized, ignoreCase = true)
                    .replace("<destination>", sanitized, ignoreCase = true)
            }

            var feedback = rule.feedback?.takeIf { it.isNotBlank() } ?: "Executing ${rule.name}."
            if (!target.isNullOrBlank()) {
                feedback = feedback
                    .replace("{target}", target, ignoreCase = true)
                    .replace("<target>", target, ignoreCase = true)
                    .replace("{query}", target, ignoreCase = true)
                    .replace("<query>", target, ignoreCase = true)
                    .replace("{arg}", target, ignoreCase = true)
                    .replace("<arg>", target, ignoreCase = true)
                    .replace("{destination}", target, ignoreCase = true)
                    .replace("<destination>", target, ignoreCase = true)
            }

            val resolvedExtras = mutableMapOf<String, String>()
            for ((k, v) in rule.extras) {
                val resolvedVal = if (!target.isNullOrBlank()) {
                    v.replace("{target}", target, ignoreCase = true)
                        .replace("<target>", target, ignoreCase = true)
                        .replace("{query}", target, ignoreCase = true)
                        .replace("<query>", target, ignoreCase = true)
                        .replace("{arg}", target, ignoreCase = true)
                        .replace("<arg>", target, ignoreCase = true)
                } else v
                resolvedExtras[k] = resolvedVal
            }

            // Auto-populate query extra for MEDIA_PLAY_FROM_SEARCH if missing
            if (rule.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH" && !target.isNullOrBlank()) {
                if (!resolvedExtras.containsKey("query")) {
                    resolvedExtras["query"] = target
                }
                if (!resolvedExtras.containsKey("android.intent.extra.focus")) {
                    resolvedExtras["android.intent.extra.focus"] = "vnd.android.cursor.item/*"
                }
            }

            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                (if (rule.attachLatestPhoto && photoUri != null) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0)

            return ResolvedIntentSpec(
                action = rule.action,
                resolvedDataUri = resolvedUri,
                packageName = rule.packageName,
                type = if (rule.attachLatestPhoto && photoUri != null) "image/jpeg" else null,
                streamUri = if (rule.attachLatestPhoto) photoUri else null,
                extraText = target,
                extras = resolvedExtras,
                flags = flags,
                feedback = feedback,
            )
        }

        fun buildIntentFromSpec(spec: ResolvedIntentSpec): Intent {
            return Intent(spec.action).apply {
                addFlags(spec.flags)
                spec.packageName?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
                spec.resolvedDataUri?.takeIf { it.isNotBlank() && (it.contains("://") || it.contains(":")) }?.let {
                    runCatching { Uri.parse(it) }.getOrNull()?.let { uri -> setData(uri) }
                }
                spec.type?.let { type = it }
                spec.streamUri?.let {
                    putExtra(Intent.EXTRA_STREAM, it)
                    clipData = ClipData.newRawUri(null, it)
                }
                spec.extraText?.takeIf { it.isNotBlank() }?.let {
                    putExtra(Intent.EXTRA_TEXT, it)
                }
                for ((k, v) in spec.extras) {
                    putExtra(k, v)
                }
            }
        }
    }
}
