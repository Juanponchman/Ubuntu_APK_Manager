package cn.termux.ubuntumanager.ui

/**
 * Tracks text that an IME finalizes through finishComposingText instead of commitText.
 */
internal class TerminalImeCompositionTracker(
    private val duplicateCommitWindowMillis: Long = 250L,
) {
    private data class FinishedComposition(
        val value: String,
        val timestamp: Long,
    )

    private var pendingValue: String? = null
    private var recentlyFinished: FinishedComposition? = null

    fun update(value: String?) {
        pendingValue = value?.takeIf { it.isNotEmpty() }
        recentlyFinished = null
    }

    fun finish(value: String?, timestamp: Long): String? {
        val finalized = value?.takeIf { it.isNotEmpty() } ?: pendingValue
        pendingValue = null
        if (finalized != null) {
            recentlyFinished = FinishedComposition(finalized, timestamp)
        }
        return finalized
    }

    fun shouldDispatchCommit(value: String, timestamp: Long): Boolean {
        pendingValue = null
        val finished = recentlyFinished
        recentlyFinished = null
        if (value.isEmpty()) return false
        return finished == null ||
            finished.value != value ||
            timestamp - finished.timestamp !in 0..duplicateCommitWindowMillis
    }

    fun clear() {
        pendingValue = null
        recentlyFinished = null
    }
}
