package cn.termux.ubuntumanager.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.termux.ubuntumanager.data.UbuntuRepository
import cn.termux.ubuntumanager.BuildConfig
import cn.termux.ubuntumanager.command.TerminalShortcuts
import cn.termux.ubuntumanager.model.AppUiState
import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.CommandHistoryEntry
import cn.termux.ubuntumanager.model.CommandTag
import cn.termux.ubuntumanager.model.EnvironmentStatus
import cn.termux.ubuntumanager.model.InstanceRuntimeState
import cn.termux.ubuntumanager.model.TerminalShortcutAction
import cn.termux.ubuntumanager.model.TerminalShortcutPreference
import cn.termux.ubuntumanager.model.TermuxWakeLockState
import cn.termux.ubuntumanager.model.UbuntuInstance
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.proot.ProotDistroClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun InstancesScreen(
    state: AppUiState,
    onRequestAlphaSetup: () -> Unit,
    alphaSetupInProgress: Boolean,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    onStart: (String) -> Unit,
    onSession: (String) -> Unit,
    onDetails: (String) -> Unit,
) {
    when {
        state.environment.checking -> LoadingState("正在检查 Termux 环境…")
        !state.environment.ready -> EnvironmentSetup(
            environment = state.environment,
            onRequestAlphaSetup = onRequestAlphaSetup,
            alphaSetupInProgress = alphaSetupInProgress,
            onOpenTermux = onOpenTermux,
            onRefresh = onRefresh,
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.currentOperation?.let { operation ->
                item {
                    OperationCard(operation.label)
                }
            }
            if (state.instances.isEmpty()) {
                item {
                    EmptyInstances()
                }
            } else {
                items(state.instances, key = { it.name }) { instance ->
                    InstanceCard(
                        instance = instance,
                        enabled = state.currentOperation == null,
                        onStart = { onStart(instance.name) },
                        onSession = { onSession(instance.name) },
                        onDetails = { onDetails(instance.name) },
                    )
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun EnvironmentSummary(environment: EnvironmentStatus) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Termux 已连接", fontWeight = FontWeight.SemiBold)
                Text(
                    "Termux ${environment.termuxVersion ?: "未知"} · PRoot Distro ${environment.prootVersion ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!environment.storageReady) {
            HorizontalDivider()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "备份目录尚不可写，请前往“设置 → 备份与存储”查看分项诊断",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OperationCard(label: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "长时间操作会通过系统通知保持运行",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InstanceCard(
    instance: UbuntuInstance,
    enabled: Boolean,
    onStart: () -> Unit,
    onSession: () -> Unit,
    onDetails: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onDetails,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    instance.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (instance.isProtected) {
                    AssistChip(
                        onClick = onDetails,
                        label = { Text("受保护") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            RuntimeStatus(instance)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SSH 端口：${instance.sshPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    instance.hostAddress?.let { address ->
                        Text(
                            "$address:${instance.sshPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (
                    instance.state == InstanceRuntimeState.RUNNING ||
                    instance.state == InstanceRuntimeState.SSH_READY
                ) {
                    Button(onClick = onSession, enabled = enabled) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("进入会话")
                    }
                } else if (instance.state == InstanceRuntimeState.STOPPED) {
                    Button(onClick = onStart, enabled = enabled) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("启动")
                    }
                } else {
                    Button(onClick = {}, enabled = false) {
                        Text("状态未知")
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatus(instance: UbuntuInstance) {
    val (text, color) = when (instance.state) {
        InstanceRuntimeState.CHECKING -> "正在检查" to MaterialTheme.colorScheme.tertiary
        InstanceRuntimeState.STOPPED -> "○ 已停止" to MaterialTheme.colorScheme.onSurfaceVariant
        InstanceRuntimeState.RUNNING -> "● Ubuntu 运行中，SSH 未监听" to
            MaterialTheme.colorScheme.tertiary
        InstanceRuntimeState.SSH_READY -> "● SSH 正常" to Color(0xFF2E7D32)
        InstanceRuntimeState.OPERATING -> "正在操作" to MaterialTheme.colorScheme.primary
        InstanceRuntimeState.UNKNOWN -> "？ 状态未知" to MaterialTheme.colorScheme.error
        InstanceRuntimeState.ERROR -> "✕ 运行异常" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, fontWeight = FontWeight.Medium)
}

@Composable
private fun EmptyInstances() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有 Ubuntu 实例", style = MaterialTheme.typography.titleMedium)
            Text(
                "点击右下角“＋”创建 Ubuntu 24.04 LTS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingState(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(label)
        }
    }
}

@Composable
private fun EnvironmentSetup(
    environment: EnvironmentStatus,
    onRequestAlphaSetup: () -> Unit,
    alphaSetupInProgress: Boolean,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("完成连接设置", style = MaterialTheme.typography.headlineSmall)
        Text(
            "管理器只通过 Termux 官方 RUN_COMMAND 接口执行预定义操作。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SetupStep(
            title = "1. 安装官方 Termux",
            completed = environment.termuxInstalled,
            description = environment.termuxVersion?.let { "已检测到版本 $it" }
                ?: "需要包名为 com.termux 的官方版本",
            actionText = if (environment.termuxInstalled) null else "打开下载页面",
            onAction = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/termux/termux-app/releases"),
                    ),
                )
            },
        )
        PermissionSetupStep(
            title = "2. 授予运行命令权限",
            completed = environment.permissionGranted,
            onAlphaPermission = onRequestAlphaSetup,
            alphaSetupInProgress = alphaSetupInProgress,
        )
        SetupStep(
            title = "3. 连接 Termux",
            completed = environment.connectionAvailable,
            description = when {
                environment.connectionAvailable ->
                    "Termux 已允许 Ubuntu 管理器通过 RUN_COMMAND 执行预定义操作"
                environment.termuxStopped ->
                    "Termux 已被系统完全停止。打开 Termux 后返回本应用，会自动重新连接。"
                environment.externalAppsConfigured ->
                    "外部调用配置已经完成，但 Termux 当前没有响应。请打开 Termux 后返回。"
                else ->
                    "可通过 Magisk Alpha 自动写入并重新加载设置，或在 Termux 的 " +
                        "~/.termux/termux.properties 中手动加入：\nallow-external-apps=true"
            },
            actionText = when {
                environment.connectionAvailable ||
                    environment.termuxStopped ||
                    environment.externalAppsConfigured -> null
                alphaSetupInProgress -> "正在配置…"
                else -> "Magisk Alpha 配置"
            },
            secondaryActionText = if (environment.connectionAvailable) null else "打开 Termux",
            actionEnabled = !alphaSetupInProgress,
            onAction = onRequestAlphaSetup,
            onSecondaryAction = onOpenTermux,
        )
        SetupStep(
            title = "4. 检查 PRoot Distro",
            completed = environment.prootCompatible,
            description = environment.prootVersion?.let { "当前版本 $it，需要 5.1 或更高版本" }
                ?: "请在 Termux 执行 pkg upgrade && pkg install proot-distro",
            actionText = if (environment.prootCompatible) null else "打开 Termux",
            onAction = onOpenTermux,
        )
        environment.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重新检查")
        }
    }
}

@Composable
private fun PermissionSetupStep(
    title: String,
    completed: Boolean,
    onAlphaPermission: () -> Unit,
    alphaSetupInProgress: Boolean,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (completed) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (completed) {
                    "已授予“在 Termux 环境中运行命令”权限"
                } else {
                    "使用 Magisk Alpha 完成固定权限配置，不提供自动降级或其他授权入口。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!completed) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onAlphaPermission,
                    enabled = !alphaSetupInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (alphaSetupInProgress) {
                            "等待 Magisk Alpha…"
                        } else {
                            "Magisk Alpha 首次配置"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    title: String,
    completed: Boolean,
    description: String,
    actionText: String? = null,
    secondaryActionText: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    onSecondaryAction: () -> Unit = {},
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (completed) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionText != null || secondaryActionText != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actionText?.let {
                        FilledTonalButton(
                            onClick = onAction,
                            enabled = actionEnabled,
                        ) {
                            Text(it)
                        }
                    }
                    secondaryActionText?.let {
                        TextButton(onClick = onSecondaryAction) { Text(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateInstanceScreen(
    state: AppUiState,
    onCreate: (String, Int, Boolean, Boolean, Boolean) -> Unit,
) {
    val usedPorts = state.instances.map { it.sshPort }.toSet()
    val suggestedPort = (UbuntuRepository.DEFAULT_SSH_PORT..65535)
        .first { it !in usedPorts }
    var name by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(suggestedPort.toString()) }
    var initializeSsh by remember { mutableStateOf(true) }
    var startAfterCreate by remember { mutableStateOf(false) }
    var protectAfterCreate by remember { mutableStateOf(true) }

    val port = portText.toIntOrNull()
    val nameError = when {
        name.isBlank() -> null
        !ProotDistroClient.isValidName(name) -> "名称格式不正确"
        state.instances.any { it.name == name } -> "实例名称已经存在"
        else -> null
    }
    val portError = when {
        port == null -> "请输入有效端口"
        port !in 1024..65535 -> "端口必须在 1024～65535 之间"
        port in usedPorts -> "端口已分配给其他实例"
        else -> null
    }
    val canSubmit = name.isNotBlank() && nameError == null && portError == null &&
        state.currentOperation == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "创建独立的 Ubuntu 24.04 LTS 实例。Redmi K20 Pro 将自动使用 ARM64 镜像。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 32) name = it },
            label = { Text("实例名称") },
            supportingText = {
                Text(nameError ?: "例如 ubuntu-dev、ubuntu-test")
            },
            isError = nameError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = "Ubuntu 24.04 LTS",
            onValueChange = {},
            label = { Text("系统版本") },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) portText = it },
            label = { Text("SSH 端口") },
            supportingText = { Text(portError ?: "每个实例必须使用不同端口") },
            isError = portError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        CheckRow(
            checked = initializeSsh,
            onCheckedChange = {
                initializeSsh = it
                if (!it) startAfterCreate = false
            },
            title = "安装并初始化 SSH",
            description = "创建后执行 apt 更新并安装 openssh-server",
        )
        CheckRow(
            checked = startAfterCreate,
            onCheckedChange = { startAfterCreate = it },
            enabled = initializeSsh,
            title = "创建后立即启动",
            description = "在后台启动 SSH 服务",
        )
        CheckRow(
            checked = protectAfterCreate,
            onCheckedChange = { protectAfterCreate = it },
            title = "创建后启用实例保护",
            description = "默认保护新实例，避免误删除、重命名或覆盖恢复",
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Text(
                "安装和初始化需要下载系统镜像及软件包，可能持续数分钟。请保持网络稳定。",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = {
                onCreate(
                    name,
                    port!!,
                    initializeSsh,
                    startAfterCreate,
                    protectAfterCreate,
                )
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("开始创建")
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InstanceDetailScreen(
    instance: UbuntuInstance?,
    allInstances: List<UbuntuInstance>,
    lastStatusCheckEpochMillis: Long?,
    operationInProgress: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onForceStop: () -> Unit,
    onInitializeSsh: () -> Unit,
    onLocalSession: () -> Unit,
    onBackup: () -> Unit,
    onLogs: () -> Unit,
    onOpenTermux: () -> Unit,
    onProtectionChanged: (Boolean) -> Unit,
    onUpdatePort: (Int) -> Unit,
    onRename: (String) -> Unit,
    onClone: (String, Int, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    if (instance == null) {
        LoadingState("正在读取实例…")
        return
    }
    var confirmForceStop by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnlock by remember { mutableStateOf(false) }
    var showPortEditor by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val running = instance.state == InstanceRuntimeState.RUNNING ||
        instance.state == InstanceRuntimeState.SSH_READY
    val stopped = instance.state == InstanceRuntimeState.STOPPED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("运行状态") {
            RuntimeStatus(instance)
            InfoRow(
                "连接地址",
                "${instance.hostAddress ?: "127.0.0.1"}:${instance.sshPort}",
            )
            InfoRow("SSH 端口", instance.sshPort.toString())
            InfoRow("运行时间", instance.uptime ?: "—")
            InfoRow("实例保护", if (instance.isProtected) "已保护" else "未保护")
            InfoRow("实例来源", if (instance.isManaged) "本应用创建" else "外部已有实例")
            InfoRow(
                "后台会话",
                if (instance.hasLocalSession) "运行中，可继续进入" else "未创建",
            )
            InfoRow(
                "最后检测",
                lastStatusCheckEpochMillis?.let(::formatDateMillis) ?: "—",
            )
            instance.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        SectionCard("主要操作") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onLocalSession,
                    enabled = !operationInProgress &&
                        instance.state != InstanceRuntimeState.UNKNOWN,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (instance.hasLocalSession) "继续会话" else "本地会话")
                }
                when {
                    running -> Button(
                        onClick = { confirmStop = true },
                        enabled = !operationInProgress,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("停止 Ubuntu")
                    }
                    stopped -> Button(
                        onClick = onStart,
                        enabled = !operationInProgress,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("启动 Ubuntu")
                    }
                    else -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("状态未知")
                    }
                }
            }
        }

        SectionCard("数据管理") {
            Button(
                onClick = onBackup,
                enabled = !operationInProgress &&
                    instance.state == InstanceRuntimeState.STOPPED,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("立即备份")
            }
            OutlinedButton(
                onClick = { showClone = true },
                enabled = !operationInProgress && stopped,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("复制为新实例")
            }
            Text(
                if (stopped) {
                    "实例已停止，可以安全备份或复制。"
                } else {
                    "必须先手动停止实例，运行中不会自动关机或开始备份。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (stopped) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "高级操作",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (showAdvanced) "收起" else "展开")
                }
                if (showAdvanced) {
                    HorizontalDivider()
                    Text("实例设置", fontWeight = FontWeight.SemiBold)
                    InfoRow("实例名称", instance.name)
                    OutlinedButton(
                        onClick = { showPortEditor = true },
                        enabled = !operationInProgress &&
                            instance.state != InstanceRuntimeState.UNKNOWN,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("修改 SSH 端口")
                    }
                    OutlinedButton(
                        onClick = { showRename = true },
                        enabled = !operationInProgress && stopped && !instance.isProtected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重命名实例")
                    }
                    OutlinedButton(
                        onClick = {
                            if (instance.isProtected) {
                                confirmUnlock = true
                            } else {
                                onProtectionChanged(true)
                            }
                        },
                        enabled = !operationInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (instance.isProtected) "解除保护" else "重新启用保护")
                    }
                    HorizontalDivider()
                    Text("维护与诊断", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = onInitializeSsh,
                        enabled = !operationInProgress && stopped,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("安装/修复 SSH")
                    }
                    OutlinedButton(
                        onClick = onRestart,
                        enabled = !operationInProgress && running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重启 SSH")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenTermux,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Termux")
                        }
                        OutlinedButton(
                            onClick = onLogs,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("查看日志")
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmForceStop = true },
                        enabled = !operationInProgress && running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("强制停止")
                    }
                }
            }
        }

        if (!instance.isProtected) {
            SectionCard("危险操作") {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !operationInProgress && stopped,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("永久删除此实例")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmUnlock) {
        ConfirmNameDialog(
            title = "解除 ${instance.name} 的保护？",
            message = "解除后可以重命名、覆盖恢复或永久删除该实例。解除保护本身不会修改 Ubuntu。",
            instanceName = instance.name,
            confirmText = "解除保护",
            onDismiss = { confirmUnlock = false },
            onConfirm = {
                confirmUnlock = false
                onProtectionChanged(false)
            },
        )
    }
    if (confirmStop) {
        ConfirmDialog(
            title = "停止 ${instance.name}？",
            message = "将停止此实例中的 Ubuntu 与 SSH 会话。",
            confirmText = "停止",
            onDismiss = { confirmStop = false },
            onConfirm = {
                confirmStop = false
                onStop()
            },
        )
    }
    if (confirmForceStop) {
        ConfirmDialog(
            title = "强制停止 ${instance.name}？",
            message = "这会立即终止该实例中的所有会话和子进程，未保存的数据可能丢失。",
            confirmText = "强制停止",
            dangerous = true,
            onDismiss = { confirmForceStop = false },
            onConfirm = {
                confirmForceStop = false
                onForceStop()
            },
        )
    }
    if (confirmDelete) {
        DeleteInstanceDialog(
            instanceName = instance.name,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
    if (showPortEditor) {
        EditPortDialog(
            instance = instance,
            usedPorts = allInstances.filter { it.name != instance.name }.map { it.sshPort }.toSet(),
            running = instance.state == InstanceRuntimeState.SSH_READY,
            onDismiss = { showPortEditor = false },
            onConfirm = {
                showPortEditor = false
                onUpdatePort(it)
            },
        )
    }
    if (showRename) {
        RenameInstanceDialog(
            instanceName = instance.name,
            existingNames = allInstances.map { it.name }.toSet(),
            onDismiss = { showRename = false },
            onConfirm = {
                showRename = false
                onRename(it)
            },
        )
    }
    if (showClone) {
        CloneInstanceDialog(
            sourceName = instance.name,
            instances = allInstances,
            onDismiss = { showClone = false },
            onConfirm = { targetName, port, protected ->
                showClone = false
                onClone(targetName, port, protected)
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dangerous: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (dangerous) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
    )
}

@Composable
private fun DeleteInstanceDialog(
    instanceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typedName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text("永久删除 $instanceName？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("此操作会永久删除整个 Ubuntu 文件系统，无法撤销。")
                Text("请输入实例名称确认：")
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    singleLine = true,
                    label = { Text(instanceName) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typedName == instanceName,
            ) {
                Text("永久删除", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun ConfirmNameDialog(
    title: String,
    message: String,
    instanceName: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typedName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                Text("请输入实例名称确认：")
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    singleLine = true,
                    label = { Text(instanceName) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typedName == instanceName,
            ) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun EditPortDialog(
    instance: UbuntuInstance,
    usedPorts: Set<Int>,
    running: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var portText by remember(instance.name) { mutableStateOf(instance.sshPort.toString()) }
    val port = portText.toIntOrNull()
    val error = when {
        port == null -> "请输入有效端口"
        port !in 1024..65535 -> "端口必须在 1024～65535 之间"
        port in usedPorts -> "端口已分配给其他实例"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 SSH 端口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        if (it.length <= 5 && it.all(Char::isDigit)) portText = it
                    },
                    label = { Text("SSH 端口") },
                    supportingText = { Text(error ?: "当前端口：${instance.sshPort}") },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (running) {
                    Text(
                        "实例正在运行。应用后会停止全部会话并使用新端口重新启动 SSH；失败时会恢复原端口。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(port!!) },
                enabled = error == null && port != instance.sshPort,
            ) {
                Text(if (running) "停止并应用" else "保存")
            }
        },
    )
}

@Composable
private fun RenameInstanceDialog(
    instanceName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember(instanceName) { mutableStateOf(instanceName) }
    val error = when {
        newName == instanceName -> "请输入新的实例名称"
        !ProotDistroClient.isValidName(newName) -> "名称格式不正确"
        newName in existingNames -> "实例名称已经存在"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名 $instanceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("重命名会更新 PRoot 的软链接记录，实例越大耗时越长。过程中不要关闭应用。")
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= 32) newName = it },
                    label = { Text("新实例名称") },
                    supportingText = { Text(error ?: "例如 ubuntu-dev") },
                    isError = error != null,
                    singleLine = true,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName) },
                enabled = error == null,
            ) {
                Text("开始重命名")
            }
        },
    )
}

@Composable
private fun CloneInstanceDialog(
    sourceName: String,
    instances: List<UbuntuInstance>,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Boolean) -> Unit,
) {
    val existingNames = instances.map { it.name }.toSet()
    val usedPorts = instances.map { it.sshPort }.toSet()
    val suggestedName = remember(sourceName, existingNames) {
        (1..999).asSequence().map { index ->
            val suffix = if (index == 1) "-copy" else "-copy$index"
            sourceName.take(32 - suffix.length) + suffix
        }.first { it !in existingNames }
    }
    val suggestedPort = (UbuntuRepository.DEFAULT_SSH_PORT..65535)
        .first { it !in usedPorts }
    var targetName by remember(sourceName) { mutableStateOf(suggestedName) }
    var portText by remember(sourceName) { mutableStateOf(suggestedPort.toString()) }
    var protectTarget by remember(sourceName) { mutableStateOf(true) }
    val port = portText.toIntOrNull()
    val nameError = when {
        !ProotDistroClient.isValidName(targetName) -> "名称格式不正确"
        targetName in existingNames -> "实例名称已经存在"
        else -> null
    }
    val portError = when {
        port == null -> "请输入有效端口"
        port !in 1024..65535 -> "端口必须在 1024～65535 之间"
        port in usedPorts -> "端口已分配给其他实例"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("复制 $sourceName") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("复制会保留文件和软件，并重新生成 SSH 主机密钥、machine-id 和 hostname。")
                OutlinedTextField(
                    value = targetName,
                    onValueChange = { if (it.length <= 32) targetName = it },
                    label = { Text("新实例名称") },
                    supportingText = { Text(nameError ?: "新实例将保持停止状态") },
                    isError = nameError != null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        if (it.length <= 5 && it.all(Char::isDigit)) portText = it
                    },
                    label = { Text("新 SSH 端口") },
                    supportingText = { Text(portError ?: "必须与其他实例不同") },
                    isError = portError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                CheckRow(
                    checked = protectTarget,
                    onCheckedChange = { protectTarget = it },
                    title = "创建后启用保护",
                    description = "建议保持开启，避免误删除新实例",
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(targetName, port!!, protectTarget) },
                enabled = nameError == null && portError == null,
            ) {
                Text("开始复制")
            }
        },
    )
}

@Composable
fun BackupsScreen(
    state: AppUiState,
    onRestore: (BackupEntry) -> Unit,
    onDelete: (BackupEntry) -> Unit,
    onOpenStorageSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    var restoreCandidate by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<BackupEntry?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.currentOperation?.let {
            item { OperationCard(it.label) }
        }
        if (!state.environment.storageReady) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("备份目录尚不可用，请在设置中使用 Magisk Alpha 处理存储。")
                        Button(
                            onClick = onOpenStorageSettings,
                            enabled = state.currentOperation == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("前往存储设置")
                        }
                        TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text("重新检查")
                        }
                    }
                }
            }
        }
        if (state.backups.isEmpty()) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("暂无备份", style = MaterialTheme.typography.titleMedium)
                        Text("进入实例详情并在停止状态下创建备份")
                    }
                }
            }
        } else {
            items(state.backups, key = { it.path }) { backup ->
                val target = state.instances.firstOrNull { it.name == backup.instanceName }
                BackupCard(
                    backup = backup,
                    protectedTarget = target?.isProtected == true,
                    enabled = state.currentOperation == null,
                    onRestore = { restoreCandidate = backup },
                    onDelete = { deleteCandidate = backup },
                )
            }
        }
    }

    restoreCandidate?.let { backup ->
        ConfirmDialog(
            title = "恢复 ${backup.instanceName}？",
            message = "恢复会覆盖同名实例的当前文件。系统将先校验 SHA-256 和备份结构，请确认目标实例已经停止。",
            confirmText = "校验并恢复",
            dangerous = true,
            onDismiss = { restoreCandidate = null },
            onConfirm = {
                restoreCandidate = null
                onRestore(backup)
            },
        )
    }
    deleteCandidate?.let { backup ->
        ConfirmDialog(
            title = "删除 ${backup.instanceName} 的备份？",
            message = "${backup.fileName}\n${formatBytes(backup.sizeBytes)}\n\n" +
                "备份压缩包和 SHA-256 校验文件都会被永久删除，此操作无法恢复。",
            confirmText = "永久删除",
            dangerous = true,
            onDismiss = { deleteCandidate = null },
            onConfirm = {
                deleteCandidate = null
                onDelete(backup)
            },
        )
    }
}

