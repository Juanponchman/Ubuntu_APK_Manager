package cn.termux.ubuntumanager.permission

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellSessionTest {
    @Test
    fun reusesShellAndSupportsRedirectedInput() {
        val process = ProcessBuilder("/bin/sh").redirectErrorStream(true).start()
        val session = RootShellSession(process)
        val input = File.createTempFile("root-shell-session", ".txt").apply {
            writeText("STREAMED")
        }
        try {
            val first = session.execute("printf 'FIRST\\n'", 2_000, null)
            val second = session.execute("printf 'SECOND\\n'", 2_000, null)
            val streamed = session.execute("cat", 2_000, input)

            assertTrue(first.bestError, first.isSuccess)
            assertTrue(second.bestError, second.isSuccess)
            assertTrue(streamed.bestError, streamed.isSuccess)
            assertEquals("FIRST", first.stdout)
            assertEquals("SECOND", second.stdout)
            assertEquals("STREAMED", streamed.stdout)
            assertTrue(session.isAlive)
        } finally {
            session.close()
            input.delete()
        }
    }

    @Test
    fun returnsNonZeroExitWithoutClosingSession() {
        val process = ProcessBuilder("/bin/sh").redirectErrorStream(true).start()
        val session = RootShellSession(process)
        try {
            val failed = session.execute("printf 'FAILED'; exit 7", 2_000, null)
            val afterFailure = session.execute("printf 'ALIVE'", 2_000, null)

            assertEquals(7, failed.exitCode)
            assertEquals("FAILED", failed.stdout)
            assertTrue(afterFailure.isSuccess)
            assertEquals("ALIVE", afterFailure.stdout)
        } finally {
            session.close()
        }
    }

    @Test
    fun supportsLargerCaptureLimitForTerminalHistory() {
        val process = ProcessBuilder("/bin/sh").redirectErrorStream(true).start()
        val session = RootShellSession(process)
        try {
            val result = session.execute(
                "head -c 100000 /dev/zero | tr '\\000' x",
                2_000,
                null,
                maxCaptureLength = 120_000,
            )

            assertTrue(result.bestError, result.isSuccess)
            assertEquals(100_000, result.stdout.length)
            assertTrue(result.stdoutOriginalLength >= 100_000)
        } finally {
            session.close()
        }
    }
}
