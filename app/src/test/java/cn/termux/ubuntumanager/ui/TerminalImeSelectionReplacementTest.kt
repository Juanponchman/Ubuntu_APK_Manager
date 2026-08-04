package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeSelectionReplacementTest {
    @Test
    fun fullSelectionDeletesPreviouslyCommittedText() {
        assertEquals(
            15,
            terminalImeSelectedSuffixCodePointCount(
                value = "123456789012345",
                selectionStart = 0,
                selectionEnd = 15,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }

    @Test
    fun trailingSelectionCanBeReplaced() {
        assertEquals(
            4,
            terminalImeSelectedSuffixCodePointCount(
                value = "prefix-tail",
                selectionStart = 7,
                selectionEnd = 11,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }

    @Test
    fun reversedTrailingSelectionIsSupported() {
        assertEquals(
            4,
            terminalImeSelectedSuffixCodePointCount(
                value = "prefix-tail",
                selectionStart = 11,
                selectionEnd = 7,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }

    @Test
    fun middleSelectionIsNotTranslated() {
        assertEquals(
            0,
            terminalImeSelectedSuffixCodePointCount(
                value = "prefix-tail-suffix",
                selectionStart = 7,
                selectionEnd = 11,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }

    @Test
    fun cursorWithoutSelectionDoesNotDelete() {
        assertEquals(
            0,
            terminalImeSelectedSuffixCodePointCount(
                value = "text",
                selectionStart = 4,
                selectionEnd = 4,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }

    @Test
    fun composingSelectionDoesNotDeleteUnsentText() {
        assertEquals(
            0,
            terminalImeSelectedSuffixCodePointCount(
                value = "语音输入",
                selectionStart = 0,
                selectionEnd = 4,
                composingStart = 0,
                composingEnd = 4,
            ),
        )
    }

    @Test
    fun supplementaryCharactersCountAsSingleBackspaces() {
        assertEquals(
            2,
            terminalImeSelectedSuffixCodePointCount(
                value = "A😀B",
                selectionStart = 1,
                selectionEnd = 4,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }

    @Test
    fun invalidSelectionIsIgnored() {
        assertEquals(
            0,
            terminalImeSelectedSuffixCodePointCount(
                value = "text",
                selectionStart = 0,
                selectionEnd = 5,
                composingStart = -1,
                composingEnd = -1,
            ),
        )
    }
}
