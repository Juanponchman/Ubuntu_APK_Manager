package cn.termux.ubuntumanager.chroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootClientTest {
    @Test
    fun acceptsSafeInstanceNames() {
        assertTrue(ChrootClient.isValidName("ubuntu"))
        assertTrue(ChrootClient.isValidName("ubuntu-dev_2"))
    }

    @Test
    fun rejectsNamesThatCouldEscapeRootScripts() {
        assertFalse(ChrootClient.isValidName(""))
        assertFalse(ChrootClient.isValidName("-ubuntu"))
        assertFalse(ChrootClient.isValidName("../ubuntu"))
        assertFalse(ChrootClient.isValidName("ubuntu;rm"))
        assertFalse(ChrootClient.isValidName("ubuntu dev"))
    }

    @Test
    fun validatesRootPasswords() {
        assertNull(ChrootClient.rootPasswordValidationError("root1234"))
        assertTrue(ChrootClient.rootPasswordValidationError("short") != null)
        assertTrue(ChrootClient.rootPasswordValidationError("root:1234") != null)
    }

    @Test
    fun supervisorUsesPrivateMountsAndNativeProcessCleanup() {
        val script = ChrootSupervisorScript.content

        assertTrue(script.contains("unshare -m"))
        assertTrue(script.contains("mount none / -o rprivate"))
        assertTrue(script.contains("--kill-rooted"))
        assertTrue(script.contains(ChrootContract.SPARSE_COPY_PATH))
        assertTrue(script.contains("exec-entered-"))
        assertTrue(script.contains("instance mount namespace changed"))
        assertTrue(script.contains("process_cmd="))
        assertTrue(script.contains("LANG=C.UTF-8 LC_ALL=C.UTF-8"))
        assertFalse(script.contains("proot-distro"))
    }

    @Test
    fun supervisorScriptHasValidPosixShellSyntax() {
        val process = ProcessBuilder("/bin/sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(ChrootSupervisorScript.content) }
        val stderr = process.errorStream.bufferedReader().readText()

        assertEquals(stderr, 0, process.waitFor())
    }

    @Test
    fun autoStartScriptRunsDirectlyAsRootAndValidatesConfiguration() {
        val script = ChrootAutoStartScript.content

        assertTrue(script.contains("getprop sys.boot_completed"))
        assertTrue(script.contains("--boot-worker"))
        assertTrue(script.contains(ChrootContract.HELPER_PATH))
        assertTrue(script.contains("valid_name"))
        assertTrue(script.contains("valid_port"))
        assertFalse(script.contains("/product/bin/su"))
        assertFalse(script.contains("eval "))
        assertFalse(script.contains("com.termux"))
        assertFalse(script.contains("proot"))
    }

    @Test
    fun autoStartScriptHasValidPosixShellSyntax() {
        val process = ProcessBuilder("/bin/sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(ChrootAutoStartScript.content) }
        val stderr = process.errorStream.bufferedReader().readText()

        assertEquals(stderr, 0, process.waitFor())
    }

    @Test
    fun encodesSortedAutoStartConfiguration() {
        val encoded = ChrootClient.encodeAutoStartConfiguration(
            ChrootAutoStartConfiguration(
                scriptReady = true,
                enabled = true,
                instances = mapOf("ubuntu-z" to 2223, "ubuntu" to 2222),
            ),
        )

        assertEquals(
            "${ChrootContract.AUTO_START_CONFIG_VERSION}|1\n" +
                "ubuntu|2222\nubuntu-z|2223\n",
            encoded,
        )
    }

    @Test
    fun parsesOnlySafeAutoStartRows() {
        val parsed = ChrootClient.parseAutoStartSnapshot(
            sequenceOf(
                "CNTERMUX_AUTOSTART|1|1",
                "CNTERMUX_AUTOSTART_ENTRY|ubuntu|2222",
                "CNTERMUX_AUTOSTART_ENTRY|../escape|2223",
                "CNTERMUX_AUTOSTART_ENTRY|bad-port|80",
                "CNTERMUX_AUTOSTART_ENTRY|ubuntu|2299",
            ),
        )

        assertTrue(parsed.scriptReady)
        assertTrue(parsed.enabled)
        assertEquals(mapOf("ubuntu" to 2222), parsed.instances)
    }

    @Test
    fun parsesOneShotRuntimeSnapshot() {
        val records = ChrootClient.parseRuntimeSnapshot(
            "ubuntu|RUNNING|321|2222|32100\n" +
                "dev|STOPPED|0|0|0\n",
        )

        assertEquals(2, records.size)
        assertEquals("dev", records[0].name)
        assertFalse(records[0].running)
        assertNull(records[0].sshPort)
        assertEquals("ubuntu", records[1].name)
        assertTrue(records[1].running)
        assertEquals(321, records[1].supervisorPid)
        assertEquals(2222, records[1].sshPort)
        assertEquals(32100, records[1].localTerminalPort)
    }

    @Test
    fun ignoresUnsafeOrMalformedRuntimeRows() {
        val records = ChrootClient.parseRuntimeSnapshot(
            "../escape|RUNNING|1|2222|32100\n" +
                "ubuntu|BROKEN|1|2222|32100\n" +
                "valid|RUNNING|bad|80|70000\n" +
                "valid|RUNNING|9|2223|0\n",
        )

        assertEquals(1, records.size)
        assertEquals("valid", records.single().name)
        assertEquals(0, records.single().supervisorPid)
        assertNull(records.single().sshPort)
        assertNull(records.single().localTerminalPort)
    }

    @Test
    fun parsesBackupRowsFromCombinedInspection() {
        val backups = ChrootClient.parseBackupSnapshot(
            sequenceOf(
                "ubuntu_20260802_080000.img.sparse|1024|200|1",
                "ubuntu_20260801_080000.img.sparse|512|100|0",
                "../unsafe.img.sparse|1|300|1",
            ),
        )

        assertEquals(2, backups.size)
        assertEquals("ubuntu_20260802_080000.img.sparse", backups[0].fileName)
        assertEquals(1024L * 1024L, backups[0].sizeBytes)
        assertTrue(backups[0].checksumPresent)
        assertFalse(backups[1].checksumPresent)
    }

    @Test
    fun parsesNamedBackupMetadataAndLogicalSize() {
        val displayName = "Code 环境安装完成"
        val encodedName = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(displayName.toByteArray())
        val metadata = """{"version":1,"displayNameBase64":"$encodedName"}"""
        val encodedMetadata = java.util.Base64.getEncoder()
            .encodeToString(metadata.toByteArray())

        val backup = ChrootClient.parseBackupSnapshot(
            sequenceOf(
                "ubuntu_20260802_192700.img.sparse|720000|8589934592|200|1|$encodedMetadata",
            ),
        ).single()

        assertEquals(displayName, backup.displayName)
        assertEquals(8589934592L, backup.logicalSizeBytes)
        assertTrue(backup.metadataPresent)
    }

    @Test
    fun parsesPortableVisibleBackup() {
        val backup = ChrootClient.parseBackupSnapshot(
            sequenceOf(
                "ubuntu--20260803_120000--Code 环境.cnubuntu|512000|8589934592|300|1|Q29kZSDnjq_looM",
            ),
        ).single()

        assertEquals("Code 环境", backup.displayName)
        assertEquals("ubuntu", backup.instanceName)
        assertEquals(
            "/storage/emulated/0/Ubuntu管理器/备份/${backup.fileName}",
            backup.path,
        )
        assertTrue(backup.portableArchive)
        assertTrue(backup.checksumPresent)
    }
}
