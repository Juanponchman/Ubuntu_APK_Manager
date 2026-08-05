package cn.termux.ubuntumanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.CommandTag
import cn.termux.ubuntumanager.model.LocalSessionPhase
import cn.termux.ubuntumanager.model.LocalSessionState
import cn.termux.ubuntumanager.model.TerminalShortcutPreference
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.operation.BackgroundOperationRequest
import cn.termux.ubuntumanager.operation.OperationForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as UbuntuManagerApplication
    private val repository = app.repository

    val uiState = repository.state
    val dynamicColors: StateFlow<Boolean> = app.preferences.registry
        .map { it.dynamicColors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _logContent = MutableStateFlow("正在读取日志…")
    val logContent = _logContent.asStateFlow()

    private val _alphaSetupInProgress = MutableStateFlow(false)
    val alphaSetupInProgress = _alphaSetupInProgress.asStateFlow()
    private val _backgroundSetupInProgress = MutableStateFlow(false)
    val backgroundSetupInProgress = _backgroundSetupInProgress.asStateFlow()
    private val _localSessionState = MutableStateFlow(LocalSessionState())
    val localSessionState = _localSessionState.asStateFlow()
    private var statusMonitoringJob: Job? = null
    private var refreshJob: Job? = null
    private var localSessionJob: Job? = null
    private var localSessionHistoryJob: Job? = null
    private var requestedLocalSessionName: String? = null

    init {
        viewModelScope.launch {
            repository.reconcileBackgroundOperation(
                OperationForegroundService.isOperationActive(),
            )
            repository.loadCachedState()
        }
    }

    fun refresh() {
        if (
            OperationForegroundService.isOperationActive() ||
            repository.state.value.currentOperation != null
        ) {
            return
        }
        requestRefresh(
            allowConnectionProbe = true,
            replaceActive = false,
            showChecking = true,
        )
    }

    fun refreshAfterPermissionChange() {
        refreshWithoutConnectionProbe()
    }

    fun startStatusMonitoring() {
        if (statusMonitoringJob?.isActive == true) return
        statusMonitoringJob = viewModelScope.launch {
            while (isActive) {
                if (
                    repository.state.value.currentOperation == null &&
                    repository.state.value.environment.ready
                ) {
                    repository.refreshRuntime()
                }
                delay(STATUS_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    fun stopStatusMonitoring() {
        statusMonitoringJob?.cancel()
        statusMonitoringJob = null
    }

    private fun refreshWithoutConnectionProbe() {
        requestRefresh(
            allowConnectionProbe = false,
            replaceActive = true,
            showChecking = true,
        )
    }

    private fun requestRefresh(
        allowConnectionProbe: Boolean,
        replaceActive: Boolean,
        showChecking: Boolean,
    ): Job {
        val activeRefresh = refreshJob
        if (activeRefresh?.isActive == true) {
            if (!replaceActive) return activeRefresh
            activeRefresh.cancel()
        }
        return viewModelScope.launch {
            repository.refreshAll(allowConnectionProbe, showChecking)
        }.also { refreshJob = it }
    }

    fun start(name: String) {
        viewModelScope.launch { repository.start(name) }
    }

    fun stop(name: String, force: Boolean = false) {
        viewModelScope.launch { repository.stop(name, force) }
    }

    fun restart(name: String) {
        viewModelScope.launch { repository.restartSsh(name) }
    }

    fun create(
        name: String,
        port: Int,
        initializeSsh: Boolean,
        startAfterCreate: Boolean,
        protectAfterCreate: Boolean,
    ) {
        enqueueBackgroundOperation(
            BackgroundOperationRequest.create(
                name,
                port,
                initializeSsh,
                startAfterCreate,
                protectAfterCreate,
            ),
        )
    }

    fun openLocalSession(name: String) {
        requestedLocalSessionName = name
        val current = _localSessionState.value
        if (
            current.instanceName == name &&
            current.phase == LocalSessionPhase.READY
        ) {
            return
        }
        if (localSessionJob?.isActive == true && current.instanceName == name) return

        _localSessionState.value = LocalSessionState(
            instanceName = name,
            phase = LocalSessionPhase.PREPARING,
            message = "正在启动实例并准备后台会话；首次使用会自动安装终端组件…",
        )
        localSessionJob = viewModelScope.launch {
            try {
                val connection = repository.openLocalSession(name)
                if (requestedLocalSessionName != name) {
                    repository.closeLocalSession(name, connection.port)
                    return@launch
                }
                _localSessionState.value = LocalSessionState(
                    instanceName = name,
                    phase = LocalSessionPhase.READY,
                    port = connection.port,
                    backend = connection.backend,
                    historySnapshot = connection.historySnapshot,
                    message = "已连接本机后台会话",
                )
            } catch (error: Exception) {
                if (requestedLocalSessionName == name) {
                    _localSessionState.value = LocalSessionState(
                        instanceName = name,
                        phase = LocalSessionPhase.ERROR,
                        message = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }

    fun closeLocalSession(name: String) {
        if (requestedLocalSessionName == name) {
            requestedLocalSessionName = null
        }
        val current = _localSessionState.value
        if (current.instanceName != name) return
        localSessionHistoryJob?.cancel()
        localSessionHistoryJob = null
        _localSessionState.value = LocalSessionState()
        current.port?.let { port ->
            viewModelScope.launch {
                repository.closeLocalSession(name, port)
                repository.refreshRuntime()
            }
        }
    }

    fun refreshLocalSessionHistory(name: String) {
        val current = _localSessionState.value
        if (
            current.instanceName != name ||
            current.phase != LocalSessionPhase.READY ||
            localSessionHistoryJob?.isActive == true
        ) {
            return
        }
        localSessionHistoryJob = viewModelScope.launch {
            val snapshot = runCatching {
                repository.captureLocalTerminalHistory(name)
            }.getOrNull().orEmpty()
            val latest = _localSessionState.value
            if (
                snapshot.isNotEmpty() &&
                latest.instanceName == name &&
                latest.phase == LocalSessionPhase.READY &&
                latest.historySnapshot != snapshot
            ) {
                _localSessionState.value = latest.copy(historySnapshot = snapshot)
            }
        }
    }

    fun endLocalSession(name: String) {
        if (requestedLocalSessionName == name) {
            requestedLocalSessionName = null
        }
        localSessionJob?.cancel()
        localSessionJob = null
        localSessionHistoryJob?.cancel()
        localSessionHistoryJob = null
        val current = _localSessionState.value
        val port = current.port.takeIf { current.instanceName == name }
        _localSessionState.value = LocalSessionState()
        viewModelScope.launch {
            try {
                repository.endLocalSession(name, port)
            } catch (error: Exception) {
                repository.showMessage(
                    "结束后台会话失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    fun initializeSsh(name: String) {
        enqueueBackgroundOperation(BackgroundOperationRequest.initializeSsh(name))
    }

    fun setProtection(name: String, protected: Boolean) {
        viewModelScope.launch { repository.setProtection(name, protected) }
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoStartEnabled(enabled) }
    }

    fun setInstanceAutoStart(name: String, enabled: Boolean) {
        viewModelScope.launch { repository.setInstanceAutoStart(name, enabled) }
    }

    fun updateSshPort(name: String, port: Int) {
        viewModelScope.launch { repository.updateSshPort(name, port) }
    }

    fun updateRootPassword(name: String, password: String) {
        viewModelScope.launch { repository.updateRootPassword(name, password) }
    }

    fun clearSavedRootPassword(name: String) {
        viewModelScope.launch { repository.clearSavedRootPassword(name) }
    }

    fun rename(name: String, newName: String) {
        enqueueBackgroundOperation(BackgroundOperationRequest.rename(name, newName))
    }

    fun executeLibraryCommand(name: String, commandId: String) {
        enqueueBackgroundOperation(
            BackgroundOperationRequest.executeCommand(name, commandId),
        )
    }

    fun saveCommandTags(tags: List<CommandTag>) {
        viewModelScope.launch {
            try {
                repository.saveCommandTags(tags)
            } catch (error: Exception) {
                repository.showMessage("保存标签失败：${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    fun saveCommand(command: UserCommand) {
        viewModelScope.launch {
            try {
                repository.saveCommand(command)
            } catch (error: Exception) {
                repository.showMessage(
                    "保存指令失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    fun deleteCommand(commandId: String) {
        viewModelScope.launch {
            try {
                repository.deleteCommand(commandId)
            } catch (error: Exception) {
                repository.showMessage(
                    "删除指令失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    fun saveTerminalShortcuts(shortcuts: List<TerminalShortcutPreference>) {
        viewModelScope.launch {
            try {
                repository.saveTerminalShortcuts(shortcuts)
            } catch (error: Exception) {
                repository.showMessage(
                    "保存终端快捷键失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    fun cloneInstance(
        sourceName: String,
        targetName: String,
        targetPort: Int,
        protectTarget: Boolean,
    ) {
        enqueueBackgroundOperation(
            BackgroundOperationRequest.clone(
                sourceName,
                targetName,
                targetPort,
                protectTarget,
            ),
        )
    }

    fun delete(name: String) {
        enqueueBackgroundOperation(BackgroundOperationRequest.deleteInstance(name))
    }

    fun backup(name: String, displayName: String) {
        enqueueBackgroundOperation(BackgroundOperationRequest.backup(name, displayName))
    }

    fun restore(backup: BackupEntry) {
        enqueueBackgroundOperation(BackgroundOperationRequest.restore(backup))
    }

    fun deleteBackup(backup: BackupEntry) {
        viewModelScope.launch { repository.deleteBackup(backup) }
    }

    fun renameBackup(backup: BackupEntry, displayName: String) {
        viewModelScope.launch { repository.renameBackup(backup, displayName) }
    }

    fun syncBackups() {
        viewModelScope.launch { repository.syncBackups() }
    }

    fun loadLogs(name: String) {
        _logContent.value = "正在读取日志…"
        viewModelScope.launch {
            _logContent.value = repository.logs(name)
        }
    }

    fun clearMessage() {
        repository.clearMessage()
    }

    fun configureWithAlpha() {
        if (_alphaSetupInProgress.value) return
        viewModelScope.launch {
            _alphaSetupInProgress.value = true
            var configured = false
            val message = try {
                val result = app.chrootClient.bootstrap(force = true)
                check(result.isSuccess) { result.bestError }
                configured = true
                "Magisk Alpha 授权完成，Root Chroot 后端已安装"
            } catch (error: Exception) {
                "Magisk Alpha 配置失败：${error.message ?: error::class.java.simpleName}"
            } finally {
                _alphaSetupInProgress.value = false
            }
            repository.showMessage(message)
            if (configured) {
                repository.refreshAll(allowConnectionProbe = true)
            }
        }
    }

    fun setDynamicColors(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColors(enabled) }
    }

    fun setHideFromRecentsWhenBackground(enabled: Boolean) {
        viewModelScope.launch {
            repository.setHideFromRecentsWhenBackground(enabled)
        }
    }

    fun setBackupRetentionCount(count: Int) {
        viewModelScope.launch {
            try {
                repository.setBackupRetentionCount(count)
            } catch (error: Exception) {
                repository.showMessage(
                    "保存备份保留策略失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    fun configureBackgroundProtectionWithAlpha() {
        if (_backgroundSetupInProgress.value) return
        viewModelScope.launch {
            _backgroundSetupInProgress.value = true
            val message = try {
                app.rootPermissionSetup.protectBackgroundApps()
            } catch (error: Exception) {
                "Magisk Alpha 后台保护配置失败：" +
                    (error.message ?: error::class.java.simpleName)
            } finally {
                _backgroundSetupInProgress.value = false
            }
            repository.showMessage(message)
        }
    }

    private fun enqueueBackgroundOperation(request: BackgroundOperationRequest) {
        if (OperationForegroundService.isOperationActive()) {
            repository.showMessage("已有后台维护任务正在运行，请等待任务完成")
            return
        }
        runCatching {
            OperationForegroundService.enqueue(getApplication(), request)
        }.onFailure { error ->
            repository.showMessage(
                "无法启动后台任务：${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    companion object {
        private const val STATUS_REFRESH_INTERVAL_MILLIS = 5_000L
    }
}

internal object ReconnectBackoff {
    private val delays = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L)

    fun delayMillis(failedAttempts: Int): Long =
        delays[failedAttempts.coerceIn(0, delays.lastIndex)]
}
