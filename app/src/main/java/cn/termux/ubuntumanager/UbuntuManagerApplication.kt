package cn.termux.ubuntumanager

import android.app.Application
import cn.termux.ubuntumanager.data.AppPreferences
import cn.termux.ubuntumanager.data.RootPasswordStore
import cn.termux.ubuntumanager.data.UbuntuRepository
import cn.termux.ubuntumanager.chroot.ChrootClient
import cn.termux.ubuntumanager.permission.RootCommandExecutor
import cn.termux.ubuntumanager.permission.RootPermissionSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class UbuntuManagerApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val preferences by lazy { AppPreferences(this) }
    val rootPasswordStore by lazy { RootPasswordStore(this) }
    val rootCommandExecutor by lazy { RootCommandExecutor(this) }
    val chrootClient by lazy { ChrootClient(this, rootCommandExecutor) }
    val rootPermissionSetup by lazy { RootPermissionSetup(this) }
    val repository by lazy {
        UbuntuRepository(
            context = this,
            preferences = preferences,
            rootPasswordStore = rootPasswordStore,
            rootExecutor = rootCommandExecutor,
            chrootClient = chrootClient,
        )
    }
}
