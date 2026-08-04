package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionLifecycleTest {
    @Test
    fun actionModeDestroyDuringInitializationDoesNotExit() {
        val lifecycle = TerminalSelectionLifecycle()

        assertNull(lifecycle.onActionModeDestroyed(resumeInput = false))
        assertTrue(lifecycle.isInitializing)
    }

    @Test
    fun actionModeDestroyAfterActivationExitsOnce() {
        val lifecycle = TerminalSelectionLifecycle()

        assertTrue(lifecycle.activate())
        assertEquals(true, lifecycle.onActionModeDestroyed(resumeInput = true))
        assertNull(lifecycle.onActionModeDestroyed(resumeInput = true))
        assertFalse(lifecycle.isActive)
    }

    @Test
    fun explicitCancelCanExitDuringInitialization() {
        val lifecycle = TerminalSelectionLifecycle()

        assertEquals(false, lifecycle.requestExit(resumeInput = false))
        assertNull(lifecycle.requestExit(resumeInput = false))
    }

    @Test
    fun releasedSelectionIgnoresLateCallbacks() {
        val lifecycle = TerminalSelectionLifecycle()

        lifecycle.activate()
        lifecycle.release()

        assertNull(lifecycle.onActionModeDestroyed(resumeInput = true))
        assertNull(lifecycle.requestExit(resumeInput = true))
    }
}
