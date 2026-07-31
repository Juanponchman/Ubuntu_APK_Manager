package cn.termux.ubuntumanager.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalShortcutsTest {
    @Test
    fun backspaceIsRequiredAndPlacedNextToEnter() {
        val ids = TerminalShortcuts.defaults.map { it.id }

        assertTrue(TerminalShortcuts.BACKSPACE in TerminalShortcuts.requiredIds)
        assertEquals(
            ids.indexOf(TerminalShortcuts.ENTER) - 1,
            ids.indexOf(TerminalShortcuts.BACKSPACE),
        )
    }

    @Test
    fun forwardDeleteIsAvailableWithoutCrowdingTheDefaultBar() {
        assertTrue(TerminalShortcuts.isValidBuiltinAction(TerminalShortcuts.DELETE))
        assertFalse(TerminalShortcuts.defaults.any { it.id == TerminalShortcuts.DELETE })
    }
}
