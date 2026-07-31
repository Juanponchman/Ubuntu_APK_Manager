package cn.termux.ubuntumanager.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPermissionSetupTest {
    @Test
    fun acceptsOnlyMagiskAlphaIdentity() {
        assertTrue(AlphaSuContract.isSupportedManagerVersion("e8a58776-alpha"))
        assertTrue(AlphaSuContract.isSupportedSuVersion("e8a58776-alpha:MAGISKSU"))

        assertFalse(AlphaSuContract.isSupportedManagerVersion("30.7"))
        assertFalse(AlphaSuContract.isSupportedSuVersion("30.7:MAGISKSU"))
        assertFalse(AlphaSuContract.isSupportedSuVersion("KernelSU"))
    }

    @Test
    fun alphaContractUsesDeviceSpecificPackageAndSuPath() {
        assertTrue(AlphaSuContract.MANAGER_PACKAGE == "io.github.vvb2060.magisk")
        assertTrue(AlphaSuContract.SU_PATH == "/product/bin/su")
        assertTrue(
            AlphaSuContract.rootCommand("id") ==
                listOf("/product/bin/su", "-t", "0", "-c", "id"),
        )
        assertTrue(AlphaSuContract.hiddenRootMessage().contains("配置排除列表"))
    }

    @Test
    fun reportsDeniedAlphaAuthorizationSeparately() {
        val denied = AlphaSuContract.rootFailureMessage(1, "permission denied")
        val commandFailure = AlphaSuContract.rootFailureMessage(
            2,
            "SecurityException: permission grant failed",
        )

        assertTrue(denied.contains("已拒绝"))
        assertTrue(commandFailure.contains("退出码 2"))
    }

    @Test
    fun scriptOnlyTargetsManagerAndTermuxConfiguration() {
        val script = RootSetupScript.build(
            managerPackage = "cn.termux.ubuntumanager",
            termuxDataDir = "/data/user/0/com.termux",
            termuxUid = 10257,
            termuxUserId = 0,
        )

        assertTrue(
            script.contains(
                "pm grant cn.termux.ubuntumanager com.termux.permission.RUN_COMMAND",
            ),
        )
        assertTrue(script.contains("allow-external-apps=true"))
        assertTrue(script.contains("chown 10257:10257"))
        assertTrue(script.contains("termux_file.ubuntu-manager.bak"))
        assertTrue(script.contains("grep -c"))
        assertTrue(script.contains("ROOT_SETUP_OK"))
        assertTrue(script.contains("ROOT_SETUP_PROOT_RUNNING"))
        assertTrue(script.contains("/system/bin/pidof proot"))
        assertTrue(script.indexOf("/system/bin/pidof proot") < script.indexOf("am force-stop"))
        assertTrue(script.contains("com.termux/.app.TermuxActivity"))
        assertFalse(script.contains("rm -"))
        assertFalse(script.contains("proot-distro kill"))
    }

    @Test
    fun storageRepairScriptOnlyGrantsFixedTermuxPermissions() {
        val script = RootStorageScript.build(userId = 0)

        assertTrue(script.contains("pm grant --user 0 com.termux"))
        assertTrue(script.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertTrue(script.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertTrue(script.contains("READ_EXTERNAL_STORAGE allow"))
        assertTrue(script.contains("WRITE_EXTERNAL_STORAGE allow"))
        assertTrue(script.contains("MANAGE_EXTERNAL_STORAGE allow"))
        assertTrue(script.contains("ROOT_STORAGE_OK"))
        assertFalse(script.contains("force-stop"))
        assertFalse(script.contains("proot"))
        assertFalse(script.contains("rm -"))
    }

    @Test
    fun backgroundProtectionOnlyWhitelistsManagerAndTermux() {
        val script = RootBackgroundProtectionScript.build(
            managerPackage = "cn.termux.ubuntumanager",
            userId = 0,
        )

        assertTrue(script.contains("deviceidle whitelist +cn.termux.ubuntumanager"))
        assertTrue(script.contains("deviceidle whitelist +com.termux"))
        assertTrue(script.contains("set-inactive --user 0 com.termux false"))
        assertTrue(script.contains("ROOT_BACKGROUND_PROTECTION_OK"))
        assertFalse(script.contains("force-stop"))
        assertFalse(script.contains("proot-distro"))
        assertFalse(script.contains("rm -"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun backgroundProtectionRejectsInjectedPackageName() {
        RootBackgroundProtectionScript.build("cn.termux.manager;reboot", 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun storageRepairRejectsInvalidAndroidUser() {
        RootStorageScript.build(userId = -1)
    }

    @Test
    fun storageLinkScriptUsesOfficialVisibleTermuxFlow() {
        val script = RootStorageLinkScript.build(
            managerPackage = "cn.termux.ubuntumanager",
            termuxDataDir = "/data/user/0/com.termux",
            userId = 0,
            rebuild = false,
        )

        assertTrue(script.contains("com.termux/.app.TermuxActivity"))
        assertTrue(script.contains("com.termux.app.request_storage_permissions"))
        assertTrue(script.contains("ROOT_STORAGE_LINKS_OK"))
        assertTrue(script.contains("ROOT_STORAGE_LINKS_EXIST"))
        assertTrue(script.contains("cn.termux.ubuntumanager/.MainActivity"))
        assertTrue(
            script.indexOf("com.termux/.app.TermuxActivity") <
                script.indexOf("com.termux.app.request_storage_permissions"),
        )
        assertFalse(script.contains("ROOT_STORAGE_LINKS_UNSAFE"))
        assertFalse(script.contains("force-stop"))
        assertFalse(script.contains("proot"))
        assertFalse(script.contains("rm -"))
        assertFalse(script.contains("ln -"))
        assertFalse(script.contains("chown"))
    }

    @Test
    fun storageLinkRebuildRefusesOrdinaryDirectoryEntries() {
        val script = RootStorageLinkScript.build(
            managerPackage = "cn.termux.ubuntumanager",
            termuxDataDir = "/data/data/com.termux",
            userId = 0,
            rebuild = true,
        )

        assertTrue(script.contains("! -type l"))
        assertTrue(script.contains("/system/bin/head -n 1"))
        assertTrue(script.contains("ROOT_STORAGE_LINKS_UNSAFE"))
        assertFalse(script.contains("ROOT_STORAGE_LINKS_EXIST"))
        assertFalse(script.contains("rm -"))
    }

    @Test
    fun storageLinkScriptsHaveValidShellSyntax() {
        listOf(false, true).forEach { rebuild ->
            val script = RootStorageLinkScript.build(
                managerPackage = "cn.termux.ubuntumanager",
                termuxDataDir = "/data/user/0/com.termux",
                userId = 0,
                rebuild = rebuild,
            )
            val process = ProcessBuilder("/bin/sh", "-n").start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            assertEquals(process.errorStream.bufferedReader().readText(), 0, process.waitFor())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun storageLinkRejectsUnexpectedTermuxDirectory() {
        RootStorageLinkScript.build(
            managerPackage = "cn.termux.ubuntumanager",
            termuxDataDir = "/data/user/0/other.app",
            userId = 0,
            rebuild = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInjectedPackageName() {
        RootSetupScript.build(
            managerPackage = "cn.termux.ubuntumanager;id",
            termuxDataDir = "/data/user/0/com.termux",
            termuxUid = 10257,
            termuxUserId = 0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedDataDirectory() {
        RootSetupScript.build(
            managerPackage = "cn.termux.ubuntumanager",
            termuxDataDir = "/data/user/0/other.app",
            termuxUid = 10257,
            termuxUserId = 0,
        )
    }
}
