package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeDeletionBoundsTest {
    @Test
    fun maximumClearRequestOnEmptyBufferDeletesNothing() {
        assertEquals(
            TerminalImeDeletionBounds(0, 0, 0, 0),
            terminalImeDeletionBounds(
                value = "",
                selectionStart = 0,
                selectionEnd = 0,
                requestedBeforeLength = Int.MAX_VALUE,
                requestedAfterLength = Int.MAX_VALUE,
                lengthsAreCodePoints = false,
            ),
        )
    }

    @Test
    fun maximumClearRequestIsLimitedToExistingPrefix() {
        val value = "x".repeat(139)

        assertEquals(
            TerminalImeDeletionBounds(139, 0, 139, 0),
            terminalImeDeletionBounds(
                value = value,
                selectionStart = value.length,
                selectionEnd = value.length,
                requestedBeforeLength = Int.MAX_VALUE,
                requestedAfterLength = Int.MAX_VALUE,
                lengthsAreCodePoints = false,
            ),
        )
    }

    @Test
    fun deletionIsLimitedOnBothSidesOfSelection() {
        assertEquals(
            TerminalImeDeletionBounds(2, 2, 2, 2),
            terminalImeDeletionBounds(
                value = "abcdef",
                selectionStart = 2,
                selectionEnd = 4,
                requestedBeforeLength = Int.MAX_VALUE,
                requestedAfterLength = Int.MAX_VALUE,
                lengthsAreCodePoints = false,
            ),
        )
    }

    @Test
    fun negativeRequestsAreTreatedAsZero() {
        assertEquals(
            TerminalImeDeletionBounds(0, 0, 0, 0),
            terminalImeDeletionBounds(
                value = "text",
                selectionStart = 2,
                selectionEnd = 2,
                requestedBeforeLength = -1,
                requestedAfterLength = -1,
                lengthsAreCodePoints = false,
            ),
        )
    }

    @Test
    fun utf16RequestUsesCodePointsForTerminalBackspaces() {
        assertEquals(
            TerminalImeDeletionBounds(4, 0, 3, 0),
            terminalImeDeletionBounds(
                value = "A😀B",
                selectionStart = 4,
                selectionEnd = 4,
                requestedBeforeLength = Int.MAX_VALUE,
                requestedAfterLength = 0,
                lengthsAreCodePoints = false,
            ),
        )
    }

    @Test
    fun codePointRequestUsesCodePointConnectionLengths() {
        assertEquals(
            TerminalImeDeletionBounds(3, 0, 3, 0),
            terminalImeDeletionBounds(
                value = "A😀B",
                selectionStart = 4,
                selectionEnd = 4,
                requestedBeforeLength = Int.MAX_VALUE,
                requestedAfterLength = 0,
                lengthsAreCodePoints = true,
            ),
        )
    }

    @Test
    fun codePointRequestIsLimitedAfterCursor() {
        assertEquals(
            TerminalImeDeletionBounds(0, 2, 0, 2),
            terminalImeDeletionBounds(
                value = "A😀B",
                selectionStart = 1,
                selectionEnd = 1,
                requestedBeforeLength = 0,
                requestedAfterLength = Int.MAX_VALUE,
                lengthsAreCodePoints = true,
            ),
        )
    }

    @Test
    fun invalidSelectionDeletesNothing() {
        assertEquals(
            TerminalImeDeletionBounds(0, 0, 0, 0),
            terminalImeDeletionBounds(
                value = "text",
                selectionStart = -1,
                selectionEnd = -1,
                requestedBeforeLength = Int.MAX_VALUE,
                requestedAfterLength = Int.MAX_VALUE,
                lengthsAreCodePoints = false,
            ),
        )
    }
}
