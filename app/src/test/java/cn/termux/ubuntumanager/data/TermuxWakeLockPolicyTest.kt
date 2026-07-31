package cn.termux.ubuntumanager.data

import cn.termux.ubuntumanager.model.BackgroundOperationRecord
import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.BackgroundOperationType
import cn.termux.ubuntumanager.model.InstanceRuntimeState
import cn.termux.ubuntumanager.model.UbuntuInstance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxWakeLockPolicyTest {
    @Test
    fun disabledMasterSwitchOverridesEveryReason() {
        assertFalse(
            TermuxWakeLockPolicy.shouldHold(
                registry(alwaysOn = true, maintenanceRunning = true).copy(
                    termuxBackgroundProtection = false,
                ),
                listOf(instance(InstanceRuntimeState.SSH_READY)),
            ),
        )
    }

    @Test
    fun alwaysOnHoldsWithoutInstances() {
        assertTrue(TermuxWakeLockPolicy.shouldHold(registry(alwaysOn = true), emptyList()))
    }

    @Test
    fun maintenanceHoldsWhileInstanceIsStopped() {
        assertTrue(
            TermuxWakeLockPolicy.shouldHold(
                registry(maintenanceRunning = true),
                listOf(instance(InstanceRuntimeState.STOPPED)),
            ),
        )
    }

    @Test
    fun anyActiveRuntimeUsesOneGlobalDecision() {
        assertTrue(
            TermuxWakeLockPolicy.shouldHold(
                registry(),
                listOf(
                    instance(InstanceRuntimeState.STOPPED),
                    instance(InstanceRuntimeState.RUNNING),
                ),
            ),
        )
        assertTrue(
            TermuxWakeLockPolicy.shouldHold(
                registry(),
                listOf(instance(InstanceRuntimeState.STOPPED, hasLocalSession = true)),
            ),
        )
    }

    @Test
    fun stoppedInstancesRemainIdle() {
        assertFalse(
            TermuxWakeLockPolicy.shouldHold(
                registry(),
                listOf(instance(InstanceRuntimeState.STOPPED)),
            ),
        )
    }

    private fun registry(
        alwaysOn: Boolean = false,
        maintenanceRunning: Boolean = false,
    ) = RegistrySnapshot(
        termuxBackgroundProtection = true,
        termuxWakeLockAlwaysOn = alwaysOn,
        backgroundOperation = if (maintenanceRunning) {
            BackgroundOperationRecord(
                id = "test-operation",
                type = BackgroundOperationType.BACKUP,
                label = "测试备份",
                instanceName = "ubuntu",
                status = BackgroundOperationStatus.RUNNING,
                startedEpochMillis = 1,
                updatedEpochMillis = 1,
            )
        } else {
            null
        },
    )

    private fun instance(
        state: InstanceRuntimeState,
        hasLocalSession: Boolean = false,
    ) = UbuntuInstance(
        name = "ubuntu",
        isProtected = true,
        isManaged = false,
        sshPort = 2222,
        hasLocalSession = hasLocalSession,
        state = state,
    )
}
