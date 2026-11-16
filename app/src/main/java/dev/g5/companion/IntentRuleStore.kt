package dev.g5.companion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Model representing a user-configurable intent rule mapped from voice commands.
 */
data class IntentRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerPattern: String,
    val action: String = Intent.ACTION_VIEW,
    val uriTemplate: String? = null,
    val packageName: String? = null,
    val attachLatestPhoto: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
    val feedback: String? = null,
    val isEnabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("triggerPattern", triggerPattern)
        put("action", action)
        putOpt("uriTemplate", uriTemplate)
        putOpt("packageName", packageName)
        put("attachLatestPhoto", attachLatestPhoto)
        put("extras", JSONObject(extras))
        putOpt("feedback", feedback)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject): IntentRule {
            val extrasMap = mutableMapOf<String, String>()
            json.optJSONObject("extras")?.let { extrasObj ->
                for (key in extrasObj.keys()) {
                    extrasMap[key] = extrasObj.getString(key)
                }
            }
            return IntentRule(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Custom Rule"),
                triggerPattern = json.optString("triggerPattern", ""),
                action = json.optString("action", Intent.ACTION_VIEW),
                uriTemplate = json.optString("uriTemplate").takeIf { it.isNotBlank() },
                packageName = json.optString("packageName").takeIf { it.isNotBlank() },
                attachLatestPhoto = json.optBoolean("attachLatestPhoto", false),
                extras = extrasMap,
                feedback = json.optString("feedback").takeIf { it.isNotBlank() },
                isEnabled = json.optBoolean("isEnabled", true),
            )
        }
    }
}

/**
 * Persistent store for user-configurable on-device intent routing rules.
 * Backed by SharedPreferences JSON storage with reactive StateFlow updates.
 */
