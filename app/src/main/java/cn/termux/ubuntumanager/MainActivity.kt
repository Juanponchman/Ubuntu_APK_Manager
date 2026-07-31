package cn.termux.ubuntumanager

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cn.termux.ubuntumanager.operation.OperationForegroundService
import cn.termux.ubuntumanager.operation.RecentTaskVisibility
import cn.termux.ubuntumanager.ui.UbuntuManagerApp
import cn.termux.ubuntumanager.ui.theme.UbuntuManagerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val openStorageSettingsRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
            val openStorageSettingsRequest by
                openStorageSettingsRequests.collectAsStateWithLifecycle()
            UbuntuManagerTheme(dynamicColor = dynamicColors) {
                PermissionAwareApp(viewModel, openStorageSettingsRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val settings = (application as UbuntuManagerApplication).preferences.snapshot()
            RecentTaskVisibility.setExcluded(
                this@MainActivity,
                settings.hideMaintenanceFromRecents &&
                    OperationForegroundService.isOperationActive(),
            )
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_STORAGE_SETTINGS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_STORAGE_SETTINGS)
            openStorageSettingsRequests.value += 1
        }
    }

    companion object {
        const val EXTRA_OPEN_STORAGE_SETTINGS =
            "cn.termux.ubuntumanager.OPEN_STORAGE_SETTINGS"
    }
}

@Composable
private fun PermissionAwareApp(
    viewModel: MainViewModel,
    openStorageSettingsRequest: Int,
) {
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    UbuntuManagerApp(
        viewModel = viewModel,
        openStorageSettingsRequest = openStorageSettingsRequest,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}
