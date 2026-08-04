package cn.termux.ubuntumanager.ui

internal class TerminalSelectionExitGesture(
    private val touchSlop: Float,
) {
    private var downX = 0f
    private var downY = 0f
    private var pendingOutsideTap = false

    val wantsInputResume: Boolean
        get() = pendingOutsideTap

    fun start(x: Float, y: Float, outsideSelection: Boolean) {
        downX = x
        downY = y
        pendingOutsideTap = outsideSelection
    }

    fun move(x: Float, y: Float) {
        if (!pendingOutsideTap) return
        val deltaX = x - downX
        val deltaY = y - downY
        if (deltaX * deltaX + deltaY * deltaY >= touchSlop * touchSlop) {
            pendingOutsideTap = false
        }
    }

    fun finish(x: Float, y: Float): Boolean {
        move(x, y)
        val shouldExit = pendingOutsideTap
        pendingOutsideTap = false
        return shouldExit
    }

    fun cancel() {
        pendingOutsideTap = false
    }
}
