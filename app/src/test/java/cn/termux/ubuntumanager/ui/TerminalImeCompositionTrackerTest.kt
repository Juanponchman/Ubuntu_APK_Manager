package cn.termux.ubuntumanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalImeCompositionTrackerTest {
    @Test
    fun finishEmitsOnlyLatestVoiceRevision() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("测")
        tracker.update("测试")
        tracker.update("测试完成")

        assertEquals("测试完成", tracker.finish("测试完成", timestamp = 1_000L))
        assertNull(tracker.finish(null, timestamp = 1_001L))
    }

    @Test
    fun commitClearsPendingComposition() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("ni")

        assertTrue(tracker.shouldDispatchCommit("你", timestamp = 1_000L))
        assertNull(tracker.finish(null, timestamp = 1_001L))
    }

    @Test
    fun equalLateCommitAfterFinishIsSuppressed() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("语音")
        assertEquals("语音", tracker.finish("语音", timestamp = 1_000L))

        assertFalse(tracker.shouldDispatchCommit("语音", timestamp = 1_100L))
    }

    @Test
    fun differentCommitAfterFinishIsPreserved() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("语音")
        tracker.finish("语音", timestamp = 1_000L)

        assertTrue(tracker.shouldDispatchCommit("输入", timestamp = 1_100L))
    }

    @Test
    fun equalCommitOutsideDuplicateWindowIsPreserved() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("好")
        tracker.finish("好", timestamp = 1_000L)

        assertTrue(tracker.shouldDispatchCommit("好", timestamp = 1_251L))
    }

    @Test
    fun newCompositionDoesNotMatchPreviousFinish() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("好")
        tracker.finish("好", timestamp = 1_000L)
        tracker.update("好")

        assertTrue(tracker.shouldDispatchCommit("好", timestamp = 1_100L))
    }

    @Test
    fun clearDropsPendingAndFinishedState() {
        val tracker = TerminalImeCompositionTracker()

        tracker.update("未完成")
        tracker.clear()

        assertNull(tracker.finish(null, timestamp = 1_000L))
        assertTrue(tracker.shouldDispatchCommit("未完成", timestamp = 1_001L))
    }
}
