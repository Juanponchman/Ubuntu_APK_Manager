package cn.termux.ubuntumanager.command

import cn.termux.ubuntumanager.model.TerminalKeyStroke
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.model.UserCommandType

data class TerminalKeyOption(
    val id: String,
    val label: String,
    val group: String,
    val text: String? = null,
    val javascriptKey: String = text.orEmpty(),
    val javascriptCode: String = "",
    val keyCode: Int = 0,
)

object TerminalKeys {
    const val ENTER = "enter"
    const val ESCAPE = "escape"
    const val TAB = "tab"
    const val SPACE = "space"
    const val BACKSPACE = "backspace"
    const val DELETE = "delete"
    const val LEFT = "arrow_left"
    const val UP = "arrow_up"
    const val DOWN = "arrow_down"
    const val RIGHT = "arrow_right"
    const val HOME = "home"
    const val END = "end"
    const val PAGE_UP = "page_up"
    const val PAGE_DOWN = "page_down"

    private val letterOptions = ('A'..'Z').map { letter ->
        TerminalKeyOption(
            id = letter.lowercase(),
            label = letter.toString(),
            group = "字母",
            text = letter.lowercase(),
            javascriptKey = letter.lowercase(),
            javascriptCode = "Key$letter",
            keyCode = letter.code,
        )
    }

    private val digitOptions = ('0'..'9').map { digit ->
        TerminalKeyOption(
            id = "digit_$digit",
            label = digit.toString(),
            group = "数字",
            text = digit.toString(),
            javascriptKey = digit.toString(),
            javascriptCode = "Digit$digit",
            keyCode = digit.code,
        )
    }

    private val symbolOptions = listOf(
        TerminalKeyOption("at", "@", "符号", "@", "@", "Digit2", 50),
        TerminalKeyOption("bracket_left", "[", "符号", "[", "[", "BracketLeft", 219),
        TerminalKeyOption("backslash", "\\", "符号", "\\", "\\", "Backslash", 220),
        TerminalKeyOption("bracket_right", "]", "符号", "]", "]", "BracketRight", 221),
        TerminalKeyOption("caret", "^", "符号", "^", "^", "Digit6", 54),
        TerminalKeyOption("underscore", "_", "符号", "_", "_", "Minus", 189),
        TerminalKeyOption("question", "?", "符号", "?", "?", "Slash", 191),
    )

    private val specialOptions = listOf(
        TerminalKeyOption(ENTER, "Enter", "特殊按键", javascriptKey = "Enter", javascriptCode = "Enter", keyCode = 13),
        TerminalKeyOption(ESCAPE, "Esc", "特殊按键", javascriptKey = "Escape", javascriptCode = "Escape", keyCode = 27),
        TerminalKeyOption(TAB, "Tab", "特殊按键", javascriptKey = "Tab", javascriptCode = "Tab", keyCode = 9),
        TerminalKeyOption(SPACE, "Space", "特殊按键", " ", " ", "Space", 32),
        TerminalKeyOption(BACKSPACE, "Backspace", "特殊按键", javascriptKey = "Backspace", javascriptCode = "Backspace", keyCode = 8),
        TerminalKeyOption(DELETE, "Delete", "特殊按键", javascriptKey = "Delete", javascriptCode = "Delete", keyCode = 46),
        TerminalKeyOption(LEFT, "←", "导航", javascriptKey = "ArrowLeft", javascriptCode = "ArrowLeft", keyCode = 37),
        TerminalKeyOption(UP, "↑", "导航", javascriptKey = "ArrowUp", javascriptCode = "ArrowUp", keyCode = 38),
        TerminalKeyOption(DOWN, "↓", "导航", javascriptKey = "ArrowDown", javascriptCode = "ArrowDown", keyCode = 40),
        TerminalKeyOption(RIGHT, "→", "导航", javascriptKey = "ArrowRight", javascriptCode = "ArrowRight", keyCode = 39),
        TerminalKeyOption(HOME, "Home", "导航", javascriptKey = "Home", javascriptCode = "Home", keyCode = 36),
        TerminalKeyOption(END, "End", "导航", javascriptKey = "End", javascriptCode = "End", keyCode = 35),
        TerminalKeyOption(PAGE_UP, "Page Up", "导航", javascriptKey = "PageUp", javascriptCode = "PageUp", keyCode = 33),
        TerminalKeyOption(PAGE_DOWN, "Page Down", "导航", javascriptKey = "PageDown", javascriptCode = "PageDown", keyCode = 34),
    )

    val options: List<TerminalKeyOption> =
        letterOptions + digitOptions + symbolOptions + specialOptions

    fun option(id: String): TerminalKeyOption? = options.firstOrNull { it.id == id }

    fun isValid(stroke: TerminalKeyStroke?): Boolean =
        stroke != null && option(stroke.key) != null

    fun displayName(stroke: TerminalKeyStroke?): String {
        if (stroke == null) return "未设置按键"
        val keyLabel = option(stroke.key)?.label ?: stroke.key
        return buildList {
            if (stroke.ctrl) add("Ctrl")
            if (stroke.alt) add("Alt")
            if (stroke.shift) add("Shift")
            add(keyLabel)
        }.joinToString(" + ")
    }

    fun printableText(stroke: TerminalKeyStroke): String? {
        val text = option(stroke.key)?.text ?: return null
        return if (stroke.shift && text.length == 1 && text[0].isLetter()) {
            text.uppercase()
        } else {
            text
        }
    }

    fun applyModifiers(value: String, ctrl: Boolean, alt: Boolean): String {
        var output = if (ctrl) value.asControlSequence() else value
        if (alt) output = "\u001b$output"
        return output
    }

    private fun String.asControlSequence(): String {
        if (isEmpty()) return this
        val character = first()
        val code = when (character.uppercaseChar()) {
            in 'A'..'Z' -> character.uppercaseChar().code - 'A'.code + 1
            '@', ' ' -> 0
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_' -> 31
            '?' -> 127
            else -> return this
        }
        return code.toChar().toString()
    }
}

fun UserCommand.actionSummary(): String = when (type) {
    UserCommandType.COMMAND -> script.replace('\n', ' ')
    UserCommandType.KEY -> TerminalKeys.displayName(keyStroke)
}
