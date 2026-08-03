package cn.termux.ubuntumanager

import android.Manifest
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
import cn.termux.ubuntumanager.operation.RecentTaskVisibility
import cn.termux.ubuntumanager.ui.UbuntuManagerApp
import cn.termux.ubuntumanager.ui.theme.UbuntuManagerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var userHasLeftActivity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
            UbuntuManagerTheme(dynamicColor = dynamicColors) {
                PermissionAwareApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        userHasLeftActivity = false
        RecentTaskVisibility.setExcluded(this, false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userHasLeftActivity = true
        lifecycleScope.launch {
            val settings = (application as UbuntuManagerApplication).preferences.snapshot()
            if (userHasLeftActivity && settings.hideFromRecentsWhenBackground) {
                RecentTaskVisibility.setExcluded(this@MainActivity, true)
            }
        }
    }
}

@Composable
private fun PermissionAwareApp(
    viewModel: MainViewModel,
) {
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    UbuntuManagerApp(
        viewModel = viewModel,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}
