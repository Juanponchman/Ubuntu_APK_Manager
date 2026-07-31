package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalShortcutLayoutTest {
    @Test
    fun landscapeFitsCurrentShortcutSetOnOneRow() {
        assertEquals(13, terminalShortcutColumnCount(851f, 13))
    }

    @Test
    fun portraitWrapsAccordingToAvailableWidth() {
        assertEquals(7, terminalShortcutColumnCount(393f, 13))
    }

    @Test
    fun emptyConfigurationStillProducesSafeChunkSize() {
        assertEquals(1, terminalShortcutColumnCount(0f, 0))
    }
}
