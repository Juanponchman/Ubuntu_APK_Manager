package cn.termux.ubuntumanager

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun backsOffAndCapsForegroundReconnectAttempts() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(0))
        assertEquals(2_000L, ReconnectBackoff.delayMillis(1))
        assertEquals(5_000L, ReconnectBackoff.delayMillis(2))
        assertEquals(10_000L, ReconnectBackoff.delayMillis(3))
        assertEquals(10_000L, ReconnectBackoff.delayMillis(20))
    }

    @Test
    fun treatsNegativeAttemptCountAsFirstAttempt() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(-1))
    }
}
