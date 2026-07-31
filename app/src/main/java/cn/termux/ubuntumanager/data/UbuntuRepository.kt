package cn.termux.ubuntumanager.data

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import cn.termux.ubuntumanager.model.AppUiState
import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.CommandHistoryEntry
import cn.termux.ubuntumanager.model.CommandTag
import cn.termux.ubuntumanager.model.EnvironmentStatus
import cn.termux.ubuntumanager.model.InstanceRuntimeState
import cn.termux.ubuntumanager.model.LocalSessionConnection
import cn.termux.ubuntumanager.model.OperationInfo
import cn.termux.ubuntumanager.model.OperationOutcome
import cn.termux.ubuntumanager.model.TerminalShortcutPreference
import cn.termux.ubuntumanager.model.TermuxWakeLockState
import cn.termux.ubuntumanager.model.UbuntuInstance
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.proot.ProotDistroClient
import cn.termux.ubuntumanager.termux.TermuxCommandClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UbuntuRepository(
    context: Context,
    private val preferences: AppPreferences,
    private val termuxClient: TermuxCommandClient,
    private val prootClient: ProotDistroClient,
) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val refreshMutex = Mutex()
    private val wakeLockMutex = Mutex()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLockConfirmedThisProcess = false
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        preferenceScope.launch {
            preferences.registry.collectLatest { registry ->
                _state.update { current -> current.mergeRegistry(registry) }
            }
        }
    }

    suspend fun refreshAll(
        allowConnectionProbe: Boolean = true,
        showChecking: Boolean = true,
    ) {
        refreshMutex.withLock {
            if (showChecking) {
                _state.update { current ->
                    current.copy(environment = current.environment.copy(checking = true))
                }
            }
            refreshAllInternal(allowConnectionProbe)
        }
    }

    suspend fun refreshRuntime() {
        refreshMutex.withLock {
            if (!_state.value.environment.ready || _state.value.currentOperation != null) return
            try {
                val instances = readInstances(STATUS_COMMAND_TIMEOUT_MILLIS)
                val registry = preferences.snapshot()
                _state.update { current -> current.mergeRegistry(registry).copy(
                    instances = instances,
                    commandHistory = registry.commandHistory,
                    commands = registry.commands,
                    commandTags = registry.commandTags,
                    terminalShortcuts = registry.terminalShortcuts,
                    lastStatusCheckEpochMillis = System.currentTimeMillis(),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                markConnectionUnavailable(error)
            }
        }
    }

    private suspend fun refreshAllInternal(allowConnectionProbe: Boolean) {
        val environment = checkEnvironment(allowConnectionProbe)
        _state.update { it.copy(environment = environment) }
        if (!environment.ready) {
            val registry = preferences.snapshot()
            _state.update { current -> current.mergeRegistry(registry).copy(
                instances = emptyList(),
                backups = emptyList(),
                commandHistory = registry.commandHistory,
                commands = registry.commands,
                commandTags = registry.commandTags,
                terminalShortcuts = registry.terminalShortcuts,
            ) }
            return
        }

        try {
            val backups = if (environment.storageReady) {
                prootClient.listBackups()
            } else {
                emptyList()
            }
            val instances = readInstances()
            val registry = preferences.snapshot()
            _state.update { current -> current.mergeRegistry(registry).copy(
                instances = instances,
                backups = backups,
                commandHistory = registry.commandHistory,
                commands = registry.commands,
                commandTags = registry.commandTags,
                terminalShortcuts = registry.terminalShortcuts,
                lastStatusCheckEpochMillis = System.currentTimeMillis(),
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            markRuntimeUnknown(error)
        }
    }

    private suspend fun readInstances(
        timeoutMillis: Long = TermuxCommandClient.DEFAULT_TIMEOUT_MILLIS,
    ): List<UbuntuInstance> {
        val names = prootClient.listContainers(timeoutMillis).toSet()
        preferences.protectDiscovered(names)
        var registry = preferences.snapshot()
        val assignedPorts = assignPorts(names, registry.ports)
        if (assignedPorts != registry.ports) {
            preferences.saveAssignedPorts(assignedPorts)
            registry = preferences.snapshot()
        }

        val sessions = prootClient.listSessions(timeoutMillis)
        val hostAddress = findLocalIpv4Address()
        val instances = names.sorted().map { name ->
            val matchingSessions = sessions.filter { it.container == name }
            val port = assignedPorts.getValue(name)
            val portOpen = if (matchingSessions.isNotEmpty()) {
                prootClient.isPortOpen(port, timeoutMillis.coerceAtMost(10_000))
            } else {
                false
            }
            UbuntuInstance(
                name = name,
                isProtected = name in registry.protectedNames,
                isManaged = name in registry.managedNames,
                sshPort = port,
                hasLocalSession = matchingSessions.any { session ->
                    session.command.contains("UBUNTU_MANAGER_PERSISTENT_SESSION")
                },
                hostAddress = hostAddress,
                state = when {
                    portOpen -> InstanceRuntimeState.SSH_READY
                    matchingSessions.isNotEmpty() -> InstanceRuntimeState.RUNNING
                    else -> InstanceRuntimeState.STOPPED
                },
                uptime = matchingSessions.firstOrNull()?.uptime,
            )
        }
        syncTermuxBackgroundProtection(instances)
        return instances
    }

    private suspend fun syncTermuxBackgroundProtection(instances: List<UbuntuInstance>) {
        wakeLockMutex.withLock {
            val registry = preferences.snapshot()
            val shouldBeActive = TermuxWakeLockPolicy.shouldHold(registry, instances)
            if (
                shouldBeActive == registry.termuxBackgroundProtectionActive &&
                (!shouldBeActive || wakeLockConfirmedThisProcess)
            ) {
                updateStableWakeLockState(registry)
                return
            }

            updateWakeLockState(
                if (shouldBeActive) {
                    TermuxWakeLockState.ACQUIRING
                } else {
                    TermuxWakeLockState.RELEASING
                },
                if (shouldBeActive) "正在请求 Termux 持有 CPU 唤醒锁" else "正在释放 Termux CPU 唤醒锁",
            )
            val result = runCatching {
                if (shouldBeActive) {
                    prootClient.acquireWakeLock()
                } else {
                    prootClient.releaseWakeLock()
                }
            }.getOrElse { error ->
                updateWakeLockState(
                    TermuxWakeLockState.ERROR,
                    "WakeLock 操作失败：${error.userMessage()}",
                )
                return
            }
            if (!result.isSuccess) {
                updateWakeLockState(
                    TermuxWakeLockState.ERROR,
                    "WakeLock 操作失败：${result.bestError}",
                )
                return
            }

            wakeLockConfirmedThisProcess = shouldBeActive
            preferences.setTermuxBackgroundProtectionActive(shouldBeActive)
            updateStableWakeLockState(preferences.snapshot())
        }
    }

    suspend fun ensureBackgroundOperationWakeLock() {
        syncTermuxBackgroundProtection(_state.value.instances)
    }

    suspend fun reconcileTermuxWakeLock() {
        syncTermuxBackgroundProtection(_state.value.instances)
    }

    private fun AppUiState.mergeRegistry(registry: RegistrySnapshot): AppUiState {
        val stableState = registry.stableWakeLockState()
        val keepTransientOrError = termuxWakeLockState == TermuxWakeLockState.ACQUIRING ||
            termuxWakeLockState == TermuxWakeLockState.RELEASING ||
            termuxWakeLockState == TermuxWakeLockState.ERROR
        return copy(
            backgroundOperation = registry.backgroundOperation,
            hideMaintenanceFromRecents = registry.hideMaintenanceFromRecents,
            termuxBackgroundProtection = registry.termuxBackgroundProtection,
            termuxWakeLockAlwaysOn = registry.termuxWakeLockAlwaysOn,
            termuxWakeLockState = if (
                !registry.termuxBackgroundProtection || !keepTransientOrError
            ) {
                stableState
            } else {
                termuxWakeLockState
            },
            termuxWakeLockMessage = if (
                !registry.termuxBackgroundProtection || !keepTransientOrError
            ) {
                registry.stableWakeLockMessage()
            } else {
                termuxWakeLockMessage
            },
        )
    }

    private fun RegistrySnapshot.stableWakeLockState(): TermuxWakeLockState = when {
        !termuxBackgroundProtection -> TermuxWakeLockState.DISABLED
        termuxBackgroundProtectionActive -> TermuxWakeLockState.HELD
        else -> TermuxWakeLockState.IDLE
    }

    private fun RegistrySnapshot.stableWakeLockMessage(): String = when {
        !termuxBackgroundProtection -> "自动管理已关闭"
        termuxBackgroundProtectionActive && termuxWakeLockAlwaysOn ->
            "持续保持模式已启用"
        termuxBackgroundProtectionActive -> "Termux 已持有全局 CPU 唤醒锁"
        termuxWakeLockAlwaysOn -> "等待 Termux 连接后加锁"
        else -> "没有运行中的实例、会话或后台维护任务"
    }

    private fun updateStableWakeLockState(
        registry: RegistrySnapshot,
        message: String = registry.stableWakeLockMessage(),
    ) {
        updateWakeLockState(registry.stableWakeLockState(), message)
    }

    private fun updateWakeLockState(state: TermuxWakeLockState, message: String) {
        _state.update { current ->
            current.copy(termuxWakeLockState = state, termuxWakeLockMessage = message)
        }
    }

    private fun markRuntimeUnknown(error: Exception) {
        val message = error.userMessage()
        _state.value = _state.value.copy(
            instances = _state.value.instances.map {
                it.copy(state = InstanceRuntimeState.UNKNOWN, message = message)
            },
            lastStatusCheckEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun markConnectionUnavailable(error: Exception) {
        val message = error.userMessage()
        _state.value = _state.value.copy(
            environment = _state.value.environment.copy(
                checking = false,
                connectionAvailable = false,
                prootCompatible = false,
                error = message,
            ),
            instances = _state.value.instances.map {
                it.copy(state = InstanceRuntimeState.UNKNOWN, message = message)
            },
            lastStatusCheckEpochMillis = System.currentTimeMillis(),
        )
    }

    suspend fun start(name: String) = runOperation(
        OperationInfo(label = "正在启动 $name", instanceName = name),
    ) {
        val instance = requireInstance(name)
        val sessions = prootClient.listSessions().filter { it.container == name }
        if (sessions.isEmpty() && prootClient.isPortOpen(instance.sshPort)) {
            error("端口 ${instance.sshPort} 已被其他程序占用")
        }
        if (sessions.isNotEmpty() && prootClient.isPortOpen(instance.sshPort)) {
            return@runOperation "$name 已经在运行"
        }
        val result = prootClient.startSsh(name, instance.sshPort)
        check(result.isSuccess) { result.bestError }
        "$name 启动命令已发送"
    }

    suspend fun stop(name: String, force: Boolean = false) = runOperation(
        OperationInfo(
            label = if (force) "正在强制停止 $name" else "正在停止 $name",
            instanceName = name,
        ),
    ) {
        requireInstance(name)
        val sessions = prootClient.listSessions().filter { it.container == name }
        if (sessions.isEmpty()) return@runOperation "$name 已停止"
        val result = prootClient.stop(name, force)
        check(result.isSuccess) { result.bestError }
        if (force) "$name 已强制停止" else "$name 已停止"
    }

    suspend fun create(
        name: String,
        port: Int,
        initializeSsh: Boolean,
        startAfterCreate: Boolean,
        protectAfterCreate: Boolean,
    ) = runOperation(
        OperationInfo(label = "正在创建 $name", instanceName = name),
    ) {
        ProotDistroClient.requireValidName(name)
        ProotDistroClient.requireValidPort(port)
        val existing = prootClient.listContainers()
        require(name !in existing) { "实例 $name 已经存在" }
        val registry = preferences.snapshot()
        require(port !in registry.ports.values) { "SSH 端口 $port 已被其他实例使用" }
        require(!prootClient.isPortOpen(port)) { "端口 $port 已被其他程序占用" }

        val installResult = prootClient.create(name)
        check(installResult.isSuccess) { installResult.bestError }
        preferences.registerManaged(name, port, protected = protectAfterCreate)

        if (initializeSsh) {
            val sshResult = prootClient.initializeSsh(name)
            if (!sshResult.isSuccess) {
                return@runOperation "$name 已创建，但 SSH 初始化失败：${sshResult.bestError}"
            }
        }

        if (startAfterCreate) {
            require(initializeSsh) { "立即启动需要同时初始化 SSH" }
            val startResult = prootClient.startSsh(name, port)
            if (!startResult.isSuccess) {
                return@runOperation "$name 已创建，但启动失败：${startResult.bestError}"
            }
        }
        "$name 创建成功"
    }

    suspend fun openLocalSession(name: String): LocalSessionConnection {
        val instance = requireInstance(name)
        prootClient.activeContainerMaintenance(name)?.let { operation ->
            val label = when (operation) {
                "backup" -> "备份"
                "restore" -> "恢复"
                "remove" -> "删除"
                "rename" -> "重命名"
                "install" -> "安装"
                else -> "维护"
            }
            error("检测到 $name 正在执行$label，请等待任务结束后再打开本地会话")
        }
        prootClient.existingLocalTerminalPort(name)?.let { existingPort ->
            if (prootClient.isPortOpen(existingPort)) {
                val hardened = prootClient.hardenExistingLocalTerminal(name)
                check(hardened.isSuccess) {
                    "升级旧本地会话失败：${hardened.bestError}"
                }
                refreshRuntime()
                return LocalSessionConnection(
                    port = existingPort,
                    backend = prootClient.localTerminalBackend(name),
                )
            }
        }
        _state.value = _state.value.copy(
            instances = _state.value.instances.map {
                if (it.name == name) {
                    it.copy(
                        state = InstanceRuntimeState.OPERATING,
                        message = "正在启动实例并准备本地会话",
                    )
                } else {
                    it
                }
            },
        )
        try {
            val sessions = prootClient.listSessions().filter { it.container == name }
            val sshPortOpen = prootClient.isPortOpen(instance.sshPort)
            if (sessions.isEmpty() && sshPortOpen) {
                error("SSH 端口 ${instance.sshPort} 已被其他程序占用")
            }
            if (!sshPortOpen) {
                val startResult = prootClient.startSsh(name, instance.sshPort)
                check(startResult.isSuccess) {
                    "启动实例失败：${startResult.bestError}"
                }
            }

            var port: Int? = null
            for (candidate in LOCAL_SESSION_PORT_START..LOCAL_SESSION_PORT_END) {
                if (!prootClient.isPortOpen(candidate)) {
                    port = candidate
                    break
                }
            }
            val selectedPort = port ?: error("没有可用的本地会话端口")

            val component = prootClient.ensureLocalTerminal(name)
            check(component.isSuccess) {
                "安装本地会话组件失败：${component.bestError}"
            }
            val started = prootClient.startLocalTerminal(name, selectedPort)
            check(started.isSuccess) { started.bestError }

            repeat(LOCAL_SESSION_CONNECT_ATTEMPTS) {
                if (prootClient.isPortOpen(selectedPort)) {
                    refreshRuntime()
                    return LocalSessionConnection(
                        port = selectedPort,
                        backend = prootClient.localTerminalBackend(name),
                    )
                }
                delay(LOCAL_SESSION_CONNECT_DELAY_MILLIS)
            }

            val logs = prootClient.localTerminalLogs(name)
            runCatching { prootClient.stopLocalTerminal(name, selectedPort) }
            error(
                buildString {
                    append("本地会话服务没有开始监听")
                    if (logs.isNotBlank()) append("：").append(logs.takeLast(600))
                },
            )
        } catch (error: Exception) {
            refreshRuntime()
            throw error
        }
    }

    suspend fun closeLocalSession(name: String, port: Int) {
        // ttyd 与 dtach 共同构成后台会话。离开页面只断开 WebView，
        // 服务保持监听，下一次从同一个 PRoot 身份边界重新连接。
    }

    suspend fun endLocalSession(name: String, port: Int?) {
        if (port != null) {
            runCatching { prootClient.stopLocalTerminal(name, port) }
        }
        val result = prootClient.endPersistentLocalSession(name)
        check(result.isSuccess) { result.bestError }
        _state.value = _state.value.copy(lastMessage = "$name 的后台会话已结束")
        refreshRuntime()
    }

    suspend fun verifyStorageLinksAndPrepareBackupDirectory() = runOperation(
        OperationInfo(label = "正在验证 Termux 目录链接"),
    ) {
        val probe = prootClient.storageProbe()
        check(probe.linksPresent) { "~/storage/downloads 链接没有创建成功" }
        check(probe.writeTestPassed) {
            "目录链接已经创建，但普通 Termux 用户无法写入 Download"
        }
        val result = prootClient.prepareBackupDirectory()
        check(result.isSuccess) { result.bestError }
        "Termux 目录链接创建成功，Download/UbuntuManager 已可用"
    }

    suspend fun saveCommandTags(tags: List<CommandTag>) {
        preferences.saveCommandTags(tags)
        refreshCommandMetadata("指令标签已保存")
    }

    suspend fun saveCommand(command: UserCommand) {
        preferences.saveCommand(command)
        refreshCommandMetadata("指令已保存")
    }

    suspend fun deleteCommand(commandId: String) {
        preferences.deleteCommand(commandId)
        refreshCommandMetadata("指令已删除")
    }

    suspend fun saveTerminalShortcuts(shortcuts: List<TerminalShortcutPreference>) {
        preferences.saveTerminalShortcuts(shortcuts)
        refreshCommandMetadata("终端快捷键已保存")
    }

    private suspend fun refreshCommandMetadata(message: String) {
        val registry = preferences.snapshot()
        _state.value = _state.value.copy(
            commands = registry.commands,
            commandTags = registry.commandTags,
            terminalShortcuts = registry.terminalShortcuts,
            lastMessage = message,
        )
    }

    suspend fun setProtection(name: String, protected: Boolean) {
        requireInstance(name)
        preferences.setProtected(name, protected)
        _state.value = _state.value.copy(
            lastMessage = if (protected) "$name 已重新启用保护" else "$name 已解除保护",
        )
        refreshRuntime()
    }

    suspend fun updateSshPort(name: String, newPort: Int) = runOperation(
        OperationInfo(label = "正在修改 $name 的 SSH 端口", instanceName = name),
    ) {
        val instance = requireInstance(name)
        ProotDistroClient.requireValidPort(newPort)
        if (newPort == instance.sshPort) return@runOperation "SSH 端口没有变化"
        val registry = preferences.snapshot()
        require(
            registry.ports.none { (otherName, port) -> otherName != name && port == newPort },
        ) { "SSH 端口 $newPort 已分配给其他实例" }
        require(!prootClient.isPortOpen(newPort)) { "端口 $newPort 已被其他程序占用" }

        val sessions = prootClient.listSessions().filter { it.container == name }
        val managedSshRunning = sessions.isNotEmpty() &&
            prootClient.isPortOpen(instance.sshPort)
        if (!managedSshRunning) {
            preferences.updatePort(name, newPort)
            return@runOperation "$name 的 SSH 端口已改为 $newPort，下次启动时生效"
        }

        val stopResult = prootClient.stop(name)
        check(stopResult.isSuccess) { stopResult.bestError }
        preferences.updatePort(name, newPort)
        val startResult = prootClient.startSsh(name, newPort)
        if (startResult.isSuccess) {
            return@runOperation "$name 已改用 SSH 端口 $newPort 并重新启动"
        }

        preferences.updatePort(name, instance.sshPort)
        val rollback = prootClient.startSsh(name, instance.sshPort)
        check(rollback.isSuccess) {
            "新端口启动失败：${startResult.bestError}；恢复原端口也失败：${rollback.bestError}"
        }
        error("新端口启动失败，已恢复原端口 ${instance.sshPort}：${startResult.bestError}")
    }

    suspend fun rename(name: String, newName: String) = runOperation(
        OperationInfo(label = "正在将 $name 重命名为 $newName", instanceName = name),
    ) {
        val instance = requireInstance(name)
        require(!instance.isProtected) { "请先解除实例保护再重命名" }
        ProotDistroClient.requireValidName(newName)
        require(name != newName) { "新旧实例名称不能相同" }
        require(newName !in prootClient.listContainers()) { "实例 $newName 已经存在" }
        val sessions = prootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "请先停止实例再重命名" }

        val result = prootClient.rename(name, newName)
        check(result.isSuccess) { result.bestError }
        try {
            preferences.renameInstance(name, newName)
        } catch (error: Exception) {
            val rollback = prootClient.rename(newName, name)
            check(rollback.isSuccess) {
                "管理数据迁移失败且名称回滚失败，请不要操作实例并重新刷新"
            }
            throw error
        }
        "$name 已重命名为 $newName"
    }

    suspend fun initializeSsh(name: String) = runOperation(
        OperationInfo(label = "正在初始化 $name 的 SSH", instanceName = name),
    ) {
        requireInstance(name)
        val sessions = prootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "请先停止实例再初始化 SSH" }
        val result = prootClient.initializeSsh(name)
        check(result.isSuccess) { result.bestError }
        "$name 的 SSH 已初始化"
    }

    suspend fun delete(name: String) = runOperation(
        OperationInfo(label = "正在删除 $name", instanceName = name),
    ) {
        val instance = requireInstance(name)
        require(!instance.isProtected) { "受保护实例禁止删除" }

        val sessions = prootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "请先停止实例再永久删除" }
        val removeResult = prootClient.remove(name)
        check(removeResult.isSuccess) { removeResult.bestError }
        preferences.removeInstance(name)
        "$name 已永久删除"
    }

    suspend fun backup(name: String) = runOperation(
        OperationInfo(label = "正在备份 $name", instanceName = name),
    ) {
        require(_state.value.environment.storageReady) {
            "备份目录不可用，请在设置中使用 Magisk Alpha 创建目录链接"
        }
        requireInstance(name)
        val sessions = prootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "为保证数据一致，请先停止实例再备份" }
        val path = prootClient.backup(name)
        "备份完成：${path.substringAfterLast('/')}"
    }

    suspend fun restore(backup: BackupEntry) = runOperation(
        OperationInfo(label = "正在恢复 ${backup.instanceName}", instanceName = backup.instanceName),
    ) {
        val embeddedName = prootClient.validateBackup(backup)
        require(embeddedName == backup.instanceName) { "备份文件名和内部实例名称不一致" }
        val current = _state.value.instances.firstOrNull { it.name == embeddedName }
        if (current != null) {
            require(!current.isProtected) {
                "受保护实例不能通过普通恢复覆盖，请保留备份并使用灾难恢复流程"
            }
            val sessions = prootClient.listSessions().filter { it.container == embeddedName }
            require(sessions.isEmpty()) { "请先停止实例再恢复" }
        }
        val result = prootClient.restore(backup)
        check(result.isSuccess) { result.bestError }
        if (current == null) {
            preferences.registerManaged(embeddedName, nextAvailablePort(), protected = true)
        }
        "$embeddedName 恢复完成"
    }

    suspend fun deleteBackup(backup: BackupEntry) = runOperation(
        OperationInfo(label = "正在删除 ${backup.fileName}"),
    ) {
        val result = prootClient.deleteBackup(backup)
        check(result.isSuccess) { result.bestError }
        "备份 ${backup.fileName} 已删除"
    }

    suspend fun executeLibraryCommand(name: String, commandId: String) = runOperation(
        OperationInfo(label = "正在执行指令", instanceName = name),
    ) {
        requireInstance(name)
        val command = _state.value.commands.firstOrNull { it.id == commandId }
            ?: error("指令不存在或已被移除")
        val result = prootClient.executeCommand(name, command)
        val output = buildString {
            if (result.stdout.isNotBlank()) append(result.stdout.trim())
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(result.stderr.trim())
            }
        }.takeLast(MAX_COMMAND_OUTPUT)
        preferences.addCommandHistory(
            CommandHistoryEntry(
                timestampEpochMillis = System.currentTimeMillis(),
                commandId = command.id,
                commandTitle = command.title,
                instanceName = name,
                exitCode = result.exitCode,
                output = output.ifBlank { "命令没有输出。" },
            ),
        )
        check(result.isSuccess) { result.bestError }
        "${command.title}执行完成"
    }

    suspend fun cloneInstance(
        sourceName: String,
        targetName: String,
        targetPort: Int,
        protectTarget: Boolean,
    ) = runOperation(
        OperationInfo(label = "正在复制 $sourceName", instanceName = sourceName),
    ) {
        requireInstance(sourceName)
        ProotDistroClient.requireValidName(targetName)
        ProotDistroClient.requireValidPort(targetPort)
        require(sourceName != targetName) { "新实例名称不能与源实例相同" }
        val sessions = prootClient.listSessions().filter { it.container == sourceName }
        require(sessions.isEmpty()) { "为保证数据一致，请先停止源实例" }
        val existing = prootClient.listContainers()
        require(targetName !in existing) { "实例 $targetName 已经存在" }
        val registry = preferences.snapshot()
        require(targetPort !in registry.ports.values) { "SSH 端口 $targetPort 已被其他实例使用" }
        require(!prootClient.isPortOpen(targetPort)) { "端口 $targetPort 已被其他程序占用" }

        val cloneResult = prootClient.cloneInstance(sourceName, targetName)
        if (!cloneResult.isSuccess) {
            val createdTarget = runCatching {
                targetName in prootClient.listContainers()
            }.getOrDefault(false)
            if (createdTarget) {
                prootClient.remove(targetName)
            }
            error(cloneResult.bestError)
        }

        preferences.registerManaged(targetName, targetPort, protected = protectTarget)
        val identityResult = prootClient.initializeCloneIdentity(targetName)
        if (!identityResult.isSuccess) {
            return@runOperation "$targetName 已复制并保持停止，但独立身份初始化失败：" +
                identityResult.bestError
        }
        "$targetName 复制完成，已分配 SSH 端口 $targetPort"
    }

    suspend fun logs(name: String): String {
        requireInstance(name)
        return prootClient.logs(name)
    }

    fun openTermux(): Boolean = termuxClient.openTermux()

    fun clearMessage() {
        _state.value = _state.value.copy(lastMessage = null)
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(lastMessage = message)
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        preferences.setDynamicColors(enabled)
    }

    suspend fun setHideMaintenanceFromRecents(enabled: Boolean) {
        preferences.setHideMaintenanceFromRecents(enabled)
    }

    suspend fun setTermuxBackgroundProtection(enabled: Boolean) {
        preferences.setTermuxBackgroundProtection(enabled)
        reconcileTermuxWakeLock()
    }

    suspend fun setTermuxWakeLockAlwaysOn(enabled: Boolean) {
        preferences.setTermuxWakeLockAlwaysOn(enabled)
        reconcileTermuxWakeLock()
    }

    suspend fun reconcileBackgroundOperation(serviceActive: Boolean) {
        val record = preferences.snapshot().backgroundOperation ?: return
        if (record.status != BackgroundOperationStatus.RUNNING || serviceActive) return
        val now = System.currentTimeMillis()
        val interrupted = record.copy(
            status = BackgroundOperationStatus.INTERRUPTED,
            updatedEpochMillis = now,
            message = "管理进程曾被结束，任务结果需要重新检查；管理器不会自动重复执行",
        )
        preferences.setBackgroundOperation(interrupted)
        _state.value = _state.value.copy(
            currentOperation = null,
            lastMessage = "检测到未正常收尾的后台任务，已标记为中断并禁止自动重跑",
        )
    }

    suspend fun registrySnapshot(): RegistrySnapshot = preferences.snapshot()

    private suspend fun checkEnvironment(allowConnectionProbe: Boolean): EnvironmentStatus {
        val installed = termuxClient.isTermuxInstalled()
        val permission = termuxClient.hasRunCommandPermission()
        val version = termuxClient.termuxVersion()
        val termuxStopped = installed && termuxClient.isTermuxStopped()
        if (!installed || !permission) {
            return EnvironmentStatus(
                checking = false,
                termuxInstalled = installed,
                termuxVersion = version,
                termuxStopped = termuxStopped,
                permissionGranted = permission,
                error = when {
                    !installed -> "没有检测到官方 Termux"
                    else -> "需要授予“在 Termux 中运行命令”权限"
                },
            )
        }

        val externalAppsConfigured = preferences.snapshot().externalAppsConfigured
        if (termuxStopped) {
            wakeLockConfirmedThisProcess = false
            if (preferences.snapshot().termuxBackgroundProtectionActive) {
                preferences.setTermuxBackgroundProtectionActive(false)
            }
            return EnvironmentStatus(
                checking = false,
                termuxInstalled = true,
                termuxVersion = version,
                termuxStopped = true,
                permissionGranted = true,
                externalAppsConfigured = externalAppsConfigured,
                error = "Termux 已被系统完全停止，请先打开 Termux；返回管理器后会自动重新连接",
            )
        }
        if (!externalAppsConfigured && !allowConnectionProbe) {
            return EnvironmentStatus(
                checking = false,
                termuxInstalled = true,
                termuxVersion = version,
                permissionGranted = true,
                externalAppsConfigured = false,
                error = "请先完成“允许外部应用”设置，再进行连接测试",
            )
        }

        return try {
            val versionResult = prootClient.version(CONNECTION_PROBE_TIMEOUT_MILLIS)
            val prootVersion =
                prootClient.parseVersion(versionResult.stdout + "\n" + versionResult.stderr)
            val connected = versionResult.internalErrorCode == -1
            val compatible = versionResult.isSuccess
            val configurationConfirmed = when {
                connected -> true
                versionResult.internalErrorCode == TERMUX_POLICY_ERROR_CODE -> false
                else -> externalAppsConfigured
            }
            if (connected) {
                preferences.setExternalAppsConfigured(true)
            } else if (versionResult.internalErrorCode == TERMUX_POLICY_ERROR_CODE) {
                preferences.setExternalAppsConfigured(false)
            }
            val storageProbe = if (connected) {
                prootClient.storageProbe(CONNECTION_PROBE_TIMEOUT_MILLIS)
            } else {
                cn.termux.ubuntumanager.model.StorageProbe(false, false, false)
            }
            val readStorageGranted =
                termuxClient.termuxPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
            val writeStorageGranted =
                termuxClient.termuxPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val allFilesGranted = termuxClient.termuxAllFilesAccessGranted()
            EnvironmentStatus(
                checking = false,
                termuxInstalled = true,
                termuxVersion = version,
                permissionGranted = true,
                externalAppsConfigured = configurationConfirmed,
                connectionAvailable = connected,
                prootVersion = prootVersion,
                prootCompatible = compatible,
                storageReady = storageProbe.writeTestPassed,
                termuxReadStorageGranted = readStorageGranted,
                termuxWriteStorageGranted = writeStorageGranted,
                termuxAllFilesGranted = allFilesGranted,
                storageDirectoryPresent = storageProbe.directoryPresent,
                storageLinksPresent = storageProbe.linksPresent,
                storageWriteTestPassed = storageProbe.writeTestPassed,
                error = when {
                    !connected -> versionResult.bestError
                    !compatible -> "需要 proot-distro 5.1 或更高版本"
                    !storageProbe.writeTestPassed -> "Termux 存储权限或目录尚未准备完成"
                    else -> null
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnvironmentStatus(
                checking = false,
                termuxInstalled = true,
                termuxVersion = version,
                permissionGranted = true,
                externalAppsConfigured = externalAppsConfigured,
                error = error.userMessage(),
            )
        }
    }

    private suspend fun runOperation(
        operation: OperationInfo,
        block: suspend () -> String,
    ): OperationOutcome {
        val outcome = operationMutex.withLock {
            _state.value = _state.value.copy(currentOperation = operation, lastMessage = null)
            val result = try {
                OperationOutcome(succeeded = true, message = block())
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(
                    currentOperation = null,
                    lastMessage = "${operation.label}已中断，重新操作前请先检查实例状态",
                )
                throw cancelled
            } catch (error: Exception) {
                OperationOutcome(
                    succeeded = false,
                    message = "操作失败：${error.userMessage()}",
                )
            }
            _state.value = _state.value.copy(
                currentOperation = null,
                lastMessage = result.message,
            )
            result
        }
        refreshAll()
        return outcome
    }

    private fun requireInstance(name: String): UbuntuInstance =
        _state.value.instances.firstOrNull { it.name == name }
            ?: error("实例 $name 不存在")

    private fun assignPorts(
        names: Set<String>,
        existing: Map<String, Int>,
    ): Map<String, Int> {
        val assigned = existing.filterKeys { it in names }.toMutableMap()
        val used = assigned.values.toMutableSet()
        for (name in names.sorted()) {
            if (name !in assigned) {
                val port = (DEFAULT_SSH_PORT..65535).first { it !in used }
                assigned[name] = port
                used += port
            }
        }
        return assigned
    }

    private suspend fun nextAvailablePort(): Int {
        val used = preferences.snapshot().ports.values.toSet()
        return (DEFAULT_SSH_PORT..65535).first { it !in used }
    }

    private fun Throwable.userMessage(): String =
        message?.substringAfterLast("IllegalStateException: ")?.ifBlank { null }
            ?: this::class.java.simpleName

    private fun findLocalIpv4Address(): String? = runCatching {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching null
        connectivityManager.getLinkProperties(activeNetwork)
            ?.linkAddresses
            .orEmpty()
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val DEFAULT_SSH_PORT = 2222
        private const val TERMUX_POLICY_ERROR_CODE = 2
        private const val MAX_COMMAND_OUTPUT = 4_000
        private const val LOCAL_SESSION_PORT_START = 32100
        private const val LOCAL_SESSION_PORT_END = 32999
        private const val LOCAL_SESSION_CONNECT_ATTEMPTS = 30
        private const val LOCAL_SESSION_CONNECT_DELAY_MILLIS = 200L
        private const val CONNECTION_PROBE_TIMEOUT_MILLIS = 5_000L
        private const val STATUS_COMMAND_TIMEOUT_MILLIS = 5_000L
    }
}
