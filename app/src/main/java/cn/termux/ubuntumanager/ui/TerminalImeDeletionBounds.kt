package cn.termux.ubuntumanager.ui

internal data class TerminalImeDeletionBounds(
    val connectionBeforeLength: Int,
    val connectionAfterLength: Int,
    val terminalBeforeCodePoints: Int,
    val terminalAfterCodePoints: Int,
)

/**
 * Bounds an IME deletion request to the text that actually exists around the
 * editor selection. Some IMEs use Int.MAX_VALUE to mean "clear everything".
 */
internal fun terminalImeDeletionBounds(
    value: String,
    selectionStart: Int,
    selectionEnd: Int,
    requestedBeforeLength: Int,
    requestedAfterLength: Int,
    lengthsAreCodePoints: Boolean,
): TerminalImeDeletionBounds {
    if (
        selectionStart !in 0..value.length ||
        selectionEnd !in 0..value.length
    ) {
        return TerminalImeDeletionBounds(0, 0, 0, 0)
    }
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    val requestedBefore = requestedBeforeLength.coerceAtLeast(0)
    val requestedAfter = requestedAfterLength.coerceAtLeast(0)

    if (lengthsAreCodePoints) {
        val availableBefore = value.codePointCount(0, start)
        val availableAfter = value.codePointCount(end, value.length)
        val safeBefore = requestedBefore.coerceAtMost(availableBefore)
        val safeAfter = requestedAfter.coerceAtMost(availableAfter)
        return TerminalImeDeletionBounds(
            connectionBeforeLength = safeBefore,
            connectionAfterLength = safeAfter,
            terminalBeforeCodePoints = safeBefore,
            terminalAfterCodePoints = safeAfter,
        )
    }

    val safeBefore = requestedBefore.coerceAtMost(start)
    val safeAfter = requestedAfter.coerceAtMost(value.length - end)
    return TerminalImeDeletionBounds(
        connectionBeforeLength = safeBefore,
        connectionAfterLength = safeAfter,
        terminalBeforeCodePoints = value.codePointCount(start - safeBefore, start),
        terminalAfterCodePoints = value.codePointCount(end, end + safeAfter),
    )
}
