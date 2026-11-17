package dev.g5.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationReaderTest {
    @Test fun appTitleAndText() = assertEquals("Messages: Alex. Running late", NotificationReader.phrase("Messages", "Alex", "Running late"))
    @Test fun textSameAsTitleSaidOnce() = assertEquals("Gmail: New mail", NotificationReader.phrase("Gmail", "New mail", "New mail"))
    @Test fun noAppLabel() = assertEquals("Hi", NotificationReader.phrase(null, null, " Hi "))
    @Test fun emptyIsNull() = assertNull(NotificationReader.phrase("X", " ", null))
    @Test fun capped() = assertEquals(300, NotificationReader.phrase("A", "t", "x".repeat(1000))?.length)
}
