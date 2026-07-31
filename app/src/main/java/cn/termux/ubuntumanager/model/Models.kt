package cn.termux.ubuntumanager.model

enum class InstanceRuntimeState {
    CHECKING,
    STOPPED,
    RUNNING,
    SSH_READY,
    OPERATING,
    UNKNOWN,
    ERROR,
}

data class UbuntuInstance(
    val name: String,
    val isProtected: Boolean,
    val isManaged: Boolean,
    val sshPort: Int,
    val hasLocalSession: Boolean = false,
    val hostAddress: String? = null,
    val state: InstanceRuntimeState = InstanceRuntimeState.CHECKING,
    val uptime: String? = null,
    val message: String? = null,
)

data class UserCommand(
    val id: String,
    val title: String,
    val script: String,
    val tagIds: Set<String> = emptySet(),
)

data class CommandTag(
    val id: String,
    val name: String,
)

enum class TerminalShortcutAction {
    BUILTIN,
    TEXT,
    COMMAND,
}

data class TerminalShortcutPreference(
    val id: String,
    val label: String,
    val action: TerminalShortcutAction,
    val payload: String,
    val enabled: Boolean = true,
    val appendEnter: Boolean = false,
)

data class CommandHistoryEntry(
    val timestampEpochMillis: Long,
    val commandId: String,
    val commandTitle: String,
    val instanceName: String,
    val exitCode: Int,
    val output: String,
) {
    val succeeded: Boolean
        get() = exitCode == 0
}

data class EnvironmentStatus(
    val checking: Boolean = true,
    val termuxInstalled: Boolean = false,
    val termuxVersion: String? = null,
    val termuxStopped: Boolean = false,
    val permissionGranted: Boolean = false,
    val externalAppsConfigured: Boolean = false,
    val connectionAvailable: Boolean = false,
    val prootVersion: String? = null,
    val prootCompatible: Boolean = false,
    val storageReady: Boolean = false,
    val termuxReadStorageGranted: Boolean = false,
    val termuxWriteStorageGranted: Boolean = false,
    val termuxAllFilesGranted: Boolean = false,
    val storageDirectoryPresent: Boolean = false,
    val storageLinksPresent: Boolean = false,
    val storageWriteTestPassed: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean
        get() = termuxInstalled &&
            permissionGranted &&
            connectionAvailable &&
            prootCompatible
}

data class StorageProbe(
    val directoryPresent: Boolean,
    val linksPresent: Boolean,
    val writeTestPassed: Boolean,
)

data class BackupEntry(
    val fileName: String,
    val path: String,
    val instanceName: String,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val checksumPresent: Boolean,
)

data class OperationInfo(
    val label: String,
    val instanceName: String? = null,
)

enum class BackgroundOperationType {
    CREATE,
    RENAME,
    INITIALIZE_SSH,
    DELETE_INSTANCE,
    BACKUP,
    RESTORE,
    EXECUTE_COMMAND,
    CLONE,
}

enum class BackgroundOperationStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
}

data class BackgroundOperationRecord(
    val id: String,
    val type: BackgroundOperationType,
    val label: String,
    val instanceName: String? = null,
    val status: BackgroundOperationStatus,
    val startedEpochMillis: Long,
    val updatedEpochMillis: Long,
    val message: String? = null,
)

data class OperationOutcome(
    val succeeded: Boolean,
    val message: String,
)

enum class TermuxWakeLockState {
    DISABLED,
    IDLE,
    ACQUIRING,
    HELD,
    RELEASING,
    ERROR,
}

data class AppUiState(
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val instances: List<UbuntuInstance> = emptyList(),
    val backups: List<BackupEntry> = emptyList(),
    val commandHistory: List<CommandHistoryEntry> = emptyList(),
    val commands: List<UserCommand> = emptyList(),
    val commandTags: List<CommandTag> = emptyList(),
    val terminalShortcuts: List<TerminalShortcutPreference> = emptyList(),
    val currentOperation: OperationInfo? = null,
    val backgroundOperation: BackgroundOperationRecord? = null,
    val hideMaintenanceFromRecents: Boolean = true,
    val termuxBackgroundProtection: Boolean = true,
    val termuxWakeLockAlwaysOn: Boolean = false,
    val termuxWakeLockState: TermuxWakeLockState = TermuxWakeLockState.IDLE,
    val termuxWakeLockMessage: String? = null,
    val lastMessage: String? = null,
    val lastStatusCheckEpochMillis: Long? = null,
)

data class CommandResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    val internalErrorCode: Int = -1,
    val internalErrorMessage: String = "",
    val stdoutOriginalLength: Int = 0,
    val stderrOriginalLength: Int = 0,
) {
    val isSuccess: Boolean
        get() = internalErrorCode == -1 && exitCode == 0

    val bestError: String
        get() = internalErrorMessage
            .ifBlank { stderr.trim() }
            .ifBlank { stdout.trim() }
            .ifBlank { "命令执行失败（退出码 $exitCode）" }
}

data class ProotSession(
    val pid: Int,
    val container: String,
    val type: String,
    val user: String,
    val uptime: String,
    val command: String,
)

enum class LocalSessionPhase {
    IDLE,
    PREPARING,
    CONNECTING,
    READY,
    ERROR,
}

enum class LocalSessionBackend {
    TMUX,
    DTACH,
    UNKNOWN,
}

data class LocalSessionConnection(
    val port: Int,
    val backend: LocalSessionBackend,
)

data class LocalSessionState(
    val instanceName: String? = null,
    val phase: LocalSessionPhase = LocalSessionPhase.IDLE,
    val port: Int? = null,
    val backend: LocalSessionBackend = LocalSessionBackend.UNKNOWN,
    val message: String? = null,
) {
    val url: String?
        get() = port?.let { "http://127.0.0.1:$it/" }
}
