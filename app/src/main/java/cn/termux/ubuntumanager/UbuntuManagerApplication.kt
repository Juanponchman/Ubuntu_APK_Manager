package cn.termux.ubuntumanager

import android.app.Application
import cn.termux.ubuntumanager.data.AppPreferences
import cn.termux.ubuntumanager.data.UbuntuRepository
import cn.termux.ubuntumanager.permission.RootPermissionSetup
import cn.termux.ubuntumanager.proot.ProotDistroClient
import cn.termux.ubuntumanager.termux.TermuxCommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class UbuntuManagerApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val preferences by lazy { AppPreferences(this) }
    val termuxClient by lazy { TermuxCommandClient(this) }
    val prootClient by lazy { ProotDistroClient(termuxClient) }
    val rootPermissionSetup by lazy { RootPermissionSetup(this) }
    val repository by lazy {
        UbuntuRepository(
            context = this,
            preferences = preferences,
            termuxClient = termuxClient,
            prootClient = prootClient,
        )
    }
}
