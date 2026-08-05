package cn.termux.ubuntumanager.data

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
import cn.termux.ubuntumanager.model.UbuntuInstance
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.model.UserCommandType
import cn.termux.ubuntumanager.chroot.ChrootClient
import cn.termux.ubuntumanager.chroot.ChrootContract
import cn.termux.ubuntumanager.chroot.ChrootRuntimeRecord
import cn.termux.ubuntumanager.permission.RootCommandExecutor
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
    private val rootPasswordStore: RootPasswordStore,
    private val rootExecutor: RootCommandExecutor,
    private val chrootClient: ChrootClient,
) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val refreshMutex = Mutex()
    private val runtimeStateHints = mutableMapOf<String, InstanceRuntimeState>()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    /** Restores the manager UI without opening su or reading Root-only directories. */
    suspend fun loadCachedState() {
        refreshMutex.withLock {
            val registry = preferences.snapshot()
            val alphaVersion = rootExecutor.alphaVersion()
            val backendConfigured = registry.firstDiscoveryCompleted || registry.ports.isNotEmpty()
            _state.update { current -> current.copy(
                environment = EnvironmentStatus(
                    checking = false,
                    alphaInstalled = alphaVersion != null,
                    alphaVersion = alphaVersion,
                    rootGranted = alphaVersion != null && backendConfigured,
                    backendReady = alphaVersion != null && backendConfigured,
                    backendVersion = if (backendConfigured) {
                        "Root Chroot ${ChrootContract.VERSION} · " +
                            "Ubuntu ${ChrootContract.UBUNTU_VERSION}"
                    } else {
                        null
                    },
                    backupReady = alphaVersion != null && backendConfigured,
                    autoStartReady = registry.autoStartBackendReady,
                    error = when {
                        alphaVersion == null -> "没有检测到受支持的 Magisk Alpha"
                        !backendConfigured -> "尚未配置 Root Chroot 后端"
                        else -> null
                    },
                ),
                backups = registry.backups,
                backupsSyncing = false,
                backupsVerified = false,
            ) }
            reconcileCachedInstances()
        }
        refreshRuntime()
    }

    suspend fun refreshRuntime() {
        refreshMutex.withLock {
            if (!_state.value.environment.ready || _state.value.currentOperation != null) return
            try {
                val hostAddress = findLocalIpv4Address()
                val instances = _state.value.instances.map { instance ->
                    val sshOpen = chrootClient.isPortOpen(
                        instance.sshPort,
                        STATUS_COMMAND_TIMEOUT_MILLIS,
                    )
                    val localSessionOpen = instance.localSessionPort?.let { port ->
                        chrootClient.isPortOpen(port, STATUS_COMMAND_TIMEOUT_MILLIS)
                    } ?: false
                    val observedState = when {
                        sshOpen -> InstanceRuntimeState.SSH_READY
                        localSessionOpen -> InstanceRuntimeState.RUNNING
                        instance.state == InstanceRuntimeState.STOPPED ->
                            InstanceRuntimeState.STOPPED
                        instance.state == InstanceRuntimeState.OPERATING ->
                            InstanceRuntimeState.OPERATING
                        else -> InstanceRuntimeState.UNKNOWN
                    }
                    instance.copy(
                        hasLocalSession = localSessionOpen,
                        localSessionPort = instance.localSessionPort.takeIf {
                            localSessionOpen
                        },
                        hostAddress = hostAddress,
                        state = observedState,
                        message = if (observedState == InstanceRuntimeState.UNKNOWN) {
                            "运行端口未响应，点击刷新确认实际状态"
                        } else {
                            null
                        },
                    )
                }
                if (_state.value.currentOperation != null) return
                val registry = preferences.snapshot()
                val runtimeByName = instances.associateBy { it.name }
                _state.update { current ->
                    val merged = current.mergeRegistry(registry)
                    merged.copy(
                        instances = merged.instances.map { currentInstance ->
                            val observed = runtimeByName[currentInstance.name]
                                ?: return@map currentInstance
                            currentInstance.copy(
                                hasLocalSession = observed.hasLocalSession,
                                localSessionPort = observed.localSessionPort,
                                hostAddress = observed.hostAddress,
                                state = if (
                                    currentInstance.state == InstanceRuntimeState.OPERATING
                                ) {
                                    InstanceRuntimeState.OPERATING
                                } else {
                                    observed.state
                                },
                                uptime = observed.uptime,
                                message = if (
                                    currentInstance.state == InstanceRuntimeState.OPERATING
                                ) {
                                    currentInstance.message
                                } else {
                                    observed.message
                                },
                            )
                        },
                        commandHistory = registry.commandHistory,
                        commands = registry.commands,
                        commandTags = registry.commandTags,
                        terminalShortcuts = registry.terminalShortcuts,
                        lastStatusCheckEpochMillis = System.currentTimeMillis(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                markConnectionUnavailable(error)
            }
        }
    }

    private suspend fun refreshAllInternal(allowConnectionProbe: Boolean) {
        @Suppress("UNUSED_VARIABLE") val ignoredLegacyProbeFlag = allowConnectionProbe
        val alphaVersion = rootExecutor.alphaVersion()
        if (alphaVersion == null) {
            val environment = EnvironmentStatus(
                checking = false,
                alphaInstalled = false,
                error = "没有检测到受支持的 Magisk Alpha",
            )
            _state.update { it.copy(environment = environment) }
            applyUnavailableEnvironmentState()
            return
        }
        val inspection = chrootClient.inspect(CONNECTION_PROBE_TIMEOUT_MILLIS)
        val environment = EnvironmentStatus(
            checking = false,
            alphaInstalled = true,
            alphaVersion = alphaVersion,
            rootGranted = inspection.rootGranted,
            backendReady = inspection.backendReady,
            backendVersion = inspection.backendVersion,
            backupReady = inspection.backupReady,
            autoStartReady = inspection.autoStart.scriptReady,
            error = inspection.error,
        )
        _state.update { it.copy(environment = environment) }
        if (!environment.ready) {
            applyUnavailableEnvironmentState()
            return
        }

        try {
            preferences.setAutoStartSnapshot(
                backendReady = inspection.autoStart.scriptReady,
                enabled = inspection.autoStart.enabled,
                names = inspection.autoStart.instances.keys,
            )
            val cachedBackups = preferences.snapshot().backups
            val backups = if (environment.backupReady) {
                mergeBackupCatalog(inspection.backups, cachedBackups)
            } else {
                null
            }
            if (backups != null) preferences.saveBackups(backups)
            val instances = readInstances(inspection.runtimes)
            var registry = preferences.snapshot()
            val recentOperation = registry.backgroundOperation
            if (
                recentOperation?.status == BackgroundOperationStatus.SUCCEEDED &&
                recentOperation.instanceName != null &&
                instances.none { it.name == recentOperation.instanceName }
            ) {
                preferences.setBackgroundOperation(null)
                registry = preferences.snapshot()
            }
            _state.update { current -> current.mergeRegistry(registry).copy(
                instances = instances,
                backups = backups ?: registry.backups,
                backupsSyncing = false,
                backupsVerified = backups != null,
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

    private suspend fun applyUnavailableEnvironmentState() {
        val registry = preferences.snapshot()
        _state.update { current -> current.mergeRegistry(registry).copy(
            instances = emptyList(),
            backupsSyncing = false,
            backupsVerified = false,
            commandHistory = registry.commandHistory,
            commands = registry.commands,
            commandTags = registry.commandTags,
            terminalShortcuts = registry.terminalShortcuts,
        ) }
    }

    private suspend fun readInstances(
        runtimeSnapshot: List<ChrootRuntimeRecord>,
        timeoutMillis: Long = ChrootClient.DEFAULT_TIMEOUT_MILLIS,
    ): List<UbuntuInstance> {
        val names = runtimeSnapshot.map { it.name }.toSet()
        preferences.protectDiscovered(names)
        var registry = preferences.snapshot()
        val assignedPorts = assignPorts(names, registry.ports)
        if (assignedPorts != registry.ports) {
            preferences.saveAssignedPorts(assignedPorts)
            registry = preferences.snapshot()
        }

        val hostAddress = findLocalIpv4Address()
        val savedPasswords = rootPasswordStore.getAll(names)
        val instances = names.sorted().map { name ->
            val runtime = runtimeSnapshot.first { it.name == name }
            val port = assignedPorts.getValue(name)
            val portOpen = if (
                runtime.running && runtime.sshPort == port
            ) {
                chrootClient.isPortOpen(port, timeoutMillis.coerceAtMost(10_000))
            } else {
                false
            }
            val localSessionPort = runtime.localTerminalPort?.takeIf {
                runtime.running && chrootClient.isPortOpen(
                    it,
                    timeoutMillis.coerceAtMost(10_000),
                )
            }
            UbuntuInstance(
                name = name,
                isProtected = name in registry.protectedNames,
                isManaged = name in registry.managedNames,
                sshPort = port,
                hasLocalSession = localSessionPort != null,
                localSessionPort = localSessionPort,
                hostAddress = hostAddress,
                state = when {
                    portOpen -> InstanceRuntimeState.SSH_READY
                    runtime.running -> InstanceRuntimeState.RUNNING
                    else -> InstanceRuntimeState.STOPPED
                },
                uptime = if (runtime.running) "运行中" else null,
                savedRootPassword = savedPasswords[name]?.password,
                savedRootPasswordUpdatedAt = savedPasswords[name]?.updatedAtEpochMillis,
                autoStartEnabled = name in registry.autoStartNames,
            )
        }
        return instances
    }

    private fun AppUiState.mergeRegistry(registry: RegistrySnapshot): AppUiState {
        return copy(
            instances = instances.map { instance ->
                instance.copy(
                    isProtected = instance.name in registry.protectedNames,
                    isManaged = instance.name in registry.managedNames,
                    sshPort = registry.ports[instance.name] ?: instance.sshPort,
                    autoStartEnabled = instance.name in registry.autoStartNames,
                )
            },
            backups = registry.backups,
            backgroundOperation = registry.backgroundOperation,
            hideFromRecentsWhenBackground = registry.hideFromRecentsWhenBackground,
            autoStartEnabled = registry.autoStartEnabled,
            backupRetentionCount = registry.backupRetentionCount,
        )
    }

    suspend fun syncBackups() {
        refreshMutex.withLock {
            if (
                !_state.value.environment.backupReady ||
                _state.value.currentOperation != null ||
                _state.value.backupsSyncing
            ) {
                return
            }
            _state.update { it.copy(backupsSyncing = true) }
            try {
                val backups = mergeBackupCatalog(
                    chrootClient.listBackups(),
                    preferences.snapshot().backups,
                )
                preferences.saveBackups(backups)
                _state.update {
                    it.copy(
                        backups = backups,
                        backupsSyncing = false,
                        backupsVerified = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(backupsSyncing = false) }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        backupsSyncing = false,
                        backupsVerified = false,
                        lastMessage = "备份列表同步失败：${error.userMessage()}；已保留本地缓存",
                    )
                }
            }
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
                backendReady = false,
                error = message,
            ),
            instances = _state.value.instances.map {
                it.copy(state = InstanceRuntimeState.UNKNOWN, message = message)
            },
            lastStatusCheckEpochMillis = System.currentTimeMillis(),
        )
    }

    suspend fun start(name: String) = runOperation(
        OperationInfo(
            label = "正在启动 $name",
            instanceName = name,
            runtimeTransition = true,
        ),
    ) {
        val instance = requireInstance(name)
        val sessions = chrootClient.listSessions().filter { it.container == name }
        val runningPort = if (sessions.isNotEmpty()) chrootClient.runningSshPort(name) else null
        if (chrootClient.isPortOpen(instance.sshPort) && runningPort != instance.sshPort) {
            error("端口 ${instance.sshPort} 已被其他程序占用")
        }
        if (runningPort == instance.sshPort && chrootClient.isPortOpen(instance.sshPort)) {
            hintRuntimeState(name, InstanceRuntimeState.SSH_READY)
            return@runOperation "$name 已经在运行"
        }
        val result = chrootClient.startSsh(name, instance.sshPort)
        check(result.isSuccess) { result.bestError }
        val ready = chrootClient.waitForPortState(
            instance.sshPort,
            expectedOpen = true,
            timeoutMillis = SSH_START_WAIT_MILLIS,
        )
        check(ready.isSuccess) { ready.bestError }
        hintRuntimeState(name, InstanceRuntimeState.SSH_READY)
        "$name 已启动"
    }

    suspend fun stop(name: String, force: Boolean = false) = runOperation(
        OperationInfo(
            label = if (force) "正在强制停止 $name" else "正在停止 $name",
            instanceName = name,
            runtimeTransition = true,
        ),
    ) {
        requireInstance(name)
        val sessions = chrootClient.listSessions().filter { it.container == name }
        if (sessions.isEmpty()) {
            hintRuntimeState(name, InstanceRuntimeState.STOPPED)
            return@runOperation "$name 已停止"
        }
        val result = chrootClient.stop(name, force)
        check(result.isSuccess) { result.bestError }
        hintRuntimeState(name, InstanceRuntimeState.STOPPED)
        if (force) "$name 已强制停止" else "$name 已停止"
    }

    suspend fun restartSsh(name: String) = runOperation(
        OperationInfo(
            label = "正在重启 $name 的 SSH",
            instanceName = name,
            runtimeTransition = true,
        ),
    ) {
        val instance = requireInstance(name)
        val sessions = chrootClient.listSessions().filter { it.container == name }
        require(sessions.isNotEmpty()) { "$name 尚未启动" }
        require(chrootClient.runningSshPort(name) == instance.sshPort) {
            "实例当前监听端口与管理记录不一致，请先停止后重新启动"
        }
        val reload = chrootClient.reloadSsh(name, instance.sshPort)
        check(reload.isSuccess) { reload.bestError }
        val ready = chrootClient.waitForPortState(
            instance.sshPort,
            expectedOpen = true,
            timeoutMillis = SSH_RELOAD_WAIT_MILLIS,
        )
        check(ready.isSuccess) { ready.bestError }
        hintRuntimeState(name, InstanceRuntimeState.SSH_READY)
        "$name 的 SSH 已重新加载，终端和其他后台程序保持运行"
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
        ChrootClient.requireValidName(name)
        ChrootClient.requireValidPort(port)
        val existing = chrootClient.listContainers()
        require(name !in existing) { "实例 $name 已经存在" }
        val registry = preferences.snapshot()
        require(port !in registry.ports.values) { "SSH 端口 $port 已被其他实例使用" }
        require(!chrootClient.isPortOpen(port)) { "端口 $port 已被其他程序占用" }

        val installResult = chrootClient.create(name)
        check(installResult.isSuccess) { installResult.bestError }
        preferences.registerManaged(name, port, protected = protectAfterCreate)
        hintRuntimeState(name, InstanceRuntimeState.STOPPED)
        var passwordRecordWarning: String? = null

        @Suppress("UNUSED_VARIABLE") val legacyInitializeSsh = initializeSsh
        val passwordResult = chrootClient.ensureDefaultRootPassword(name)
        if (!passwordResult.isSuccess) {
            return@runOperation "$name 已创建，但默认 root 密码配置失败：" +
                passwordResult.bestError
        }
        passwordRecordWarning = rememberRootPassword(
            name,
            ChrootClient.DEFAULT_ROOT_PASSWORD,
        )

        if (startAfterCreate) {
            val startResult = chrootClient.startSsh(name, port)
            if (!startResult.isSuccess) {
                return@runOperation "$name 已创建，但启动失败：${startResult.bestError}"
                    .withPasswordRecordWarning(passwordRecordWarning)
            }
            val ready = chrootClient.waitForPortState(
                port,
                expectedOpen = true,
                timeoutMillis = SSH_START_WAIT_MILLIS,
            )
            if (!ready.isSuccess) {
                return@runOperation "$name 已创建，但 SSH 端口没有开始监听：${ready.bestError}"
                    .withPasswordRecordWarning(passwordRecordWarning)
            }
            hintRuntimeState(name, InstanceRuntimeState.SSH_READY)
        }
        "$name 创建成功".withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun openLocalSession(name: String): LocalSessionConnection {
        val instance = requireInstance(name)
        chrootClient.activeContainerMaintenance(name)?.let { operation ->
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
        chrootClient.existingLocalTerminalPort(name)?.let { existingPort ->
            if (chrootClient.isPortOpen(existingPort)) {
                val hardened = chrootClient.hardenExistingLocalTerminal(name)
                check(hardened.isSuccess) {
                    "升级旧本地会话失败：${hardened.bestError}"
                }
                rememberLocalSessionPort(name, existingPort)
                refreshRuntime()
                return LocalSessionConnection(
                    port = existingPort,
                    backend = chrootClient.localTerminalBackend(name),
                    historySnapshot = chrootClient.captureLocalTerminalHistory(name),
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
            val sessions = chrootClient.listSessions().filter { it.container == name }
            val runningPort = if (sessions.isNotEmpty()) chrootClient.runningSshPort(name) else null
            val sshPortOpen = chrootClient.isPortOpen(instance.sshPort)
            if (sshPortOpen && runningPort != instance.sshPort) {
                error("SSH 端口 ${instance.sshPort} 已被其他程序占用")
            }
            if (runningPort != instance.sshPort || !sshPortOpen) {
                val startResult = chrootClient.startSsh(name, instance.sshPort)
                check(startResult.isSuccess) {
                    "启动实例失败：${startResult.bestError}"
                }
            }

            var port: Int? = null
            for (candidate in LOCAL_SESSION_PORT_START..LOCAL_SESSION_PORT_END) {
                if (!chrootClient.isPortOpen(candidate)) {
                    port = candidate
                    break
                }
            }
            val selectedPort = port ?: error("没有可用的本地会话端口")

            val component = chrootClient.ensureLocalTerminal(name)
            check(component.isSuccess) {
                "安装本地会话组件失败：${component.bestError}"
            }
            val started = chrootClient.startLocalTerminal(name, selectedPort)
            check(started.isSuccess) { started.bestError }

            repeat(LOCAL_SESSION_CONNECT_ATTEMPTS) {
                if (chrootClient.isPortOpen(selectedPort)) {
                    rememberLocalSessionPort(name, selectedPort)
                    refreshRuntime()
                    return LocalSessionConnection(
                        port = selectedPort,
                        backend = chrootClient.localTerminalBackend(name),
                        historySnapshot = chrootClient.captureLocalTerminalHistory(name),
                    )
                }
                delay(LOCAL_SESSION_CONNECT_DELAY_MILLIS)
            }

            val logs = chrootClient.localTerminalLogs(name)
            runCatching { chrootClient.stopLocalTerminal(name, selectedPort) }
            error(
                buildString {
                    append("本地会话服务没有开始监听")
                    if (logs.isNotBlank()) append("：").append(logs.takeLast(600))
                },
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                instances = _state.value.instances.map { current ->
                    if (current.name == name) {
                        instance.copy(
                            state = InstanceRuntimeState.UNKNOWN,
                            message = "本地会话准备失败，点击刷新确认实际状态",
                        )
                    } else {
                        current
                    }
                },
            )
            throw error
        }
    }

    suspend fun closeLocalSession(name: String, port: Int) {
        // ttyd 与 dtach 共同构成后台会话。离开页面只断开 WebView，
        // 离开页面只断开 WebView；ttyd 与 tmux 仍留在同一个 Chroot 实例中。
    }

    suspend fun captureLocalTerminalHistory(name: String): String {
        requireInstance(name)
        return chrootClient.captureLocalTerminalHistory(name)
    }

    suspend fun endLocalSession(name: String, port: Int?) {
        if (port != null) {
            runCatching { chrootClient.stopLocalTerminal(name, port) }
        }
        val result = chrootClient.endPersistentLocalSession(name)
        check(result.isSuccess) { result.bestError }
        _state.value = _state.value.copy(
            instances = _state.value.instances.map { instance ->
                if (instance.name == name) {
                    instance.copy(hasLocalSession = false, localSessionPort = null)
                } else {
                    instance
                }
            },
        )
        _state.value = _state.value.copy(lastMessage = "$name 的后台会话已结束")
        refreshRuntime()
    }

    private fun rememberLocalSessionPort(name: String, port: Int) {
        _state.value = _state.value.copy(
            instances = _state.value.instances.map { instance ->
                if (instance.name == name) {
                    instance.copy(
                        hasLocalSession = true,
                        localSessionPort = port,
                        state = InstanceRuntimeState.SSH_READY,
                        message = null,
                    )
                } else {
                    instance
                }
            },
        )
    }

    suspend fun verifyStorageLinksAndPrepareBackupDirectory() = runOperation(
        OperationInfo(label = "正在验证 Root Chroot 备份目录"),
    ) {
        val probe = chrootClient.storageProbe()
        check(probe.writeTestPassed) { "Root Chroot 备份目录不可写" }
        val result = chrootClient.prepareBackupDirectory()
        check(result.isSuccess) { result.bestError }
        "Root Chroot 备份目录已可用"
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
        reconcileCachedInstances()
        _state.update {
            it.copy(
                lastMessage = if (protected) {
                    "$name 已重新启用保护"
                } else {
                    "$name 已解除保护"
                },
            )
        }
    }

    suspend fun setAutoStartEnabled(enabled: Boolean) = runOperation(
        OperationInfo(label = if (enabled) "正在启用开机自动启动" else "正在关闭开机自动启动"),
    ) {
        val configuration = chrootClient.setAutoStartEnabled(enabled)
        preferences.setAutoStartSnapshot(
            backendReady = configuration.scriptReady,
            enabled = configuration.enabled,
            names = configuration.instances.keys,
        )
        if (enabled) {
            "开机自动启动已启用，将启动已勾选的实例"
        } else {
            "开机自动启动已关闭，实例勾选已保留"
        }
    }

    suspend fun setInstanceAutoStart(name: String, enabled: Boolean) = runOperation(
        OperationInfo(
            label = if (enabled) "正在设置 $name 开机启动" else "正在取消 $name 开机启动",
            instanceName = name,
        ),
    ) {
        val instance = requireInstance(name)
        val configuration = chrootClient.setInstanceAutoStart(
            name = name,
            port = instance.sshPort,
            enabled = enabled,
        )
        preferences.setAutoStartSnapshot(
            backendReady = configuration.scriptReady,
            enabled = configuration.enabled,
            names = configuration.instances.keys,
        )
        if (enabled) {
            "$name 已加入开机启动，使用 SSH 端口 ${instance.sshPort}"
        } else {
            "$name 已取消开机启动"
        }
    }

    suspend fun updateSshPort(name: String, newPort: Int) = runOperation(
        OperationInfo(
            label = "正在修改 $name 的 SSH 端口",
            instanceName = name,
            runtimeTransition = true,
        ),
    ) {
        val instance = requireInstance(name)
        ChrootClient.requireValidPort(newPort)
        if (newPort == instance.sshPort) return@runOperation "SSH 端口没有变化"
        val registry = preferences.snapshot()
        require(
            registry.ports.none { (otherName, port) -> otherName != name && port == newPort },
        ) { "SSH 端口 $newPort 已分配给其他实例" }
        require(!chrootClient.isPortOpen(newPort)) { "端口 $newPort 已被其他程序占用" }

        val sessions = chrootClient.listSessions().filter { it.container == name }
        val managedSshRunning = sessions.isNotEmpty() &&
            chrootClient.runningSshPort(name) == instance.sshPort &&
            chrootClient.isPortOpen(instance.sshPort)
        if (instance.autoStartEnabled) {
            chrootClient.updateAutoStartPort(name, newPort)
        }
        if (!managedSshRunning) {
            try {
                preferences.updatePort(name, newPort)
            } catch (error: Exception) {
                if (instance.autoStartEnabled) {
                    chrootClient.updateAutoStartPort(name, instance.sshPort)
                }
                throw error
            }
            return@runOperation "$name 的 SSH 端口已改为 $newPort，下次启动时生效"
        }

        val stopResult = chrootClient.stopSsh(name, instance.sshPort)
        if (!stopResult.isSuccess) {
            if (instance.autoStartEnabled) {
                chrootClient.updateAutoStartPort(name, instance.sshPort)
            }
            error(stopResult.bestError)
        }
        val stopped = chrootClient.waitForPortState(
            instance.sshPort,
            expectedOpen = false,
            timeoutMillis = SSH_STOP_WAIT_MILLIS,
        )
        if (!stopped.isSuccess) {
            if (instance.autoStartEnabled) {
                chrootClient.updateAutoStartPort(name, instance.sshPort)
            }
            error(stopped.bestError)
        }
        try {
            preferences.updatePort(name, newPort)
        } catch (error: Exception) {
            if (instance.autoStartEnabled) {
                chrootClient.updateAutoStartPort(name, instance.sshPort)
            }
            chrootClient.startSsh(name, instance.sshPort)
            throw error
        }
        val startResult = chrootClient.startSsh(name, newPort)
        val newPortReady = startResult.isSuccess && chrootClient.waitForPortState(
            newPort,
            expectedOpen = true,
            timeoutMillis = SSH_START_WAIT_MILLIS,
        ).isSuccess
        if (newPortReady) {
            hintRuntimeState(name, InstanceRuntimeState.SSH_READY)
            return@runOperation "$name 已改用 SSH 端口 $newPort 并重新启动"
        }

        chrootClient.stopSsh(name, newPort)
        chrootClient.waitForPortState(
            newPort,
            expectedOpen = false,
            timeoutMillis = SSH_STOP_WAIT_MILLIS,
        )
        preferences.updatePort(name, instance.sshPort)
        if (instance.autoStartEnabled) {
            chrootClient.updateAutoStartPort(name, instance.sshPort)
        }
        val rollback = chrootClient.startSsh(name, instance.sshPort)
        val rollbackReady = rollback.isSuccess && chrootClient.waitForPortState(
            instance.sshPort,
            expectedOpen = true,
            timeoutMillis = SSH_START_WAIT_MILLIS,
        ).isSuccess
        check(rollbackReady) {
            "新端口启动失败：${startResult.bestError}；恢复原端口也失败：${rollback.bestError}"
        }
        hintRuntimeState(name, InstanceRuntimeState.SSH_READY)
        error("新端口启动失败，已恢复原端口 ${instance.sshPort}：${startResult.bestError}")
    }

    suspend fun rename(name: String, newName: String) = runOperation(
        OperationInfo(label = "正在将 $name 重命名为 $newName", instanceName = name),
    ) {
        val instance = requireInstance(name)
        require(!instance.isProtected) { "请先解除实例保护再重命名" }
        ChrootClient.requireValidName(newName)
        require(name != newName) { "新旧实例名称不能相同" }
        require(newName !in chrootClient.listContainers()) { "实例 $newName 已经存在" }
        val sessions = chrootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "请先停止实例再重命名" }

        val result = chrootClient.rename(name, newName)
        check(result.isSuccess) { result.bestError }
        if (instance.autoStartEnabled) {
            try {
                chrootClient.renameAutoStartInstance(name, newName, instance.sshPort)
            } catch (error: Exception) {
                val rollback = chrootClient.rename(newName, name)
                check(rollback.isSuccess) {
                    "开机启动配置更新失败且名称回滚失败，请不要操作实例并重新刷新"
                }
                throw error
            }
        }
        try {
            preferences.renameInstance(name, newName)
        } catch (error: Exception) {
            if (instance.autoStartEnabled) {
                chrootClient.renameAutoStartInstance(newName, name, instance.sshPort)
            }
            val rollback = chrootClient.rename(newName, name)
            check(rollback.isSuccess) {
                "管理数据迁移失败且名称回滚失败，请不要操作实例并重新刷新"
            }
            throw error
        }
        val passwordRecordWarning = try {
            rootPasswordStore.rename(name, newName)
            null
        } catch (error: Exception) {
            error.userMessage()
        }
        runtimeStateHints.remove(name)
        hintRuntimeState(newName, InstanceRuntimeState.STOPPED)
        "$name 已重命名为 $newName".withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun initializeSsh(name: String) = runOperation(
        OperationInfo(label = "正在初始化 $name 的 SSH", instanceName = name),
    ) {
        requireInstance(name)
        val sessions = chrootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "请先停止实例再初始化 SSH" }
        val result = chrootClient.initializeSsh(name)
        check(result.isSuccess) { result.bestError }
        hintRuntimeState(name, InstanceRuntimeState.STOPPED)
        val passwordResult = chrootClient.ensureDefaultRootPassword(name)
        check(passwordResult.isSuccess) { passwordResult.bestError }
        val passwordRecordWarning = if (
            ChrootClient.defaultPasswordWasSet(passwordResult)
        ) {
            rememberRootPassword(name, ChrootClient.DEFAULT_ROOT_PASSWORD)
        } else {
            null
        }
        "$name 的 SSH 已初始化；未设置密码时默认使用 root1234"
            .withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun updateRootPassword(name: String, password: String) = runOperation(
        OperationInfo(label = "正在修改 $name 的 root 密码", instanceName = name),
    ) {
        val instance = requireInstance(name)
        val result = chrootClient.setRootPassword(name, password)
        check(result.isSuccess) { result.bestError }
        val passwordRecordWarning = rememberRootPassword(name, password)

        val sshRunning = chrootClient.runningSshPort(name) == instance.sshPort &&
            chrootClient.isPortOpen(instance.sshPort)
        val message = if (sshRunning) {
            val reloadResult = chrootClient.reloadSsh(name, instance.sshPort)
            if (!reloadResult.isSuccess) {
                "$name 的 root 密码已修改，但 SSH 配置重载失败：" +
                    "${reloadResult.bestError}；可使用“重启 SSH”恢复配置"
            } else {
                delay(SSH_RELOAD_SETTLE_MILLIS)
                val stillListening = chrootClient.waitForPortState(
                    instance.sshPort,
                    expectedOpen = true,
                    timeoutMillis = SSH_RELOAD_WAIT_MILLIS,
                )
                if (stillListening.isSuccess) {
                    "$name 的 root 密码已修改，SSH 配置已重新加载"
                } else {
                    val recovery = chrootClient.startSsh(name, instance.sshPort)
                    val recovered = recovery.isSuccess && chrootClient.waitForPortState(
                        instance.sshPort,
                        expectedOpen = true,
                        timeoutMillis = SSH_START_WAIT_MILLIS,
                    ).isSuccess
                    if (recovered) {
                        "$name 的 root 密码已修改；SSH 重载后中断，现已自动恢复"
                    } else {
                        "$name 的 root 密码已修改，但 SSH 重载后没有恢复监听；" +
                            "请使用“重启 SSH”查看详细错误"
                    }
                }
            }
        } else {
            "$name 的 root 密码已修改，下次启动 SSH 时生效"
        }
        message.withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun clearSavedRootPassword(name: String) = runOperation(
        OperationInfo(label = "正在清除 $name 的本地密码记录", instanceName = name),
    ) {
        requireInstance(name)
        rootPasswordStore.remove(name)
        "$name 的管理器密码记录已清除；Ubuntu 中的实际密码没有改变"
    }

    suspend fun delete(name: String) = runOperation(
        OperationInfo(label = "正在删除 $name", instanceName = name),
    ) {
        val instance = requireInstance(name)
        require(!instance.isProtected) { "受保护实例禁止删除" }

        val sessions = chrootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "请先停止实例再永久删除" }
        if (instance.autoStartEnabled) {
            chrootClient.removeAutoStartInstance(name)
        }
        val removeResult = chrootClient.remove(name)
        if (!removeResult.isSuccess && instance.autoStartEnabled) {
            chrootClient.setInstanceAutoStart(name, instance.sshPort, true)
        }
        check(removeResult.isSuccess) { removeResult.bestError }
        preferences.removeInstance(name)
        val passwordRecordWarning = try {
            rootPasswordStore.remove(name)
            null
        } catch (error: Exception) {
            error.userMessage()
        }
        "$name 已永久删除".withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun backup(name: String, displayName: String) = runOperation(
        OperationInfo(label = "正在备份 $displayName", instanceName = name),
    ) {
        require(_state.value.environment.backupReady) {
            "备份目录不可用，请在设置中重新配置 Root Chroot 后端"
        }
        requireInstance(name)
        val sessions = chrootClient.listSessions().filter { it.container == name }
        require(sessions.isEmpty()) { "为保证数据一致，请先停止实例再备份" }
        val createdBackup = chrootClient.backup(name, displayName)
        var updatedBackups = mergeBackupCatalog(
            chrootClient.listBackups(),
            preferences.snapshot().backups + createdBackup,
        )
        val retentionCount = preferences.snapshot().backupRetentionCount
        var deletedCount = 0
        var cleanupError: String? = null
        if (retentionCount > 0) {
            val expired = updatedBackups
                .filter { it.instanceName == name }
                .sortedByDescending { it.modifiedEpochSeconds }
                .drop(retentionCount)
            for (entry in expired) {
                val deleted = chrootClient.deleteBackup(entry)
                if (deleted.isSuccess) {
                    deletedCount++
                    updatedBackups = updatedBackups.filterNot { it.path == entry.path }
                } else {
                    cleanupError = deleted.bestError
                    break
                }
            }
        }
        preferences.saveBackups(updatedBackups)
        _state.value = _state.value.copy(
            backups = updatedBackups,
            backupsVerified = true,
        )
        buildString {
            append("备份完成：").append(displayName.trim())
            if (deletedCount > 0) {
                append("；已清理 ").append(deletedCount).append(" 份旧备份")
            }
            cleanupError?.let { append("；旧备份清理失败：").append(it) }
        }
    }

    suspend fun renameBackup(backup: BackupEntry, displayName: String) = runOperation(
        OperationInfo(label = "正在修改备份名称"),
    ) {
        val renamedBackup = chrootClient.renameBackup(backup, displayName)
        val updatedBackups = mergeBackupCatalog(
            chrootClient.listBackups(),
            preferences.snapshot().backups.filterNot { it.path == backup.path } + renamedBackup,
        )
        preferences.saveBackups(updatedBackups)
        _state.value = _state.value.copy(
            backups = updatedBackups,
            backupsVerified = true,
        )
        "备份名称已修改为：${displayName.trim()}"
    }

    suspend fun restore(backup: BackupEntry) = runOperation(
        OperationInfo(label = "正在恢复 ${backup.instanceName}", instanceName = backup.instanceName),
    ) {
        val embeddedName = chrootClient.validateBackup(backup)
        require(embeddedName == backup.instanceName) { "备份文件名和内部实例名称不一致" }
        val current = _state.value.instances.firstOrNull { it.name == embeddedName }
        if (current != null) {
            require(!current.isProtected) {
                "受保护实例不能通过普通恢复覆盖，请保留备份并使用灾难恢复流程"
            }
            val sessions = chrootClient.listSessions().filter { it.container == embeddedName }
            require(sessions.isEmpty()) { "请先停止实例再恢复" }
        }
        val result = chrootClient.restore(backup)
        check(result.isSuccess) { result.bestError }
        if (current == null) {
            preferences.registerManaged(embeddedName, nextAvailablePort(), protected = true)
        }
        hintRuntimeState(embeddedName, InstanceRuntimeState.STOPPED)
        val passwordRecordWarning = try {
            rootPasswordStore.remove(embeddedName)
            null
        } catch (error: Exception) {
            error.userMessage()
        }
        "$embeddedName 恢复完成；已清除旧的管理器密码记录"
            .withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun deleteBackup(backup: BackupEntry) = runOperation(
        OperationInfo(label = "正在删除 ${backup.fileName}"),
    ) {
        val result = chrootClient.deleteBackup(backup)
        check(result.isSuccess) { result.bestError }
        val updatedBackups = _state.value.backups.filterNot { it.path == backup.path }
        preferences.saveBackups(updatedBackups)
        _state.value = _state.value.copy(backups = updatedBackups, backupsVerified = true)
        "备份 ${backup.fileName} 已删除"
    }

    suspend fun executeLibraryCommand(name: String, commandId: String) = runOperation(
        OperationInfo(label = "正在执行指令", instanceName = name),
    ) {
        requireInstance(name)
        val command = _state.value.commands.firstOrNull { it.id == commandId }
            ?: error("指令不存在或已被移除")
        require(command.type == UserCommandType.COMMAND) {
            "按键动作必须在本地会话中发送"
        }
        val result = chrootClient.executeCommand(name, command)
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
        ChrootClient.requireValidName(targetName)
        ChrootClient.requireValidPort(targetPort)
        require(sourceName != targetName) { "新实例名称不能与源实例相同" }
        val sessions = chrootClient.listSessions().filter { it.container == sourceName }
        require(sessions.isEmpty()) { "为保证数据一致，请先停止源实例" }
        val existing = chrootClient.listContainers()
        require(targetName !in existing) { "实例 $targetName 已经存在" }
        val registry = preferences.snapshot()
        require(targetPort !in registry.ports.values) { "SSH 端口 $targetPort 已被其他实例使用" }
        require(!chrootClient.isPortOpen(targetPort)) { "端口 $targetPort 已被其他程序占用" }

        val cloneResult = chrootClient.cloneInstance(sourceName, targetName)
        if (!cloneResult.isSuccess) {
            val createdTarget = runCatching {
                targetName in chrootClient.listContainers()
            }.getOrDefault(false)
            if (createdTarget) {
                chrootClient.remove(targetName)
            }
            error(cloneResult.bestError)
        }

        preferences.registerManaged(targetName, targetPort, protected = protectTarget)
        hintRuntimeState(targetName, InstanceRuntimeState.STOPPED)
        val passwordRecordWarning = try {
            rootPasswordStore.copy(sourceName, targetName)
            null
        } catch (error: Exception) {
            error.userMessage()
        }
        val identityResult = chrootClient.initializeCloneIdentity(targetName)
        if (!identityResult.isSuccess) {
            return@runOperation "$targetName 已复制并保持停止，但独立身份初始化失败：" +
                identityResult.bestError.withPasswordRecordWarning(passwordRecordWarning)
        }
        "$targetName 复制完成，已分配 SSH 端口 $targetPort"
            .withPasswordRecordWarning(passwordRecordWarning)
    }

    suspend fun logs(name: String): String {
        requireInstance(name)
        return chrootClient.logs(name)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(lastMessage = null)
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(lastMessage = message)
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        preferences.setDynamicColors(enabled)
    }

    suspend fun setHideFromRecentsWhenBackground(enabled: Boolean) {
        preferences.setHideFromRecentsWhenBackground(enabled)
    }

    suspend fun setBackupRetentionCount(count: Int) {
        preferences.setBackupRetentionCount(count)
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

    private suspend fun runOperation(
        operation: OperationInfo,
        block: suspend () -> String,
    ): OperationOutcome {
        return operationMutex.withLock {
            val previousInstance = operation.instanceName?.let { targetName ->
                _state.value.instances.firstOrNull { it.name == targetName }
            }
            _state.update { current ->
                current.copy(
                    currentOperation = operation,
                    lastMessage = null,
                    instances = if (operation.runtimeTransition) {
                        current.instances.map { instance ->
                            if (instance.name == operation.instanceName) {
                                instance.copy(
                                    state = InstanceRuntimeState.OPERATING,
                                    message = operation.label,
                                )
                            } else {
                                instance
                            }
                        }
                    } else {
                        current.instances
                    },
                )
            }
            val result = try {
                OperationOutcome(succeeded = true, message = block())
            } catch (cancelled: CancellationException) {
                _state.update { current ->
                    current.copy(
                        currentOperation = null,
                        lastMessage = "${operation.label}已中断，重新操作前请先检查实例状态",
                        instances = if (operation.runtimeTransition) {
                            current.instances.map { instance ->
                                if (instance.name == operation.instanceName) {
                                    instance.copy(
                                        state = InstanceRuntimeState.UNKNOWN,
                                        message = "操作已中断，请刷新确认实际状态",
                                    )
                                } else {
                                    instance
                                }
                            }
                        } else {
                            current.instances
                        },
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                OperationOutcome(
                    succeeded = false,
                    message = "操作失败：${error.userMessage()}",
                )
            }
            val targetName = operation.instanceName
            if (
                operation.runtimeTransition &&
                targetName != null &&
                _state.value.instances.any { it.name == targetName } &&
                targetName !in runtimeStateHints
            ) {
                hintRuntimeState(
                    targetName,
                    if (result.succeeded) {
                        previousInstance?.state ?: InstanceRuntimeState.UNKNOWN
                    } else {
                        InstanceRuntimeState.UNKNOWN
                    },
                )
            }
            _state.value = _state.value.copy(
                currentOperation = null,
                lastMessage = result.message,
            )
            reconcileCachedInstances()
            refreshRuntime()
            result
        }
    }

    /** Reconciles app-owned metadata without touching Alpha or Root-only paths. */
    private suspend fun reconcileCachedInstances() {
        val registry = preferences.snapshot()
        val names = registry.ports.keys
        val savedPasswords = rootPasswordStore.getAll(names)
        val currentByName = _state.value.instances.associateBy { it.name }
        val hostAddress = findLocalIpv4Address()
        val instances = names.sorted().map { name ->
            val current = currentByName[name]
            val password = savedPasswords[name]
            val hintedState = runtimeStateHints.remove(name)
            val reconciled = if (current == null) {
                UbuntuInstance(
                    name = name,
                    isProtected = name in registry.protectedNames,
                    isManaged = name in registry.managedNames,
                    sshPort = registry.ports.getValue(name),
                    hostAddress = hostAddress,
                    state = hintedState ?: InstanceRuntimeState.UNKNOWN,
                    message = if (hintedState == null) {
                        "等待端口检测；可手动强制同步实际状态"
                    } else {
                        null
                    },
                    savedRootPassword = password?.password,
                    savedRootPasswordUpdatedAt = password?.updatedAtEpochMillis,
                    autoStartEnabled = name in registry.autoStartNames,
                )
            } else {
                current.copy(
                    isProtected = name in registry.protectedNames,
                    isManaged = name in registry.managedNames,
                    sshPort = registry.ports.getValue(name),
                    hostAddress = hostAddress,
                    savedRootPassword = password?.password,
                    savedRootPasswordUpdatedAt = password?.updatedAtEpochMillis,
                    autoStartEnabled = name in registry.autoStartNames,
                )
            }
            if (hintedState == null) {
                reconciled
            } else {
                reconciled.copy(
                    state = hintedState,
                    hasLocalSession = if (hintedState == InstanceRuntimeState.STOPPED) {
                        false
                    } else {
                        reconciled.hasLocalSession
                    },
                    localSessionPort = if (hintedState == InstanceRuntimeState.STOPPED) {
                        null
                    } else {
                        reconciled.localSessionPort
                    },
                    uptime = if (hintedState == InstanceRuntimeState.STOPPED) null else "运行中",
                    message = null,
                )
            }
        }
        runtimeStateHints.keys.retainAll(names)
        _state.update { current -> current.mergeRegistry(registry).copy(
            instances = instances,
            commandHistory = registry.commandHistory,
            commands = registry.commands,
            commandTags = registry.commandTags,
            terminalShortcuts = registry.terminalShortcuts,
        ) }
    }

    private fun hintRuntimeState(name: String, state: InstanceRuntimeState) {
        runtimeStateHints[name] = state
    }

    private fun mergeBackupCatalog(
        scanned: List<BackupEntry>,
        cached: List<BackupEntry>,
    ): List<BackupEntry> {
        val cachedByPath = cached.associateBy { it.path }
        return scanned.map { entry ->
            val previous = cachedByPath[entry.path]
            if (previous == null || previous.portableArchive != entry.portableArchive) {
                entry
            } else {
                entry.copy(
                    displayName = previous.displayName,
                    logicalSizeBytes = entry.logicalSizeBytes.takeIf { it > 0 }
                        ?: previous.logicalSizeBytes,
                    metadataPresent = entry.metadataPresent || previous.metadataPresent,
                )
            }
        }.sortedByDescending { it.modifiedEpochSeconds }
    }

    private suspend fun rememberRootPassword(name: String, password: String): String? = try {
        rootPasswordStore.save(name, password)
        null
    } catch (error: Exception) {
        error.userMessage()
    }

    private fun String.withPasswordRecordWarning(warning: String?): String =
        if (warning == null) {
            this
        } else {
            "$this；本地加密密码记录失败：$warning"
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
        private const val MAX_COMMAND_OUTPUT = 4_000
        private const val LOCAL_SESSION_PORT_START = 32100
        private const val LOCAL_SESSION_PORT_END = 32999
        private const val LOCAL_SESSION_CONNECT_ATTEMPTS = 30
        private const val LOCAL_SESSION_CONNECT_DELAY_MILLIS = 200L
        private const val CONNECTION_PROBE_TIMEOUT_MILLIS = 5_000L
        private const val STATUS_COMMAND_TIMEOUT_MILLIS = 5_000L
        private const val SSH_STOP_WAIT_MILLIS = 5_000L
        private const val SSH_START_WAIT_MILLIS = 8_000L
        private const val SSH_RELOAD_WAIT_MILLIS = 5_000L
        private const val SSH_RELOAD_SETTLE_MILLIS = 400L
        private const val SSH_START_ATTEMPTS = 2
    }
}
