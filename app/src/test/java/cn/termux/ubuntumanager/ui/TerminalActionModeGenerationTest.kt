package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalActionModeGenerationTest {
    @Test
    fun destroyedModeWithoutReplacementRemainsFinal() {
        val generations = TerminalActionModeGeneration()

        val first = generations.onCreated()
        assertTrue(generations.onDestroyed(first))

        assertTrue(generations.isStillDestroyed(first))
    }

    @Test
    fun replacementModeCancelsPreviousDestroy() {
        val generations = TerminalActionModeGeneration()

        val first = generations.onCreated()
        generations.onDestroyed(first)
        val replacement = generations.onCreated()

        assertFalse(generations.isStillDestroyed(first))
        assertFalse(generations.isStillDestroyed(replacement))
    }

    @Test
    fun staleDestroyCannotClearReplacement() {
        val generations = TerminalActionModeGeneration()

        val first = generations.onCreated()
        val replacement = generations.onCreated()

        assertFalse(generations.onDestroyed(first))
        assertTrue(generations.onDestroyed(replacement))
        assertTrue(generations.isStillDestroyed(replacement))
    }
}
