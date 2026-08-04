package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModifierInputGateTest {
    @Test
    fun inactiveModifierDoesNotInterceptText() {
        val gate = TerminalModifierInputGate()

        assertFalse(gate.intercepts(modifierActive = false))
        assertNull(gate.consume(modifierActive = false, value = "c"))
    }

    @Test
    fun composingCharacterIsConsumedOnlyOncePerConnection() {
        val gate = TerminalModifierInputGate()

        assertEquals("c", gate.consume(modifierActive = true, value = "c"))
        assertTrue(gate.intercepts(modifierActive = true))
        assertNull(gate.consume(modifierActive = true, value = "c"))
    }

    @Test
    fun lateCommitRemainsInterceptedAfterOneShotModifierTurnsOff() {
        val gate = TerminalModifierInputGate()

        assertEquals("d", gate.consume(modifierActive = true, value = "d"))
        assertTrue(gate.intercepts(modifierActive = false))
        assertNull(gate.consume(modifierActive = false, value = "d"))
    }

    @Test
    fun newConnectionCanConsumeNextLockedModifierKey() {
        val firstConnection = TerminalModifierInputGate()
        val nextConnection = TerminalModifierInputGate()

        assertEquals("a", firstConnection.consume(modifierActive = true, value = "a"))
        assertEquals("b", nextConnection.consume(modifierActive = true, value = "b"))
    }
}
