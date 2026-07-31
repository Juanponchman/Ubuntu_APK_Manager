package cn.termux.ubuntumanager.proot

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotDistroClientTest {
    @Test
    fun acceptsSafeContainerNames() {
        assertTrue(ProotDistroClient.isValidName("ubuntu-dev"))
        assertTrue(ProotDistroClient.isValidName("ubuntu_24.04"))
        assertTrue(ProotDistroClient.isValidName("u1"))
    }

    @Test
    fun rejectsShellAndPathCharacters() {
        assertFalse(ProotDistroClient.isValidName(""))
        assertFalse(ProotDistroClient.isValidName("-ubuntu"))
        assertFalse(ProotDistroClient.isValidName("../ubuntu"))
        assertFalse(ProotDistroClient.isValidName("ubuntu;rm"))
        assertFalse(ProotDistroClient.isValidName("ubuntu dev"))
        assertFalse(ProotDistroClient.isValidName("ubuntu\$HOME"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivilegedSshPort() {
        ProotDistroClient.requireValidPort(22)
    }

    @Test
    fun parsesProot55TableWrittenToEitherOutputStream() {
        val sessions = ProotDistroClient.parseSessionTable(
            """
                PID    CONTAINER  TYPE    USER  UPTIME  COMMAND
                16900  ubuntu     login*  root  8m12s   /bin/bash -lc test

                * detached session
            """.trimIndent(),
        )

        assertEquals(1, sessions.size)
        assertEquals("ubuntu", sessions.single().container)
        assertEquals("login*", sessions.single().type)
        assertEquals("8m12s", sessions.single().uptime)
    }

    @Test
    fun parsesStructuredSessionAdapterOutput() {
        fun encoded(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val row = listOf(
            "16900",
            encoded("ubuntu"),
            encoded("login*"),
            encoded("root"),
            encoded("9m01s"),
            encoded("/bin/bash -lc test"),
        ).joinToString("\t")

        val session = ProotDistroClient.parseStructuredSessions(row).single()

        assertEquals(16900, session.pid)
        assertEquals("ubuntu", session.container)
        assertEquals("/bin/bash -lc test", session.command)
    }

    @Test
    fun atomicBackupPublishesChecksumBeforeArchiveAndCleansPartials() {
        val script = AtomicBackupScript.build(
            name = "ubuntu",
            directory = "/tmp/backups",
            path = "/tmp/backups/ubuntu_20260731_120000.tar.xz",
        )

        assertTrue(script.contains("backup --compress xz"))
        assertTrue(script.contains(".tar.xz.partial"))
        assertTrue(script.contains("trap cleanup EXIT INT TERM HUP"))
        val checksumPublish = script.indexOf("\"${'$'}checksum_partial\" \"${'$'}checksum\"")
        val archivePublish = script.indexOf("\"${'$'}partial\" \"${'$'}archive\"")
        assertTrue(checksumPublish >= 0)
        assertTrue(archivePublish > checksumPublish)

        val process = ProcessBuilder("/bin/bash", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        assertEquals(process.errorStream.bufferedReader().readText(), 0, process.waitFor())
    }
}