class IntentRuleStore(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _rules = MutableStateFlow<List<IntentRule>>(emptyList())
    val rules: StateFlow<List<IntentRule>> = _rules.asStateFlow()

    init {
        loadRules()
    }

    fun getAll(): List<IntentRule> = _rules.value

    fun getActive(): List<IntentRule> = _rules.value.filter { it.isEnabled }

    fun getById(id: String): IntentRule? = _rules.value.firstOrNull { it.id == id }

    @Synchronized
    fun saveRule(rule: IntentRule) {
        val current = _rules.value.toMutableList()
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            current[index] = rule
        } else {
            current.add(rule)
        }
        persist(current)
    }

    @Synchronized
    fun deleteRule(id: String) {
        val current = _rules.value.filterNot { it.id == id }
        persist(current)
    }

    @Synchronized
    fun toggleEnabled(id: String) {
        val updated = _rules.value.map {
            if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
        }
        persist(updated)
    }

    @Synchronized
    fun resetToDefaults() {
        persist(DEFAULT_RULES)
    }

    /**
     * Serializes all active rules into a concise JSON string suitable for injecting
     * into a LiteRT-LM system prompt or tool context.
     */
    fun serializeActiveRulesForPrompt(): String {
        val array = JSONArray()
        for (rule in getActive()) {
            array.put(JSONObject().apply {
                put("ruleId", rule.id)
                put("name", rule.name)
                put("pattern", rule.triggerPattern)
                put("action", rule.action)
                put("attachPhoto", rule.attachLatestPhoto)
            })
        }
        return array.toString()
    }

    /**
     * Finds an active matching rule using pattern extraction.
     * Returns the matched rule and any extracted target parameter.
     */
    fun findMatchingRule(text: String): Pair<IntentRule, String?>? {
        val clean = text.trim().trimEnd('.', '!', '?').trim()
        if (clean.isBlank()) return null
        val active = getActive()

        // 1. Exact static match check
        val staticMatch = active.firstOrNull {
            !PLACEHOLDER_REGEX.containsMatchIn(it.triggerPattern) &&
                it.triggerPattern.equals(clean, ignoreCase = true)
        }
        if (staticMatch != null) return staticMatch to null

        // 2. Parameterized pattern check
        for (rule in active) {
            if (PLACEHOLDER_REGEX.containsMatchIn(rule.triggerPattern)) {
                val parts = rule.triggerPattern.trim().split(PLACEHOLDER_REGEX)
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
                        return rule to param
                    }
                }
            } else if (rule.uriTemplate?.contains(PLACEHOLDER_REGEX) == true) {
                val prefixPattern = rule.triggerPattern.trim().split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                val prefixRegex = Regex("""^$prefixPattern\s+(.+)$""", RegexOption.IGNORE_CASE)
                val m = prefixRegex.matchEntire(clean)
                if (m != null) {
                    val param = m.groupValues.getOrNull(1)?.trim().orEmpty()
                    if (param.isNotBlank()) {
                        return rule to param
                    }
                }
            }
        }

        return null
    }

    private fun loadRules() {
        val raw = prefs.getString(KEY_RULES, null)
        if (raw.isNullOrBlank()) {
            persist(DEFAULT_RULES)
            return
        }
        try {
            val jsonArray = JSONArray(raw)
            val list = mutableListOf<IntentRule>()
            var needsPersist = false
            for (i in 0 until jsonArray.length()) {
                val rule = IntentRule.fromJson(jsonArray.getJSONObject(i))
                // Seamless migration from legacy Spotify default rule to active music app
                if (rule.id == "rule_spotify_play") {
                    val p = Prefs(context)
                    val musicOption = MusicAppManager.getSelectedMusicApp(context, p)
                    list.add(
                        IntentRule(
                            id = "rule_music_play",
                            name = "${musicOption.name} Play",
                            triggerPattern = "play {query}",
                            action = musicOption.action,
                            uriTemplate = musicOption.uriTemplate,
                            packageName = musicOption.packageName,
                            attachLatestPhoto = false,
                            extras = musicOption.extras,
                            feedback = musicOption.feedback,
                            isEnabled = rule.isEnabled,
                        ),
                    )
                    needsPersist = true
                } else {
                    list.add(rule)
                }
            }
            if (needsPersist) {
                persist(list)
            } else {
                _rules.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing intent rules, resetting to defaults", e)
            persist(DEFAULT_RULES)
        }
    }

    private fun persist(list: List<IntentRule>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
        _rules.value = list
    }

    companion object {
        private const val TAG = "IntentRuleStore"
        private const val PREFS_NAME = "g5_intent_rule_store"
        private const val KEY_RULES = "intent_rules_json"
        val PLACEHOLDER_REGEX = Regex("""[\{<](target|query|arg|text|destination)[\}>]""", RegexOption.IGNORE_CASE)

        val DEFAULT_RULES = listOf(
            IntentRule(
                id = "rule_nav_target",
                name = "Navigate to Destination",
                triggerPattern = "navigate to {target}",
                action = Intent.ACTION_VIEW,
                uriTemplate = "google.navigation:q={target}&mode=d",
                packageName = "com.google.android.apps.maps",
                attachLatestPhoto = false,
                feedback = "Navigating to {target}.",
            ),
            IntentRule(
                id = "rule_share_messages",
                name = "Share POV with Messages",
                triggerPattern = "share this with messages",
                action = Intent.ACTION_SEND,
                uriTemplate = null,
                packageName = "com.google.android.apps.messaging",
                attachLatestPhoto = true,
                feedback = "Sharing photo with Messages.",
            ),
            IntentRule(
                id = "rule_music_play",
                name = "YouTube Music Play",
                triggerPattern = "play {query}",
                action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
                uriTemplate = null,
                packageName = "com.google.android.apps.youtube.music",
                attachLatestPhoto = false,
                extras = mapOf(
                    "query" to "{query}",
                    "android.intent.extra.focus" to "vnd.android.cursor.item/*",
                ),
                feedback = "Playing {query} on YouTube Music.",
            ),
            IntentRule(
                id = "rule_google_search",
                name = "Google Web Search",
                triggerPattern = "search {query}",
                action = "android.intent.action.WEB_SEARCH",
                uriTemplate = "https://www.google.com/search?q={query}",
                attachLatestPhoto = false,
                feedback = "Searching Google for {query}.",
            ),
        )
    }
}
