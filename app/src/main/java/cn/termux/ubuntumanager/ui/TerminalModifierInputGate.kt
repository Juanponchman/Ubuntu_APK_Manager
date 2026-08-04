package cn.termux.ubuntumanager.ui

/**
 * Keeps callbacks from one IME connection from emitting the same modifier key twice.
 * A fresh gate is created for every InputConnection.
 */
internal class TerminalModifierInputGate {
    private var consumed = false

    fun intercepts(modifierActive: Boolean): Boolean = modifierActive || consumed

    fun consume(modifierActive: Boolean, value: String): String? {
        if (!modifierActive || consumed || value.isEmpty()) return null
        consumed = true
        return value
    }
}
