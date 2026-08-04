package cn.termux.ubuntumanager.ui

internal class TerminalActionModeGeneration {
    private var latestGeneration = 0
    private var currentGeneration: Int? = null

    fun onCreated(): Int {
        latestGeneration += 1
        currentGeneration = latestGeneration
        return latestGeneration
    }

    fun onDestroyed(generation: Int): Boolean {
        if (currentGeneration != generation) return false
        currentGeneration = null
        return true
    }

    fun isStillDestroyed(generation: Int): Boolean =
        currentGeneration == null && latestGeneration == generation
}
