package cn.termux.ubuntumanager.ui

/**
 * Returns how many terminal backspaces are needed when an IME replaces a
 * selected suffix of the shadow editor. Middle-of-buffer edits are left alone
 * because the terminal cursor is only known to match the editor at its end.
 */
internal fun terminalImeSelectedSuffixCodePointCount(
    value: String,
    selectionStart: Int,
    selectionEnd: Int,
    composingStart: Int,
    composingEnd: Int,
): Int {
    if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) return 0
    if (composingStart >= 0 || composingEnd >= 0) return 0
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    if (start !in 0..value.length || end != value.length) return 0
    return value.codePointCount(start, end)
}
