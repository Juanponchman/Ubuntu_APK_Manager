package cn.termux.ubuntumanager.command

import cn.termux.ubuntumanager.model.TerminalKeyStroke
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.model.UserCommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {
    @Test
    fun ctrlCIsEncodedAsInterrupt() {
        assertEquals("\u0003", TerminalKeys.applyModifiers("c", ctrl = true, alt = false))
    }

    @Test
    fun ctrlAltCAddsEscapePrefix() {
        assertEquals("\u001b\u0003", TerminalKeys.applyModifiers("c", ctrl = true, alt = true))
    }

    @Test
    fun keyStrokeValidationRejectsUnknownKeys() {
        assertTrue(TerminalKeys.isValid(TerminalKeyStroke("c", ctrl = true)))
        assertFalse(TerminalKeys.isValid(TerminalKeyStroke("unknown")))
    }

    @Test
    fun keyActionSummaryUsesReadableCombination() {
        val command = UserCommand(
            id = "ctrl_c",
            title = "停止任务",
            script = "",
            type = UserCommandType.KEY,
            keyStroke = TerminalKeyStroke("c", ctrl = true),
        )

        assertEquals("Ctrl + C", command.actionSummary())
    }
}
