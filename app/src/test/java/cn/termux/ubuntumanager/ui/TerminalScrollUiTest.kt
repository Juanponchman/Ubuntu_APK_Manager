package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollUiTest {
    @Test
    fun returnToLatestIsHiddenAtBottom() {
        assertFalse(shouldShowReturnToLatest(atBottom = true))
    }

    @Test
    fun returnToLatestIsShownAboveBottom() {
        assertTrue(shouldShowReturnToLatest(atBottom = false))
    }
}