@Composable
private fun BackupCard(
    backup: BackupEntry,
    protectedTarget: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    backup.instanceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (backup.checksumPresent) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "有校验文件",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                formatDate(backup.modifiedEpochSeconds),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${formatBytes(backup.sizeBytes)} · ${
                    if (backup.checksumPresent) "包含 SHA-256 校验" else "没有校验文件"
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (protectedTarget) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "受保护实例禁止普通恢复覆盖",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!protectedTarget) {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("恢复")
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
fun CommandLibraryScreen(
    state: AppUiState,
    initialInstanceName: String?,
    onRunCommand: (String, String) -> Unit,
    onSaveTags: (List<CommandTag>) -> Unit,
    onSaveCommand: (UserCommand) -> Unit,
    onDeleteCommand: (String) -> Unit,
    showTagManagerRequest: Int = 0,
    createCommandRequest: Int = 0,
) {
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    var commandCandidate by remember { mutableStateOf<UserCommand?>(null) }
    var runCandidate by remember { mutableStateOf<UserCommand?>(null) }
    var targetInstanceName by remember { mutableStateOf<String?>(null) }
    var editorCommand by remember { mutableStateOf<UserCommand?>(null) }
    var showTagManager by remember { mutableStateOf(false) }
    val availableTagIds = state.commandTags.map { it.id }.toSet()
    val filteredCommands = state.commands.filter { command ->
        selectedTagId == null || selectedTagId in command.tagIds
    }

    LaunchedEffect(state.commandTags) {
        if (selectedTagId != null && selectedTagId !in availableTagIds) {
            selectedTagId = null
        }
    }
    LaunchedEffect(showTagManagerRequest) {
        if (showTagManagerRequest > 0) showTagManager = true
    }
    LaunchedEffect(createCommandRequest) {
        if (createCommandRequest > 0) {
            editorCommand = UserCommand(
                id = "cmd_${UUID.randomUUID().toString().replace("-", "")}",
                title = "",
                script = "",
            )
        }
    }

    editorCommand?.let { command ->
        CommandEditor(
            command = command,
            availableTags = state.commandTags,
            existing = state.commands.any { it.id == command.id },
            onBack = { editorCommand = null },
            onSave = {
                editorCommand = null
                onSaveCommand(it)
            },
            onDelete = {
                editorCommand = null
                onDeleteCommand(command.id)
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.currentOperation?.let {
            item { OperationCard(it.label) }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedTagId == null,
                        onClick = { selectedTagId = null },
                        label = { Text("全部") },
                    )
                }
                items(state.commandTags, key = { it.id }) { tag ->
                    FilterChip(
                        selected = selectedTagId == tag.id,
                        onClick = { selectedTagId = tag.id },
                        label = { Text(tag.name) },
                    )
                }
            }
        }
        if (filteredCommands.isEmpty()) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.commands.isEmpty()) {
                            "还没有指令，请点击右上角“+”自行创建。"
                        } else {
                            "此标签下还没有指令。"
                        },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(filteredCommands, key = { it.id }) { command ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            Text(command.title, fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(
                                command.script.replace('\n', ' '),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Build, contentDescription = null)
                        },
                        modifier = Modifier.clickable(
                            enabled = state.currentOperation == null,
                        ) {
                            commandCandidate = command
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    commandCandidate?.let { command ->
        AlertDialog(
            onDismissRequest = { commandCandidate = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        command.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = {
                            commandCandidate = null
                            editorCommand = command
                        },
                    ) {
                        Text("编辑")
                    }
                }
            },
            text = {
                Text(
                    command.script,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        commandCandidate = null
                        targetInstanceName = initialInstanceName
                            ?.takeIf { name -> state.instances.any { it.name == name } }
                            ?: state.instances.firstOrNull()?.name
                        runCandidate = command
                    },
                    enabled = state.instances.isNotEmpty() && state.currentOperation == null,
                ) {
                    Text("选择实例执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { commandCandidate = null }) { Text("取消") }
            },
        )
    }

    runCandidate?.let { command ->
        AlertDialog(
            onDismissRequest = { runCandidate = null },
            title = { Text("选择实例") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.instances.forEach { instance ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { targetInstanceName = instance.name }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = targetInstanceName == instance.name,
                                onClick = { targetInstanceName = instance.name },
                            )
                            Column {
                                Text(instance.name)
                                Text(
                                    instanceStateLabel(instance.state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = targetInstanceName ?: return@TextButton
                        runCandidate = null
                        onRunCommand(target, command.id)
                    },
                    enabled = targetInstanceName != null && state.currentOperation == null,
                ) {
                    Text("执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { runCandidate = null }) { Text("取消") }
            },
        )
    }
    if (showTagManager) {
        var editableTags by remember(state.commandTags) { mutableStateOf(state.commandTags) }
        AlertDialog(
            onDismissRequest = { showTagManager = false },
            title = { Text("管理指令标签") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "标签可新增、改名和删除，最多 20 个。删除标签不会删除指令。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    editableTags.forEachIndexed { index, tag ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = tag.name,
                                onValueChange = { value ->
                                    editableTags = editableTags.toMutableList().also {
                                        it[index] = tag.copy(name = value.take(12))
                                    }
                                },
                                label = { Text("标签名称") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    editableTags = editableTags.filterNot { it.id == tag.id }
                                },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除标签")
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (editableTags.size < 20) {
                                editableTags = editableTags + CommandTag(
                                    id = "custom_${UUID.randomUUID().toString().replace("-", "")}",
                                    name = "新标签",
                                )
                            }
                        },
                        enabled = editableTags.size < 20,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加标签")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTagManager = false
                        onSaveTags(editableTags)
                    },
                    enabled = editableTags.all { it.name.trim().isNotEmpty() } &&
                        editableTags.map { it.name.trim() }.distinct().size == editableTags.size,
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTagManager = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CommandEditor(
    command: UserCommand,
    availableTags: List<CommandTag>,
    existing: Boolean,
    onBack: () -> Unit,
    onSave: (UserCommand) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(command.id) { mutableStateOf(command.title) }
    var script by remember(command.id) { mutableStateOf(command.script) }
    var tagIds by remember(command.id) { mutableStateOf(command.tagIds) }
    var confirmDelete by remember(command.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                if (existing) "编辑指令" else "新增指令",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(40) },
            label = { Text("指令名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = script,
            onValueChange = { script = it.take(32_768) },
            label = { Text("命令") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
        )
        if (availableTags.isNotEmpty()) {
            Text("所属标签", fontWeight = FontWeight.SemiBold)
            availableTags.forEach { tag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            tagIds = if (tag.id in tagIds) tagIds - tag.id else tagIds + tag.id
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = tag.id in tagIds,
                        onCheckedChange = { checked ->
                            tagIds = if (checked) tagIds + tag.id else tagIds - tag.id
                        },
                    )
                    Text(tag.name)
                }
            }
        }
        Button(
            onClick = {
                onSave(
                    command.copy(
                        title = title.trim(),
                        script = script.trim(),
                        tagIds = tagIds,
                    ),
                )
            },
            enabled = title.isNotBlank() && script.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
        if (existing) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除指令")
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除“${command.title}”？",
            message = "删除后无法恢复，执行历史不会受到影响。",
            confirmText = "删除",
            dangerous = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

@Composable
fun SettingsScreen(
    state: AppUiState,
    dynamicColors: Boolean,
    onDynamicColorsChanged: (Boolean) -> Unit,
    onHideMaintenanceFromRecentsChanged: (Boolean) -> Unit,
    onTermuxBackgroundProtectionChanged: (Boolean) -> Unit,
    onTermuxWakeLockAlwaysOnChanged: (Boolean) -> Unit,
    onRequestBackgroundAlphaSetup: () -> Unit,
    backgroundSetupInProgress: Boolean,
    onOpenTermux: () -> Unit,
    onRequestAlphaSetup: () -> Unit,
    alphaSetupInProgress: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestStorageAlphaRepair: () -> Unit,
    storageRepairInProgress: Boolean,
    onRequestStorageAlphaLinks: (Boolean) -> Unit,
    storageLinkInProgress: Boolean,
    onSaveTerminalShortcuts: (List<TerminalShortcutPreference>) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val storagePermissionsReady = state.environment.termuxReadStorageGranted &&
        state.environment.termuxWriteStorageGranted &&
        state.environment.termuxAllFilesGranted
    var showShortcutSettings by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("外观") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("使用系统动态颜色", fontWeight = FontWeight.Medium)
                    Text(
                        "Android 12 及以上可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = dynamicColors,
                    onCheckedChange = onDynamicColorsChanged,
                )
            }
        }
        SectionCard("后台运行保护") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("维护任务期间隐藏最近任务卡片", fontWeight = FontWeight.Medium)
                    Text(
                        "仅防止误划；后台任务仍通过低干扰通知进入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.hideMaintenanceFromRecents,
                    onCheckedChange = onHideMaintenanceFromRecentsChanged,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Termux 后台保护", fontWeight = FontWeight.Medium)
                    Text(
                        "实例、本地会话或后台维护任务运行时自动持有 WakeLock",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.termuxBackgroundProtection,
                    onCheckedChange = onTermuxBackgroundProtectionChanged,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("持续保持 WakeLock", fontWeight = FontWeight.Medium)
                    Text(
                        "即使没有运行实例也保持，适合长期后台任务，但会增加耗电",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.termuxWakeLockAlwaysOn,
                    onCheckedChange = onTermuxWakeLockAlwaysOnChanged,
                    enabled = state.termuxBackgroundProtection,
                )
            }
            InfoRow(
                "Termux WakeLock",
                when (state.termuxWakeLockState) {
                    TermuxWakeLockState.DISABLED -> "已关闭"
                    TermuxWakeLockState.IDLE -> "待机"
                    TermuxWakeLockState.ACQUIRING -> "正在启用"
                    TermuxWakeLockState.HELD -> "已持有"
                    TermuxWakeLockState.RELEASING -> "正在释放"
                    TermuxWakeLockState.ERROR -> "异常"
                },
            )
            state.termuxWakeLockMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.termuxWakeLockState == TermuxWakeLockState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            state.backgroundOperation?.let { operation ->
                val status = when (operation.status) {
                    BackgroundOperationStatus.RUNNING -> "运行中"
                    BackgroundOperationStatus.SUCCEEDED -> "已完成"
                    BackgroundOperationStatus.FAILED -> "失败"
                    BackgroundOperationStatus.INTERRUPTED -> "已中断，需检查"
                }
                InfoRow("最近后台任务", "${operation.label} · $status")
                operation.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (
                            operation.status == BackgroundOperationStatus.FAILED ||
                            operation.status == BackgroundOperationStatus.INTERRUPTED
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                "系统要求可靠后台任务保持可见通知。管理器不会使用 Root 隐藏进程或" +
                    "Termux 通知，也不会在中断后自动重复执行破坏性操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRequestBackgroundAlphaSetup,
                enabled = !backgroundSetupInProgress && state.currentOperation == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (backgroundSetupInProgress) {
                        "等待 Magisk Alpha…"
                    } else {
                        "Magisk Alpha：加入后台白名单"
                    },
                )
            }
            OutlinedButton(
                onClick = { openBatteryOptimizationSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开系统电池优化设置")
            }
            OutlinedButton(
                onClick = { openMiuiAutostartSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 MIUI 自启动管理")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { openAppDetails(context, context.packageName) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("管理器应用设置")
                }
                TextButton(
                    onClick = { openAppDetails(context, "com.termux") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Termux 应用设置")
                }
            }
        }
        SectionCard("终端快捷键") {
            Text(
                "设置本地会话底部按键的显示和顺序，布局会根据屏幕宽度自动换行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showShortcutSettings = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("配置快捷键")
            }
        }
        SectionCard("运行环境") {
            InfoRow(
                "Termux",
                if (state.environment.termuxInstalled) {
                    state.environment.termuxVersion ?: "已安装"
                } else {
                    "未安装"
                },
            )
            InfoRow("RUN_COMMAND 权限", if (state.environment.permissionGranted) "已授予" else "未授予")
            InfoRow("外部调用", if (state.environment.connectionAvailable) "可用" else "不可用")
            InfoRow("PRoot Distro", state.environment.prootVersion ?: "未检测到")
            InfoRow("备份目录", if (state.environment.storageReady) "可用" else "未授权")
            OutlinedButton(onClick = onOpenTermux, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开 Termux")
            }
            OutlinedButton(
                onClick = onRequestAlphaSetup,
                enabled = !alphaSetupInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (alphaSetupInProgress) {
                        "等待 Magisk Alpha…"
                    } else {
                        "Magisk Alpha 首次配置"
                    },
                )
            }
            OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                Text("授予操作通知权限")
            }
        }
        SectionCard("备份与存储") {
            InfoRow(
                "Termux 读取权限",
                if (state.environment.termuxReadStorageGranted) "已授予" else "未授予",
            )
            InfoRow(
                "Termux 写入权限",
                if (state.environment.termuxWriteStorageGranted) "已授予" else "未授予",
            )
            InfoRow(
                "所有文件访问",
                if (state.environment.termuxAllFilesGranted) "已允许" else "未允许",
            )
            InfoRow(
                "~/storage 目录链接",
                if (state.environment.storageLinksPresent) "已创建" else "未创建",
            )
            InfoRow(
                "Download 写入测试",
                if (state.environment.storageWriteTestPassed) "通过" else "未通过",
            )
            Text(
                "备份保存在 Download/UbuntuManager。系统权限和目录链接是两个独立步骤；" +
                    "所有存储处理只通过固定的 Magisk Alpha 操作完成，不自动切换其他入口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!storagePermissionsReady) {
                Button(
                    onClick = onRequestStorageAlphaRepair,
                    enabled = !storageRepairInProgress &&
                        !storageLinkInProgress &&
                        state.currentOperation == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (storageRepairInProgress) {
                            "等待 Magisk Alpha…"
                        } else {
                            "Magisk Alpha：修复存储权限"
                        },
                    )
                }
            }
            if (
                storagePermissionsReady &&
                !state.environment.storageDirectoryPresent
            ) {
                Button(
                    onClick = { onRequestStorageAlphaLinks(false) },
                    enabled = !storageRepairInProgress &&
                        !storageLinkInProgress &&
                        state.currentOperation == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (storageLinkInProgress) {
                            "正在等待 Termux 创建链接…"
                        } else {
                            "Magisk Alpha：创建目录链接"
                        },
                    )
                }
            }
            if (
                storagePermissionsReady &&
                state.environment.storageDirectoryPresent &&
                (
                    !state.environment.storageLinksPresent ||
                        !state.environment.storageWriteTestPassed
                    )
            ) {
                OutlinedButton(
                    onClick = { onRequestStorageAlphaLinks(true) },
                    enabled = !storageRepairInProgress &&
                        !storageLinkInProgress &&
                        state.currentOperation == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (storageLinkInProgress) {
                            "正在等待 Termux 重建链接…"
                        } else {
                            "Magisk Alpha：安全重建目录链接"
                        },
                    )
                }
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("重新检查全部存储状态")
            }
        }
        Text(
            "Ubuntu 管理器 ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showShortcutSettings) {
        TerminalShortcutSettingsDialog(
            current = state.terminalShortcuts.ifEmpty { TerminalShortcuts.defaults },
            commands = state.commands,
            onDismiss = { showShortcutSettings = false },
            onSave = {
                showShortcutSettings = false
                onSaveTerminalShortcuts(it)
            },
        )
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetails(context, context.packageName) }
}

private fun openMiuiAutostartSettings(context: Context) {
    val intent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetails(context, context.packageName) }
}

private fun openAppDetails(context: Context, packageName: String) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun TerminalShortcutSettingsDialog(
    current: List<TerminalShortcutPreference>,
    commands: List<UserCommand>,
    onDismiss: () -> Unit,
    onSave: (List<TerminalShortcutPreference>) -> Unit,
) {
    var editable by remember(current) { mutableStateOf(current) }
    var draft by remember { mutableStateOf<TerminalShortcutPreference?>(null) }
    val editing = draft
    val draftValid = editing?.let { shortcut ->
        shortcut.label.isNotBlank() && when (shortcut.action) {
            TerminalShortcutAction.BUILTIN ->
                TerminalShortcuts.isValidBuiltinAction(shortcut.payload)
            TerminalShortcutAction.TEXT ->
                shortcut.payload.isNotEmpty() || shortcut.appendEnter
            TerminalShortcutAction.COMMAND ->
                commands.any { it.id == shortcut.payload }
        }
    } == true

    AlertDialog(
        onDismissRequest = {
            if (draft != null) draft = null else onDismiss()
        },
        title = {
            Text(if (editing == null) "终端快捷键" else "编辑快捷键")
        },
        text = {
            if (editing == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    editable.forEachIndexed { index, shortcut ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = shortcut.enabled,
                                onCheckedChange = { enabled ->
                                    editable = editable.toMutableList().also {
                                        it[index] = shortcut.copy(enabled = enabled)
                                    }
                                },
                            )
                            Text(
                                shortcut.label,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { draft = shortcut }) {
                                Text("编辑")
                            }
                            TextButton(
                                onClick = {
                                    if (index > 0) {
                                        editable = editable.toMutableList().also {
                                            val before = it[index - 1]
                                            it[index - 1] = it[index]
                                            it[index] = before
                                        }
                                    }
                                },
                                enabled = index > 0,
                            ) {
                                Text("↑")
                            }
                            TextButton(
                                onClick = {
                                    if (index < editable.lastIndex) {
                                        editable = editable.toMutableList().also {
                                            val after = it[index + 1]
                                            it[index + 1] = it[index]
                                            it[index] = after
                                        }
                                    }
                                },
                                enabled = index < editable.lastIndex,
                            ) {
                                Text("↓")
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            draft = TerminalShortcutPreference(
                                id = "custom_${UUID.randomUUID().toString().replace("-", "")}",
                                label = "新按键",
                                action = TerminalShortcutAction.TEXT,
                                payload = "",
                            )
                        },
                        enabled = editable.size < 32,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("新增自定义按键")
                    }
                    TextButton(
                        onClick = { editable = TerminalShortcuts.defaults },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("恢复默认")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = editing.label,
                        onValueChange = { draft = editing.copy(label = it.take(12)) },
                        label = { Text("按键名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("按键动作", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            TerminalShortcutAction.BUILTIN to "特殊按键",
                            TerminalShortcutAction.TEXT to "输入文本",
                            TerminalShortcutAction.COMMAND to "指令库",
                        ).forEach { (action, title) ->
                            FilterChip(
                                selected = editing.action == action,
                                onClick = {
                                    draft = editing.copy(
                                        action = action,
                                        payload = when (action) {
                                            TerminalShortcutAction.BUILTIN ->
                                                TerminalShortcuts.ENTER
                                            TerminalShortcutAction.TEXT -> ""
                                            TerminalShortcutAction.COMMAND ->
                                                commands.firstOrNull()?.id.orEmpty()
                                        },
                                        appendEnter = action != TerminalShortcutAction.BUILTIN,
                                    )
                                },
                                label = { Text(title) },
                            )
                        }
                    }
                    when (editing.action) {
                        TerminalShortcutAction.BUILTIN -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(TerminalShortcuts.builtinActions) { action ->
                                    FilterChip(
                                        selected = editing.payload == action,
                                        onClick = {
                                            draft = editing.copy(payload = action)
                                        },
                                        label = {
                                            Text(TerminalShortcuts.builtinLabel(action))
                                        },
                                    )
                                }
                            }
                        }
                        TerminalShortcutAction.TEXT -> {
                            OutlinedTextField(
                                value = editing.payload,
                                onValueChange = {
                                    draft = editing.copy(payload = it.take(4096))
                                },
                                label = { Text("发送内容") },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TerminalShortcutAction.COMMAND -> {
                            if (commands.isEmpty()) {
                                Text("指令库为空，请先创建指令。")
                            } else {
                                commands.forEach { command ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                draft = editing.copy(payload = command.id)
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = editing.payload == command.id,
                                            onClick = {
                                                draft = editing.copy(payload = command.id)
                                            },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(command.title)
                                            Text(
                                                command.script.replace('\n', ' '),
                                                maxLines = 1,
                                                overflow =
                                                    androidx.compose.ui.text.style.TextOverflow
                                                        .Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (editing.action != TerminalShortcutAction.BUILTIN) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (editing.action == TerminalShortcutAction.COMMAND) {
                                        "点击后立即执行"
                                    } else {
                                        "发送后自动回车"
                                    },
                                )
                                Text(
                                    if (editing.appendEnter) {
                                        "开启"
                                    } else {
                                        "关闭，仅插入当前命令行"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = editing.appendEnter,
                                onCheckedChange = {
                                    draft = editing.copy(appendEnter = it)
                                },
                            )
                        }
                    }
                    if (editing.id !in TerminalShortcuts.requiredIds) {
                        OutlinedButton(
                            onClick = {
                                editable = editable.filterNot { it.id == editing.id }
                                draft = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("删除自定义按键")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editing == null) {
                TextButton(onClick = { onSave(editable) }) {
                    Text("保存全部")
                }
            } else {
                TextButton(
                    onClick = {
                        val shortcut = draft ?: return@TextButton
                        val index = editable.indexOfFirst { it.id == shortcut.id }
                        editable = if (index >= 0) {
                            editable.toMutableList().also { it[index] = shortcut }
                        } else {
                            editable + shortcut
                        }
                        draft = null
                    },
                    enabled = draftValid,
                ) {
                    Text("应用")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (draft != null) draft = null else onDismiss()
                },
            ) {
                Text(if (editing == null) "取消" else "返回")
            }
        },
    )
}

@Composable
fun LogsScreen(
    name: String,
    logs: String,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(name) { onRefresh() }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$name · 最近 200 行",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新日志")
            }
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F14))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                logs,
                color = Color(0xFFD7E3EA),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDate(epochSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochSeconds * 1000))

private fun formatDateMillis(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochMillis))

private fun instanceStateLabel(state: InstanceRuntimeState): String = when (state) {
    InstanceRuntimeState.CHECKING -> "检查中"
    InstanceRuntimeState.STOPPED -> "已停止"
    InstanceRuntimeState.RUNNING -> "运行中"
    InstanceRuntimeState.SSH_READY -> "SSH 正常"
    InstanceRuntimeState.OPERATING -> "操作中"
    InstanceRuntimeState.UNKNOWN -> "状态未知"
    InstanceRuntimeState.ERROR -> "异常"
}
