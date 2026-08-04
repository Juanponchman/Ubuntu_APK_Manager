package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionExitGestureTest {
    @Test
    fun shortTapOutsideSelectionRequestsInputResume() {
        val gesture = TerminalSelectionExitGesture(touchSlop = 10f)

        gesture.start(x = 20f, y = 30f, outsideSelection = true)

        assertTrue(gesture.wantsInputResume)
        assertTrue(gesture.finish(x = 23f, y = 34f))
        assertFalse(gesture.wantsInputResume)
    }

    @Test
    fun draggingOutsideSelectionDoesNotExit() {
        val gesture = TerminalSelectionExitGesture(touchSlop = 10f)

        gesture.start(x = 20f, y = 30f, outsideSelection = true)
        gesture.move(x = 45f, y = 30f)

        assertFalse(gesture.wantsInputResume)
        assertFalse(gesture.finish(x = 45f, y = 30f))
    }

    @Test
    fun tappingInsideSelectionDoesNotExit() {
        val gesture = TerminalSelectionExitGesture(touchSlop = 10f)

        gesture.start(x = 20f, y = 30f, outsideSelection = false)

        assertFalse(gesture.finish(x = 20f, y = 30f))
    }

    @Test
    fun canceledGestureDoesNotResumeInput() {
        val gesture = TerminalSelectionExitGesture(touchSlop = 10f)

        gesture.start(x = 20f, y = 30f, outsideSelection = true)
        gesture.cancel()

        assertFalse(gesture.wantsInputResume)
        assertFalse(gesture.finish(x = 20f, y = 30f))
    }
}
