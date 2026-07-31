package cn.termux.ubuntumanager.command

import cn.termux.ubuntumanager.model.TerminalShortcutAction
import cn.termux.ubuntumanager.model.TerminalShortcutPreference

object TerminalShortcuts {
    const val CTRL = "ctrl"
    const val ALT = "alt"
    const val ESC = "esc"
    const val TAB = "tab"
    const val BACKSPACE = "backspace"
    const val DELETE = "delete"
    const val ENTER = "enter"
    const val LEFT = "left"
    const val UP = "up"
    const val DOWN = "down"
    const val RIGHT = "right"
    const val COMMANDS = "commands"
    const val LATEST = "latest"

    val builtinActions = listOf(
        CTRL,
        ALT,
        ESC,
        TAB,
        BACKSPACE,
        DELETE,
        ENTER,
        LEFT,
        UP,
        DOWN,
        RIGHT,
        COMMANDS,
        LATEST,
    )

    val defaults = builtinActions.filterNot { it == DELETE }.map { action ->
        TerminalShortcutPreference(
            id = action,
            label = builtinLabel(action),
            action = TerminalShortcutAction.BUILTIN,
            payload = action,
        )
    }

    val requiredIds = defaults.map { it.id }.toSet()

    fun builtinLabel(id: String): String = when (id) {
        CTRL -> "Ctrl"
        ALT -> "Alt"
        ESC -> "Esc"
        TAB -> "Tab"
        BACKSPACE -> "⌫"
        DELETE -> "Del"
        ENTER -> "回车"
        LEFT -> "←"
        UP -> "↑"
        DOWN -> "↓"
        RIGHT -> "→"
        COMMANDS -> "指令"
        LATEST -> "回到最新"
        else -> id
    }

    fun isValidBuiltinAction(action: String): Boolean = action in builtinActions
}
