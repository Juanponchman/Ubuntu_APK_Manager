package cn.termux.ubuntumanager.data

import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.InstanceRuntimeState
import cn.termux.ubuntumanager.model.UbuntuInstance

internal object TermuxWakeLockPolicy {
    fun shouldHold(
        registry: RegistrySnapshot,
        instances: List<UbuntuInstance>,
    ): Boolean {
        if (!registry.termuxBackgroundProtection) return false
        if (registry.termuxWakeLockAlwaysOn) return true
        if (registry.backgroundOperation?.status == BackgroundOperationStatus.RUNNING) return true
        return instances.any(::isRuntimeActive)
    }

    private fun isRuntimeActive(instance: UbuntuInstance): Boolean =
        instance.state == InstanceRuntimeState.RUNNING ||
            instance.state == InstanceRuntimeState.SSH_READY ||
            instance.hasLocalSession
}
