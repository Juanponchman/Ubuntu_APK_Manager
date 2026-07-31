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
    openStorageSettingsRequest: Int,
    onRequestNotifications: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val alphaSetupInProgress by viewModel.alphaSetupInProgress.collectAsStateWithLifecycle()
    val storageRepairInProgress by viewModel.storageRepairInProgress.collectAsStateWithLifecycle()
    val storageLinkInProgress by viewModel.storageLinkInProgress.collectAsStateWithLifecycle()
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
    var showStorageAlphaConfirmation by remember { mutableStateOf(false) }
    var storageLinkRebuild by remember { mutableStateOf(false) }
    var showStorageLinkConfirmation by remember { mutableStateOf(false) }
    var showBackgroundAlphaConfirmation by remember { mutableStateOf(false) }
    var commandTagManagerRequest by remember { mutableStateOf(0) }
    var createCommandRequest by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(route) {
        if (route != Routes.COMMANDS) {
            commandTagManagerRequest = 0
            createCommandRequest = 0
        }
    }

    LaunchedEffect(openStorageSettingsRequest) {
        if (openStorageSettingsRequest > 0) {
            navController.navigate(Routes.SETTINGS) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    DisposableEffect(lifecycleOwner, shouldMonitorStatus) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (shouldMonitorStatus) {
                        viewModel.startStatusMonitoring()
                    } else {
                        viewModel.refresh()
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
                            "Magisk。请先在 Alpha 的“配置排除列表”中取消勾选 Ubuntu 管理器。" +
                            "随后管理器会申请一次超级用户权限，只执行两项固定操作：授予 " +
                            "RUN_COMMAND，并备份后启用 Termux 的 allow-external-apps；写入后会" +
                            "校验文件。由于此 Termux 版本需要重启才能加载该设置，若没有运行中" +
                            "的 PRoot，管理器会关闭普通 Termux 会话并重启 Termux；若检测到 " +
                            "PRoot 则中止重启以保护 Ubuntu。Ubuntu 和 PRoot 后续仍以普通应用" +
                            "权限运行。",
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
                    "此操作只把 Termux 和 Ubuntu 管理器加入 Android Doze 白名单，并" +
                        "清除两者的 inactive 状态；不会隐藏系统通知、不会停止 PRoot，也" +
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

    if (showStorageAlphaConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!storageRepairInProgress) showStorageAlphaConfirmation = false
            },
            title = { Text("使用 Magisk Alpha 修复 Termux 存储权限？") },
            text = {
                Text(
                    "此入口只支持已安装的 Magisk Alpha，不兼容普通 Magisk。它会向 " +
                        "Termux 固定授予读取、写入和所有文件访问权限。本操作只处理系统" +
                        "权限，不创建 ~/storage 目录链接，也不会停止或删除任何 PRoot 实例。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStorageAlphaConfirmation = false
                        viewModel.repairStorageWithAlpha()
                    },
                    enabled = !storageRepairInProgress,
                ) {
                    Text("申请 Alpha Root 并修复")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStorageAlphaConfirmation = false },
                    enabled = !storageRepairInProgress,
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (showStorageLinkConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!storageLinkInProgress) showStorageLinkConfirmation = false
            },
            title = {
                Text(
                    if (storageLinkRebuild) {
                        "使用 Magisk Alpha 重建目录链接？"
                    } else {
                        "使用 Magisk Alpha 创建目录链接？"
                    },
                )
            },
            text = {
                Text(
                    if (storageLinkRebuild) {
                        "管理器会先确认 ~/storage 中只有符号链接；发现普通文件或目录时将" +
                            "拒绝执行。确认安全后，Alpha 会把 Termux 拉到前台并调用 Termux " +
                            "官方逻辑重建链接。不会使用 Root 直接删除或创建链接。"
                    } else {
                        "Alpha 会把 Termux 可靠地拉到前台，再发送 Termux 官方目录链接请求。" +
                            "链接由 Termux 自己创建，Root 不会直接修改 Termux 私有目录；" +
                            "完成后会验证 Download 写入并创建 UbuntuManager 备份目录。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStorageLinkConfirmation = false
                        viewModel.createStorageLinksWithAlpha(storageLinkRebuild)
                    },
                    enabled = !storageLinkInProgress,
                ) {
                    Text(if (storageLinkRebuild) "确认安全并重建" else "创建目录链接")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStorageLinkConfirmation = false },
                    enabled = !storageLinkInProgress,
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
                                state.environment.checking -> "正在检查 Termux"
                                state.environment.ready -> "Termux 已连接"
                                state.environment.termuxStopped -> "Termux 未运行"
                                else -> "Termux 需要配置"
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
                    onOpenTermux = viewModel::openTermux,
                    onRefresh = viewModel::refresh,
                    onStart = viewModel::start,
                    onSession = { navController.navigate(Routes.localSession(it)) },
                    onDetails = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.BACKUPS) {
                BackupsScreen(
                    state = state,
                    onRestore = viewModel::restore,
                    onDelete = viewModel::deleteBackup,
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
                    onHideMaintenanceFromRecentsChanged =
                        viewModel::setHideMaintenanceFromRecents,
                    onTermuxBackgroundProtectionChanged =
                        viewModel::setTermuxBackgroundProtection,
                    onTermuxWakeLockAlwaysOnChanged =
                        viewModel::setTermuxWakeLockAlwaysOn,
                    onRequestBackgroundAlphaSetup = {
                        showBackgroundAlphaConfirmation = true
                    },
                    backgroundSetupInProgress = backgroundSetupInProgress,
                    onOpenTermux = viewModel::openTermux,
                    onRequestAlphaSetup = { showAlphaConfirmation = true },
                    alphaSetupInProgress = alphaSetupInProgress,
                    onRequestNotifications = onRequestNotifications,
                    onRequestStorageAlphaRepair = {
                        showStorageAlphaConfirmation = true
                    },
                    storageRepairInProgress = storageRepairInProgress,
                    onRequestStorageAlphaLinks = { rebuild ->
                        storageLinkRebuild = rebuild
                        showStorageLinkConfirmation = true
                    },
                    storageLinkInProgress = storageLinkInProgress,
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
                    onStart = { viewModel.start(name) },
                    onStop = { viewModel.stop(name) },
                    onRestart = { viewModel.restart(name) },
                    onForceStop = { viewModel.stop(name, force = true) },
                    onInitializeSsh = { viewModel.initializeSsh(name) },
                    onLocalSession = {
                        navController.navigate(Routes.localSession(name))
                    },
                    onBackup = { viewModel.backup(name) },
                    onLogs = { navController.navigate(Routes.logs(name)) },
                    onOpenTermux = viewModel::openTermux,
                    onProtectionChanged = { viewModel.setProtection(name, it) },
                    onUpdatePort = { viewModel.updateSshPort(name, it) },
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
                    operationInProgress = state.currentOperation != null,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSession = { viewModel.openLocalSession(name) },
                    onCloseSession = { viewModel.closeLocalSession(name) },
                    onEndSession = {
                        viewModel.endLocalSession(name)
                        navController.popBackStack()
                    },
                    onRunCommand = { commandId ->
                        viewModel.executeLibraryCommand(name, commandId)
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
