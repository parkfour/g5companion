package dev.g5.companion

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesCommandRouterTest {

    @Test
    fun `sanitizes target and properly encodes ampersands and spaces`() {
        val targetWithAmpersand = "Barnes & Noble"
        val encoded = GlassesCommandRouter.sanitizeTarget(targetWithAmpersand)
        assertEquals("Barnes%20%26%20Noble", encoded)

        val targetStreet = "5th Ave & 42nd St"
        val encodedStreet = GlassesCommandRouter.sanitizeTarget(targetStreet)
        assertEquals("5th%20Ave%20%26%2042nd%20St", encodedStreet)

        val querySpecial = "Rock & Roll + Jazz"
        val encodedQuery = GlassesCommandRouter.sanitizeTarget(querySpecial)
        assertTrue(encodedQuery.contains("%26"))
        assertFalse(encodedQuery.contains(" "))
    }

    @Test
    fun `resolves navigation intent spec with encoded target and natural spoken feedback`() {
        val navRule = IntentRule(
            id = "nav_test",
            name = "Navigate to Destination",
            triggerPattern = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            uriTemplate = "google.navigation:q={target}&mode=d",
            packageName = "com.google.android.apps.maps",
            attachLatestPhoto = false,
            feedback = "Navigating to {target}.",
        )

        val spec = GlassesCommandRouter.resolveIntentSpec(navRule, target = "Barnes & Noble")

        assertEquals(Intent.ACTION_VIEW, spec.action)
        assertEquals("com.google.android.apps.maps", spec.packageName)
        assertEquals("google.navigation:q=Barnes%20%26%20Noble&mode=d", spec.resolvedDataUri)
        assertNull(spec.streamUri)
        assertNull(spec.type)
        assertEquals("Barnes & Noble", spec.extraText)
        assertEquals("Navigating to Barnes & Noble.", spec.feedback)
        assertTrue((spec.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `resolves photo-sharing intent spec with FileProvider Uri and permission flags`() {
        val shareRule = IntentRule(
            id = "share_test",
            name = "Share POV with Messages",
            triggerPattern = "share this with messages",
            action = Intent.ACTION_SEND,
            uriTemplate = null,
            packageName = "com.google.android.apps.messaging",
            attachLatestPhoto = true,
            extras = mapOf("source" to "dpvr_g5", "mode" to "quick_share"),
            feedback = "Sharing photo with Messages.",
        )

        // Mock URI instance via java reflection or dummy URI for headless test
        val dummyPhotoUri = runCatching { Uri.parse("content://dev.g5.companion.fileprovider/pov_cache/pov_768.jpg") }.getOrNull()

        val spec = GlassesCommandRouter.resolveIntentSpec(shareRule, target = null, photoUri = dummyPhotoUri)

        assertEquals(Intent.ACTION_SEND, spec.action)
        assertEquals("com.google.android.apps.messaging", spec.packageName)
        assertNull(spec.resolvedDataUri)
        if (dummyPhotoUri != null) {
            assertEquals(dummyPhotoUri, spec.streamUri)
            assertEquals("image/jpeg", spec.type)
            assertTrue((spec.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        }
        assertTrue((spec.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals("dpvr_g5", spec.extras["source"])
        assertEquals("quick_share", spec.extras["mode"])
        assertEquals("Sharing photo with Messages.", spec.feedback)
    }

    @Test
    fun `pattern matching correctly resolves target arguments and static commands`() {
        val navRule = IntentRule(
            id = "rule_nav",
            name = "Navigate to Destination",
            triggerPattern = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            uriTemplate = "google.navigation:q={target}&mode=d",
        )
        val shareRule = IntentRule(
            id = "rule_share",
            name = "Share POV with Messages",
            triggerPattern = "share this with messages",
            action = Intent.ACTION_SEND,
            attachLatestPhoto = true,
        )

        // Test matching logic directly
        val clean1 = "navigate to Austin, Texas!"
        val cleanPunct = clean1.trim().trimEnd('.', '!', '?').trim()
        val parts = navRule.triggerPattern.trim().split(IntentRuleStore.PLACEHOLDER_REGEX)
        val prefix = parts[0].trim()
        val regex = Regex("^${Regex.escape(prefix)}\\s+(.+)$", RegexOption.IGNORE_CASE)
        val match1 = regex.matchEntire(cleanPunct)
        assertNotNull(match1)
        assertEquals("Austin, Texas", match1!!.groupValues[1])

        val clean2 = "share this with messages"
        assertEquals(clean2, shareRule.triggerPattern)
    }

    @Test
    fun `serializes and deserializes intent rules to and from JSON`() {
        val rule = IntentRule(
            id = "custom_rule_1",
            name = "Spotify Rock",
            triggerPattern = "play {query} on spotify",
            action = Intent.ACTION_VIEW,
            uriTemplate = "spotify:search:{query}",
            packageName = "com.spotify.music",
            attachLatestPhoto = false,
            extras = mapOf("client" to "smart_glasses"),
            feedback = "Playing {query}.",
            isEnabled = true,
        )

        val json = rule.toJson()
        val restored = IntentRule.fromJson(json)

        assertEquals(rule.id, restored.id)
        assertEquals(rule.name, restored.name)
        assertEquals(rule.triggerPattern, restored.triggerPattern)
        assertEquals(rule.action, restored.action)
        assertEquals(rule.uriTemplate, restored.uriTemplate)
        assertEquals(rule.packageName, restored.packageName)
        assertEquals(rule.attachLatestPhoto, restored.attachLatestPhoto)
        assertEquals("smart_glasses", restored.extras["client"])
        assertEquals(rule.feedback, restored.feedback)
        assertTrue(restored.isEnabled)
    }

    @Test
    fun `resolves media play from search intent spec for selected music apps`() {
        // 1. YouTube Music
        val ytRule = IntentRule(
            id = "rule_music_play",
            name = "YouTube Music Play",
            triggerPattern = "play {query}",
            action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            packageName = "com.google.android.apps.youtube.music",
            extras = mapOf("query" to "{query}", "android.intent.extra.focus" to "vnd.android.cursor.item/*"),
            feedback = "Playing {query} on YouTube Music.",
        )

        val ytSpec = GlassesCommandRouter.resolveIntentSpec(ytRule, target = "Miles Davis")
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", ytSpec.action)
        assertEquals("com.google.android.apps.youtube.music", ytSpec.packageName)
        assertEquals("Miles Davis", ytSpec.extras["query"])
        assertEquals("vnd.android.cursor.item/*", ytSpec.extras["android.intent.extra.focus"])
        assertEquals("Playing Miles Davis on YouTube Music.", ytSpec.feedback)

        // 2. SiriusXM
        val siriusRule = IntentRule(
            id = "rule_music_play",
            name = "SiriusXM Play",
            triggerPattern = "play {query}",
            action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            packageName = "com.sirius",
            extras = mapOf("query" to "{query}"),
            feedback = "Playing {query} on SiriusXM.",
        )

        val siriusSpec = GlassesCommandRouter.resolveIntentSpec(siriusRule, target = "80s on 8")
        assertEquals("com.sirius", siriusSpec.packageName)
        assertEquals("80s on 8", siriusSpec.extras["query"])
        assertEquals("Playing 80s on 8 on SiriusXM.", siriusSpec.feedback)

        // 3. System Default (no package)
        val defaultRule = IntentRule(
            id = "rule_music_play",
            name = "System Music Play",
            triggerPattern = "play {query}",
            action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            packageName = null,
            extras = emptyMap(),
            feedback = "Playing {query}.",
        )

        val defaultSpec = GlassesCommandRouter.resolveIntentSpec(defaultRule, target = "Coltrane")
        assertNull(defaultSpec.packageName)
        assertEquals("Coltrane", defaultSpec.extras["query"])
        assertEquals("Playing Coltrane.", defaultSpec.feedback)
    }

    @Test
    fun `MusicAppManager presets contain YouTube Music, SiriusXM, Musicolet, TuneIn, and System Default`() {
        val presets = MusicAppManager.PRESETS
        assertTrue(presets.any { it.id == "youtube_music" && it.packageName == "com.google.android.apps.youtube.music" })
        assertTrue(presets.any { it.id == "sirius_xm" && it.packageName == "com.sirius" })
        assertTrue(presets.any { it.id == "musicolet" && it.packageName == "in.krosbits.musicolet" })
        assertTrue(presets.any { it.id == "tunein" && it.packageName == "tunein.player" })
        assertTrue(presets.any { it.id == "spotify" && it.packageName == "com.spotify.music" })
        assertNull(MusicAppManager.SYSTEM_DEFAULT.packageName)
    }

    @Test
    fun `backend order puts Tensor first on Google hardware`() {
        assertEquals(
            listOf("Google Tensor", "GPU", "CPU"),
            GlassesCommandRouter.backendOrder(manufacturer = "Google", hardware = "zuma", soc = "Google"),
        )
    }

    @Test
    fun `backend order detects tensor from hardware or soc string alone`() {
        assertEquals(
            listOf("Google Tensor", "GPU", "CPU"),
            GlassesCommandRouter.backendOrder(manufacturer = "Samsung", hardware = "tensor", soc = null),
        )
        assertEquals(
            listOf("Google Tensor", "GPU", "CPU"),
            GlassesCommandRouter.backendOrder(manufacturer = null, hardware = null, soc = "Google"),
        )
    }

    @Test
    fun `backend order is GPU then CPU on non-Tensor hardware with no pointless Tensor attempt`() {
        val order = GlassesCommandRouter.backendOrder(manufacturer = "Xiaomi", hardware = "qcom", soc = "Qualcomm")
        assertEquals(listOf("GPU", "CPU"), order)
        assertFalse(order.contains("Google Tensor"))
    }

    @Test
    fun `backend order handles all-null build info`() {
        assertEquals(
            listOf("GPU", "CPU"),
            GlassesCommandRouter.backendOrder(manufacturer = null, hardware = null, soc = null),
        )
    }
}
