package cn.termux.ubuntumanager.operation

import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.BackgroundOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundOperationRequestTest {
    @Test
    fun createRequestKeepsAllSafetyOptions() {
        val request = BackgroundOperationRequest.create(
            name = "ubuntu-dev",
            port = 2223,
            initializeSsh = true,
            startAfterCreate = false,
            protectAfterCreate = true,
        )

        assertEquals(BackgroundOperationType.CREATE, request.type)
        assertEquals("ubuntu-dev", request.instanceName)
        assertEquals(2223, request.port)
        assertTrue(request.flag1)
        assertTrue(request.flag3)
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(request.id))
    }

    @Test
    fun restoreRequestKeepsValidatedBackupIdentity() {
        val backup = BackupEntry(
            fileName = "ubuntu_20260731_120000.img.sparse",
            path = "/safe/ubuntu_20260731_120000.img.sparse",
            instanceName = "ubuntu",
            sizeBytes = 42,
            modifiedEpochSeconds = 10,
            checksumPresent = true,
        )
        val first = BackgroundOperationRequest.restore(backup)
        val second = BackgroundOperationRequest.restore(backup)

        assertEquals(backup, first.backup)
        assertEquals(BackgroundOperationType.RESTORE, first.type)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun backupRequestKeepsUserVisibleName() {
        val request = BackgroundOperationRequest.backup("ubuntu", "升级 Node 之前")

        assertEquals(BackgroundOperationType.BACKUP, request.type)
        assertEquals("ubuntu", request.instanceName)
        assertEquals("升级 Node 之前", request.secondaryName)
    }
}
