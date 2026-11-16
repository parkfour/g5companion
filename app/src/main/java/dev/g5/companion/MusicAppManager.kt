package dev.g5.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

/**
 * Represents a selectable music streaming service or player.
 */
data class MusicAppOption(
    val id: String,
    val name: String,
    val packageName: String?,
    val action: String = MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH,
    val uriTemplate: String? = null,
    val extras: Map<String, String> = mapOf(
        "query" to "{query}",
        "android.intent.extra.focus" to "vnd.android.cursor.item/*",
    ),
    val feedback: String = if (packageName != null) "Playing {query} on $name." else "Playing {query}.",
    val isInstalled: Boolean = false,
)

/**
 * Manages music app presets, detection of installed media players, and updating
 * the active "play {query}" intent rule across the app.
 */
object MusicAppManager {
    val SYSTEM_DEFAULT = MusicAppOption(
        id = "system_default",
        name = "System Default (Any Player)",
        packageName = null,
        feedback = "Playing {query}.",
        isInstalled = true,
    )

    val PRESETS = listOf(
        MusicAppOption(
            id = "youtube_music",
            name = "YouTube Music",
            packageName = "com.google.android.apps.youtube.music",
            feedback = "Playing {query} on YouTube Music.",
        ),
        MusicAppOption(
            id = "sirius_xm",
            name = "SiriusXM",
            packageName = "com.sirius",
            feedback = "Playing {query} on SiriusXM.",
        ),
        MusicAppOption(
            id = "musicolet",
            name = "Musicolet",
            packageName = "in.krosbits.musicolet",
            feedback = "Playing {query} on Musicolet.",
        ),
        MusicAppOption(
            id = "tunein",
            name = "TuneIn Radio",
            packageName = "tunein.player",
            feedback = "Playing {query} on TuneIn.",
        ),
        MusicAppOption(
            id = "vlc",
            name = "VLC",
            packageName = "org.videolan.vlc",
            feedback = "Playing {query} on VLC.",
        ),
        MusicAppOption(
            id = "spotify",
            name = "Spotify",
            packageName = "com.spotify.music",
            action = Intent.ACTION_VIEW,
            uriTemplate = "spotify:search:{query}",
            extras = emptyMap(),
            feedback = "Playing {query} on Spotify.",
        ),
        MusicAppOption(
            id = "apple_music",
            name = "Apple Music",
            packageName = "com.apple.android.music",
            feedback = "Playing {query} on Apple Music.",
        ),
        MusicAppOption(
            id = "amazon_music",
            name = "Amazon Music",
            packageName = "com.amazon.mp3",
            feedback = "Playing {query} on Amazon Music.",
        ),
        MusicAppOption(
            id = "tidal",
            name = "Tidal",
            packageName = "com.aspiro.tidal",
            feedback = "Playing {query} on Tidal.",
        ),
        MusicAppOption(
            id = "pandora",
            name = "Pandora",
            packageName = "com.pandora.android",
            feedback = "Playing {query} on Pandora.",
        ),
        MusicAppOption(
            id = "soundcloud",
            name = "SoundCloud",
            packageName = "com.soundcloud.android",
            feedback = "Playing {query} on SoundCloud.",
        ),
    )

    /**
     * Resolves all available music players on the device, highlighting installed ones.
     */
    fun getAvailableMusicApps(context: Context): List<MusicAppOption> {
        val pm = context.packageManager
        val installedPackages = mutableSetOf<String>()

        val resolvedPresets = PRESETS.map { preset ->
            val installed = preset.packageName?.let { pkg -> isAppInstalled(pm, pkg) } ?: true
            if (installed && preset.packageName != null) {
                installedPackages.add(preset.packageName)
            }
            preset.copy(isInstalled = installed)
        }

        // Dynamically discover any other installed media player responding to MEDIA_PLAY_FROM_SEARCH
        val dynamicApps = mutableListOf<MusicAppOption>()
        val mediaSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        val resolveList = pm.queryIntentActivities(mediaSearchIntent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolveList) {
            val pkg = info.activityInfo.packageName
            if (pkg !in installedPackages &&
                pkg != context.packageName &&
                !pkg.contains("google.android.videos")
            ) {
                val label = info.loadLabel(pm)?.toString() ?: pkg
                dynamicApps.add(
                    MusicAppOption(
                        id = "pkg_$pkg",
                        name = label,
                        packageName = pkg,
                        isInstalled = true,
                        feedback = "Playing {query} on $label.",
                    ),
                )
                installedPackages.add(pkg)
            }
        }

        // Return System Default first, then installed apps sorted by name, then uninstalled apps
        val nonDefault = (resolvedPresets + dynamicApps)
        val installedList = nonDefault.filter { it.isInstalled }.sortedBy { it.name }
        val uninstalledList = nonDefault.filter { !it.isInstalled }.sortedBy { it.name }

        return listOf(SYSTEM_DEFAULT) + installedList + uninstalledList
    }

    fun isAppInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gets the currently selected music app option, defaulting to YouTube Music if installed,
     * or the first installed player.
     */
    fun getSelectedMusicApp(context: Context, prefs: Prefs): MusicAppOption {
        val available = getAvailableMusicApps(context)
        val savedId = prefs.selectedMusicAppId
        val found = available.firstOrNull { it.id == savedId }
        if (found != null) return found

        // Auto-select YouTube Music if available and installed, or first installed player
        val yt = available.firstOrNull { it.id == "youtube_music" && it.isInstalled }
        if (yt != null) return yt

        return available.firstOrNull { it.isInstalled && it.packageName != null } ?: SYSTEM_DEFAULT
    }

    /**
     * Applies the music app option to both [IntentRuleStore] and [Prefs].
     */
    fun selectMusicApp(
        context: Context,
        option: MusicAppOption,
        ruleStore: IntentRuleStore,
        prefs: Prefs,
    ) {
        prefs.selectedMusicAppId = option.id
        prefs.selectedMusicAppName = option.name
        prefs.selectedMusicAppPackage = option.packageName

        val musicRule = IntentRule(
            id = "rule_music_play",
            name = "${option.name} Play",
            triggerPattern = "play {query}",
            action = option.action,
            uriTemplate = option.uriTemplate,
            packageName = option.packageName,
            attachLatestPhoto = false,
            extras = option.extras,
            feedback = option.feedback,
            isEnabled = true,
        )

        // Remove stale/duplicate "play {query}" rules (legacy Spotify id, or any other
        // id with the same trigger), then upsert the selected music rule. deleteRule and
        // saveRule both persist; the previous local-list approach discarded its result.
        ruleStore.getAll()
            .filter { (it.triggerPattern == "play {query}" || it.id == "rule_spotify_play") && it.id != "rule_music_play" }
            .forEach { ruleStore.deleteRule(it.id) }
        ruleStore.saveRule(musicRule)

        // Sync legacy CustomVerb in Prefs
        val customVerbs = prefs.customVerbs().toMutableList()
        customVerbs.removeAll { it.id == "default_music_play" || it.phrase == "play {query}" }
        customVerbs.add(
            CustomVerb(
                id = "default_music_play",
                phrase = "play {query}",
                action = option.action,
                packageName = option.packageName,
                dataUriTemplate = option.uriTemplate,
                extras = option.extras,
                feedback = option.feedback,
            ),
        )
        prefs.setCustomVerbs(customVerbs)
    }
}
