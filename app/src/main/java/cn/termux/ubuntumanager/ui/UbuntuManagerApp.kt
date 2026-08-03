package cn.termux.ubuntumanager.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.termux.ubuntumanager.MainViewModel

private object Routes {
    const val INSTANCES = "instances"
    const val BACKUPS = "backups"
    const val COMMANDS = "commands"
    const val SETTINGS = "settings"
    const val CREATE = "create"
    const val DETAIL = "detail/{name}"
    const val LOGS = "logs/{name}"
    const val LOCAL_SESSION = "session/{name}"

    fun detail(name: String) = "detail/$name"
    fun logs(name: String) = "logs/$name"
    fun localSession(name: String) = "session/$name"
}

private data class RootDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val rootDestinations = listOf(
    RootDestination(Routes.INSTANCES, "实例", Icons.Default.Home),
    RootDestination(Routes.BACKUPS, "备份", Icons.Default.Share),
    RootDestination(Routes.COMMANDS, "指令", Icons.Default.Build),
    RootDestination(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UbuntuManagerApp(
    viewModel: MainViewModel,
    onRequestNotifications: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val alphaSetupInProgress by viewModel.alphaSetupInProgress.collectAsStateWithLifecycle()
    val backgroundSetupInProgress by
        viewModel.backgroundSetupInProgress.collectAsStateWithLifecycle()
    val localSessionState by viewModel.localSessionState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: Routes.INSTANCES
    val isRoot = route in rootDestinations.map { it.route }
    val shouldMonitorStatus = route == Routes.INSTANCES || route == Routes.DETAIL
    val snackbarHostState = remember { SnackbarHostState() }
    var showAlphaConfirmation by remember { mutableStateOf(false) }
    var showBackgroundAlphaConfirmation by remember { mutableStateOf(false) }
    var commandTagManagerRequest by remember { mutableStateOf(0) }
    var createCommandRequest by remember { mutableStateOf(0) }
    var pendingSessionKeyAction by remember {
        mutableStateOf<Pair<String, String>?>(null)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(route, state.environment.backupReady) {
        if (route != Routes.COMMANDS) {
            commandTagManagerRequest = 0
            createCommandRequest = 0
        }
        if (route == Routes.BACKUPS && state.environment.backupReady) {
            viewModel.syncBackups()
        }
    }

    DisposableEffect(lifecycleOwner, shouldMonitorStatus) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (shouldMonitorStatus) {
                        viewModel.startStatusMonitoring()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.stopStatusMonitoring()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (
            shouldMonitorStatus &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            viewModel.startStatusMonitoring()
        } else {
            viewModel.stopStatusMonitoring()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopStatusMonitoring()
        }
    }

    LaunchedEffect(state.lastMessage) {
        val message = state.lastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    if (showAlphaConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!alphaSetupInProgress) showAlphaConfirmation = false
            },
            title = { Text("使用 Magisk Alpha 完成首次配置？") },
            text = {
                Text(
                    "此入口仅支持 io.github.vvb2060.magisk 的 Alpha 版本，不兼容普通 " +
                        "Magisk。管理器会申请超级用户权限，安装固定的 Root Chroot 监督脚本，" +
                        "并创建独立的镜像、运行日志和备份目录。它不会迁移、修改或删除现有的 " +
                        "Termux/PRoot 数据。新 Ubuntu 实例将直接由 APK 和 Alpha Root 管理。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlphaConfirmation = false
                        viewModel.configureWithAlpha()
                    },
                    enabled = !alphaSetupInProgress,
                ) {
                    Text("申请 Alpha Root 并配置")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlphaConfirmation = false },
                    enabled = !alphaSetupInProgress,
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (showBackgroundAlphaConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!backgroundSetupInProgress) showBackgroundAlphaConfirmation = false
            },
            title = { Text("使用 Magisk Alpha 配置后台白名单？") },
            text = {
                Text(
                    "此操作只把 Ubuntu 管理器加入 Android Doze 白名单，并" +
                        "清除 inactive 状态；不会隐藏系统通知、不会停止 Chroot，也" +
                        "不会修改 MIUI 私有数据库。MIUI 的自启动和省电策略仍可通过设置" +
                        "页中的系统入口手动确认。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackgroundAlphaConfirmation = false
                        viewModel.configureBackgroundProtectionWithAlpha()
                    },
                    enabled = !backgroundSetupInProgress,
                ) {
                    Text("申请 Alpha Root 并配置")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBackgroundAlphaConfirmation = false },
                    enabled = !backgroundSetupInProgress,
                ) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (route != Routes.LOCAL_SESSION) TopAppBar(
                title = {
                    Text(
                        when (route) {
                            Routes.INSTANCES -> when {
                                state.environment.checking -> "正在检查 Root Chroot"
                                state.environment.ready -> "Root Chroot 已连接"
                                else -> "Root Chroot 需要配置"
                            }
                            Routes.BACKUPS -> "备份与恢复"
                            Routes.COMMANDS -> "指令库"
                            Routes.SETTINGS -> "设置"
                            Routes.CREATE -> "新建 Ubuntu"
                            Routes.DETAIL -> backStackEntry?.arguments?.getString("name")
                                ?: "实例详情"
                            Routes.LOGS -> "SSH 日志"
                            Routes.LOCAL_SESSION -> {
                                val name = backStackEntry?.arguments?.getString("name").orEmpty()
                                "本地会话 · $name"
                            }
                            else -> "Ubuntu 管理器"
                        },
                    )
                },
                navigationIcon = {
                    if (!isRoot) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (route == Routes.COMMANDS) {
                        IconButton(onClick = { createCommandRequest += 1 }) {
                            Icon(Icons.Default.Add, contentDescription = "新增指令")
                        }
                        TextButton(onClick = { commandTagManagerRequest += 1 }) {
                            Text("管理标签")
                        }
                    }
                    if (isRoot) {
                        IconButton(
                            onClick = viewModel::refresh,
                            enabled = state.currentOperation == null,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isRoot) {
                NavigationBar {
                    rootDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = route == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (
                route == Routes.INSTANCES &&
                state.environment.ready &&
                state.currentOperation == null
            ) {
                FloatingActionButton(onClick = { navController.navigate(Routes.CREATE) }) {
                    Icon(Icons.Default.Add, contentDescription = "新建 Ubuntu")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.INSTANCES,
            modifier = Modifier
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding),
        ) {
            composable(Routes.INSTANCES) {
                InstancesScreen(
                    state = state,
                    onRequestAlphaSetup = { showAlphaConfirmation = true },
                    alphaSetupInProgress = alphaSetupInProgress,
                    onRefresh = viewModel::refresh,
                    onSession = { navController.navigate(Routes.localSession(it)) },
                    onDetails = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.BACKUPS) {
                BackupsScreen(
                    state = state,
                    onRestore = viewModel::restore,
                    onDelete = viewModel::deleteBackup,
                    onRename = viewModel::renameBackup,
                    onOpenStorageSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                    onRefresh = viewModel::refresh,
                )
            }
            composable(Routes.COMMANDS) {
                CommandLibraryScreen(
                    state = state,
                    initialInstanceName = null,
                    onRunCommand = viewModel::executeLibraryCommand,
                    onSendKeyAction = { name, commandId ->
                        pendingSessionKeyAction = name to commandId
                        navController.navigate(Routes.localSession(name)) {
                            launchSingleTop = true
                        }
                    },
                    onSaveTags = viewModel::saveCommandTags,
                    onSaveCommand = viewModel::saveCommand,
                    onDeleteCommand = viewModel::deleteCommand,
                    showTagManagerRequest = commandTagManagerRequest,
                    createCommandRequest = createCommandRequest,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    state = state,
                    dynamicColors = dynamicColors,
                    onDynamicColorsChanged = viewModel::setDynamicColors,
                    onHideFromRecentsWhenBackgroundChanged =
                        viewModel::setHideFromRecentsWhenBackground,
                    onAutoStartEnabledChanged = viewModel::setAutoStartEnabled,
                    onBackupRetentionCountChanged =
                        viewModel::setBackupRetentionCount,
                    onRequestBackgroundAlphaSetup = {
                        showBackgroundAlphaConfirmation = true
                    },
                    backgroundSetupInProgress = backgroundSetupInProgress,
                    onRequestAlphaSetup = { showAlphaConfirmation = true },
                    alphaSetupInProgress = alphaSetupInProgress,
                    onRequestNotifications = onRequestNotifications,
                    onSaveTerminalShortcuts = viewModel::saveTerminalShortcuts,
                    onRefresh = viewModel::refresh,
                )
            }
            composable(Routes.CREATE) {
                CreateInstanceScreen(
                    state = state,
                    onCreate = {
                            name,
                            port,
                            initializeSsh,
                            startAfterCreate,
                            protectAfterCreate,
                        ->
                        viewModel.create(
                            name,
                            port,
                            initializeSsh,
                            startAfterCreate,
                            protectAfterCreate,
                        )
                        navController.popBackStack()
                    },
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("name").orEmpty()
                InstanceDetailScreen(
                    instance = state.instances.firstOrNull { it.name == name },
                    allInstances = state.instances,
                    lastStatusCheckEpochMillis = state.lastStatusCheckEpochMillis,
                    operationInProgress = state.currentOperation != null,
                    autoStartAvailable = state.environment.autoStartReady,
                    autoStartGloballyEnabled = state.autoStartEnabled,
                    onStart = { viewModel.start(name) },
                    onStop = { viewModel.stop(name) },
                    onRestart = { viewModel.restart(name) },
                    onForceStop = { viewModel.stop(name, force = true) },
                    onInitializeSsh = { viewModel.initializeSsh(name) },
                    onLocalSession = {
                        navController.navigate(Routes.localSession(name))
                    },
                    onBackup = { displayName -> viewModel.backup(name, displayName) },
                    onLogs = { navController.navigate(Routes.logs(name)) },
                    onProtectionChanged = { viewModel.setProtection(name, it) },
                    onAutoStartChanged = { viewModel.setInstanceAutoStart(name, it) },
                    onUpdatePort = { viewModel.updateSshPort(name, it) },
                    onUpdateRootPassword = { viewModel.updateRootPassword(name, it) },
                    onClearSavedRootPassword = { viewModel.clearSavedRootPassword(name) },
                    onRename = {
                        viewModel.rename(name, it)
                        navController.popBackStack()
                    },
                    onClone = { targetName, port, protectTarget ->
                        viewModel.cloneInstance(name, targetName, port, protectTarget)
                    },
                    onDelete = {
                        viewModel.delete(name)
                        navController.popBackStack()
                    },
                )
            }
            composable(
                route = Routes.LOCAL_SESSION,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("name").orEmpty()
                LocalSessionScreen(
                    instanceName = name,
                    sessionState = localSessionState,
                    commands = state.commands,
                    commandTags = state.commandTags,
                    terminalShortcuts = state.terminalShortcuts,
                    pendingKeyActionId = pendingSessionKeyAction
                        ?.takeIf { it.first == name }
                        ?.second,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSession = { viewModel.openLocalSession(name) },
                    onCloseSession = { viewModel.closeLocalSession(name) },
                    onEndSession = {
                        viewModel.endLocalSession(name)
                        navController.popBackStack()
                    },
                    onPendingKeyActionConsumed = {
                        if (pendingSessionKeyAction?.first == name) {
                            pendingSessionKeyAction = null
                        }
                    },
                )
            }
            composable(
                route = Routes.LOGS,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("name").orEmpty()
                val logs by viewModel.logContent.collectAsStateWithLifecycle()
                LogsScreen(
                    name = name,
                    logs = logs,
                    onRefresh = { viewModel.loadLogs(name) },
                )
            }
        }
    }
}
