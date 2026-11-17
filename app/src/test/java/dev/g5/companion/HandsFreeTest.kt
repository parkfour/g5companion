package dev.g5.companion

import dev.g5.companion.HandsFree.Cmd
import dev.g5.companion.HandsFree.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class HandsFreeTest {
    private val afternoon = LocalTime.of(14, 0)
    private fun p(s: String) = HandsFree.parse(s, afternoon)

    @Test fun `reply variants`() {
        assertEquals(Cmd.Reply("on my way"), p("Reply on my way."))
        assertEquals(Cmd.Reply("sounds good"), p("reply with sounds good"))
        assertEquals(Cmd.Reply("ok"), p("respond: ok"))
    }

    @Test fun `call and text`() {
        assertEquals(Cmd.Call("Alex Smith"), p("call Alex Smith"))
        assertEquals(Cmd.Text("Alex", "running late"), p("text Alex saying running late"))
        assertEquals(Cmd.Text("Mom", "I'm here"), p("send a message to Mom that I'm here"))
        assertEquals(Cmd.Text(null, "alex running late"), p("text alex running late"))
    }

    @Test fun `timers and reminders`() {
        assertEquals(Cmd.Timer(600, null), p("set a timer for 10 minutes"))
        assertEquals(Cmd.Timer(5400, null), p("timer 1 hour and 30 minutes"))
        assertEquals(Cmd.Timer(1800, null), p("set a timer for half an hour"))
        assertEquals(Cmd.Timer(1200, "call Mom"), p("remind me in 20 minutes to call Mom"))
        assertEquals(Cmd.Timer(3600, "move the car"), p("remind me to move the car in an hour"))
        assertEquals(Cmd.Alarm(17, 0, "call Mom"), p("remind me at 5 p.m. to call Mom"))
        assertEquals(Cmd.Alarm(17, 30, "call Mom"), p("remind me to call Mom at 5:30"))
        assertEquals(Cmd.Alarm(9, 0, null), p("remind me at 9 am"))
    }

    @Test fun `walking directions and missed`() {
        assertEquals(Cmd.Walk("the train station"), p("walk me to the train station"))
        assertEquals(Cmd.Walk("Times Square"), p("directions to Times Square"))
        assertEquals(Cmd.Missed, p("What did I miss?"))
        assertEquals(Cmd.Missed, p("read my notifications"))
    }

    @Test fun `ordinary questions are not commands`() {
        assertNull(p("what's the weather"))
        assertNull(p("navigate to Times Square"))
        assertNull(p("set a timer"))
    }

    @Test fun `bare hour picks the next occurrence`() {
        assertEquals(17 to 0, HandsFree.clock("5", afternoon))
        assertEquals(15 to 0, HandsFree.clock("3", afternoon))
        assertEquals(5 to 0, HandsFree.clock("5", LocalTime.of(4, 0)))
        assertEquals(0 to 0, HandsFree.clock("12 am", afternoon))
        assertEquals(12 to 0, HandsFree.clock("noon", afternoon))
        assertNull(HandsFree.clock("25", afternoon))
    }

    @Test fun `yes and no`() {
        assertTrue(HandsFree.isYes("Yes"))
        assertTrue(HandsFree.isYes("yeah send it"))
        assertTrue(HandsFree.isYes("go ahead"))
        assertFalse(HandsFree.isYes("no"))
        assertFalse(HandsFree.isYes("yes wait no"))
        assertFalse(HandsFree.isYes(null))
        assertFalse(HandsFree.isYes("what"))
    }

    @Test fun `contact ranking`() {
        val rows = listOf(
            Contact("Alexandra Jones", "1"),
            Contact("Alex Smith", "2"),
            Contact("Alex Smith", "3", mobile = true),
            Contact("Alex", "4"),
        )
        assertEquals("4", HandsFree.bestContact("alex", rows)?.number)
        assertEquals("3", HandsFree.bestContact("alex smith", rows)?.number)
        assertEquals("1", HandsFree.bestContact("alexandra", rows)?.number)
        assertNull(HandsFree.bestContact("andra", rows, strict = true))
    }

    @Test fun `split recipient uses the longest known name`() {
        val known = mapOf("alex smith" to Contact("Alex Smith", "2"), "alex" to Contact("Alex", "4"))
        val split = HandsFree.splitRecipient("alex smith running late") { known[it.lowercase()] }
        assertEquals(Contact("Alex Smith", "2") to "running late", split)
        assertNull(HandsFree.splitRecipient("bob hi") { known[it.lowercase()] })
    }

    @Test fun `describe durations`() {
        assertEquals("1 hour 30 minutes", HandsFree.describe(5400))
        assertEquals("10 minutes", HandsFree.describe(600))
    }
}
