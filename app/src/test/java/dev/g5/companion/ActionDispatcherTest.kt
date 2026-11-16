package dev.g5.companion

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherTest {

    @Test
    fun `parses open app commands`() {
        assertEquals(ParsedAction.OpenApp("spotify"), ActionDispatcher.parse("open spotify"))
        assertEquals(ParsedAction.OpenApp("google maps"), ActionDispatcher.parse("launch google maps"))
        assertEquals(ParsedAction.OpenApp("youtube"), ActionDispatcher.parse("start youtube!"))
        assertEquals(ParsedAction.OpenApp("camera"), ActionDispatcher.parse("open camera?"))
    }

    @Test
    fun `parses share photo commands`() {
        assertEquals(ParsedAction.SharePhoto("whatsapp"), ActionDispatcher.parse("share with whatsapp"))
        assertEquals(ParsedAction.SharePhoto("slack"), ActionDispatcher.parse("share to slack"))
        assertEquals(ParsedAction.SharePhoto("telegram"), ActionDispatcher.parse("share photo with telegram"))
        assertEquals(ParsedAction.SharePhoto("signal"), ActionDispatcher.parse("share the image to signal"))
        assertEquals(ParsedAction.SharePhoto(null), ActionDispatcher.parse("share it"))
        assertEquals(ParsedAction.SharePhoto(null), ActionDispatcher.parse("share photo"))
        assertEquals(ParsedAction.SharePhoto(null), ActionDispatcher.parse("share"))
    }

    @Test
    fun `parses display photo commands`() {
        assertEquals(ParsedAction.DisplayPhoto, ActionDispatcher.parse("display it"))
        assertEquals(ParsedAction.DisplayPhoto, ActionDispatcher.parse("show it"))
        assertEquals(ParsedAction.DisplayPhoto, ActionDispatcher.parse("view it"))
        assertEquals(ParsedAction.DisplayPhoto, ActionDispatcher.parse("display photo"))
        assertEquals(ParsedAction.DisplayPhoto, ActionDispatcher.parse("show the photo"))
    }

    @Test
    fun `parses user-defined custom verbs`() {
        val custom = listOf(
            CustomVerb(
                phrase = "navigate home",
                action = Intent.ACTION_VIEW,
                dataUriTemplate = "geo:0,0?q=home",
                feedback = "Navigating home.",
            ),
            CustomVerb(
                phrase = "display it",
                action = Intent.ACTION_VIEW,
                dataUriTemplate = "{photo}",
                feedback = "Displaying your POV photo.",
            ),
        )

        val action1 = ActionDispatcher.parse("navigate home", custom)
        assertNotNull(action1)
        assertTrue(action1 is ParsedAction.Custom)
        assertEquals("navigate home", (action1 as ParsedAction.Custom).verb.phrase)
        assertNull((action1 as ParsedAction.Custom).parameter)

        val action2 = ActionDispatcher.parse("display it", custom)
        assertNotNull(action2)
        assertTrue(action2 is ParsedAction.Custom)
        assertEquals("display it", (action2 as ParsedAction.Custom).verb.phrase)
        assertNull((action2 as ParsedAction.Custom).parameter)
    }

    @Test
    fun `parses parameterized custom verbs with target or query placeholders`() {
        val navVerb = CustomVerb(
            phrase = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "google.navigation:q={target}&mode=d",
            feedback = "Navigating to {target}.",
        )
        val spotifyVerb = CustomVerb(
            phrase = "play <query>",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "spotify:search:{query}",
            feedback = "Playing {query} on Spotify.",
        )
        val middleVerb = CustomVerb(
            phrase = "search for {query} on youtube",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "https://www.youtube.com/results?search_query={query}",
        )
        val verbs = listOf(navVerb, spotifyVerb, middleVerb)

        // Navigation target parameter
        val navAction = ActionDispatcher.parse("navigate to Times Square", verbs)
        assertNotNull(navAction)
        assertTrue(navAction is ParsedAction.Custom)
        val navCustom = navAction as ParsedAction.Custom
        assertEquals("navigate to {target}", navCustom.verb.phrase)
        assertEquals("Times Square", navCustom.parameter)

        // Trailing punctuation ignored
        val navPunct = ActionDispatcher.parse("navigate to Austin, Texas!", verbs)
        assertNotNull(navPunct)
        assertEquals("Austin, Texas", (navPunct as ParsedAction.Custom).parameter)

        // Music query parameter
        val musicAction = ActionDispatcher.parse("play Miles Davis", verbs)
        assertNotNull(musicAction)
        val musicCustom = musicAction as ParsedAction.Custom
        assertEquals("play <query>", musicCustom.verb.phrase)
        assertEquals("Miles Davis", musicCustom.parameter)

        // Middle placeholder with suffix
        val middleAction = ActionDispatcher.parse("search for modern jazz on youtube", verbs)
        assertNotNull(middleAction)
        assertEquals("modern jazz", (middleAction as ParsedAction.Custom).parameter)
    }

    @Test
    fun `parses prefix custom verb when placeholder is only in URI template`() {
        val prefixNav = CustomVerb(
            phrase = "navigate to",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "google.navigation:q={target}&mode=d",
            feedback = "Navigating to {target}.",
        )
        val verbs = listOf(prefixNav)

        val action = ActionDispatcher.parse("navigate to Central Park", verbs)
        assertNotNull(action)
        val custom = action as ParsedAction.Custom
        assertEquals("navigate to", custom.verb.phrase)
        assertEquals("Central Park", custom.parameter)
    }

    @Test
    fun `exact static custom verbs take precedence over parameterized matches`() {
        val staticHome = CustomVerb(
            phrase = "navigate home",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "geo:0,0?q=home",
            feedback = "Navigating home.",
        )
        val dynamicNav = CustomVerb(
            phrase = "navigate {target}",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "google.navigation:q={target}&mode=d",
        )
        val verbs = listOf(dynamicNav, staticHome)

        val homeAction = ActionDispatcher.parse("navigate home", verbs)
        assertNotNull(homeAction)
        val homeCustom = homeAction as ParsedAction.Custom
        assertEquals("navigate home", homeCustom.verb.phrase)
        assertNull(homeCustom.parameter)

        val workAction = ActionDispatcher.parse("navigate downtown", verbs)
        assertNotNull(workAction)
        val workCustom = workAction as ParsedAction.Custom
        assertEquals("navigate {target}", workCustom.verb.phrase)
        assertEquals("downtown", workCustom.parameter)
    }

    @Test
    fun `provides sample parameter for parameterized verbs`() {
        val navVerb = CustomVerb(
            phrase = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "google.navigation:q={target}&mode=d",
        )
        val spotifyVerb = CustomVerb(
            phrase = "play {query}",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "spotify:search:{query}",
        )
        val staticVerb = CustomVerb(
            phrase = "display it",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "{photo}",
        )

        assertEquals("Times Square", ActionDispatcher.sampleParameterFor(navVerb))
        assertEquals("Miles Davis", ActionDispatcher.sampleParameterFor(spotifyVerb))
        assertNull(ActionDispatcher.sampleParameterFor(staticVerb))
    }

    @Test
    fun `ignores non-action conversational questions`() {
        assertNull(ActionDispatcher.parse("What am I looking at?"))
        assertNull(ActionDispatcher.parse("How is the weather in Chicago?"))
        assertNull(ActionDispatcher.parse("Can you tell me what time it is?"))
        assertNull(ActionDispatcher.parse("Who is the author of this book?"))
    }

    @Test
    fun `parses LLM action directive line`() {
        val reply1 = """ACTION: open spotify
Opening Spotify for you."""
        val directive1 = ActionDispatcher.parseActionDirective(reply1)
        assertNotNull(directive1)
        assertEquals(ParsedAction.OpenApp("spotify"), directive1!!.first)
        assertEquals("Opening Spotify for you.", directive1.second)

        val reply2 = """ACTION: share whatsapp
Sharing the photo with WhatsApp."""
        val directive2 = ActionDispatcher.parseActionDirective(reply2)
        assertNotNull(directive2)
        assertEquals(ParsedAction.SharePhoto("whatsapp"), directive2!!.first)
        assertEquals("Sharing the photo with WhatsApp.", directive2.second)

        val reply3 = "I see a green cup sitting on a wooden desk."
        assertNull(ActionDispatcher.parseActionDirective(reply3))

        val navVerb = CustomVerb(
            phrase = "navigate to {target}",
            action = Intent.ACTION_VIEW,
            dataUriTemplate = "google.navigation:q={target}&mode=d",
            feedback = "Navigating to {target}.",
        )
        val reply4 = """ACTION: navigate to Empire State Building
Navigating to Empire State Building."""
        val directive4 = ActionDispatcher.parseActionDirective(reply4, listOf(navVerb))
        assertNotNull(directive4)
        val (action4, text4) = directive4!!
        assertTrue(action4 is ParsedAction.Custom)
        assertEquals("Empire State Building", (action4 as ParsedAction.Custom).parameter)
        assertEquals("Navigating to Empire State Building.", text4)
    }
}
