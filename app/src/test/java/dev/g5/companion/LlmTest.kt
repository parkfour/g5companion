package dev.g5.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmTest {
    @Test
    fun `speakable strips markdown links urls and citations`() {
        assertEquals(
            "It's 54°F and cloudy in Chicago. Rain is likely after 3 pm.",
            Llm.speakable("**It's 54°F** and cloudy in Chicago ([weather.com](https://weather.com/x)). [Rain](https://a.b) is likely after 3 pm [1]."),
        )
        assertEquals("First\nSecond", Llm.speakable("- First\n2. Second"))
        assertEquals("See this.", Llm.speakable("See https://example.com/page this."))
    }
}
