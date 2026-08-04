package cn.termux.ubuntumanager.ui

internal class TerminalSelectionLifecycle {
    private var phase = Phase.INITIALIZING

    val isInitializing: Boolean
        get() = phase == Phase.INITIALIZING

    val isActive: Boolean
        get() = phase == Phase.ACTIVE

    fun activate(): Boolean {
        if (phase != Phase.INITIALIZING) return false
        phase = Phase.ACTIVE
        return true
    }

    fun onActionModeDestroyed(resumeInput: Boolean): Boolean? {
        if (phase != Phase.ACTIVE) return null
        phase = Phase.EXITING
        return resumeInput
    }

    fun requestExit(resumeInput: Boolean): Boolean? {
        if (phase == Phase.EXITING || phase == Phase.RELEASED) return null
        phase = Phase.EXITING
        return resumeInput
    }

    fun release() {
        phase = Phase.RELEASED
    }

    private enum class Phase {
        INITIALIZING,
        ACTIVE,
        EXITING,
        RELEASED,
    }
}
