package cn.termux.ubuntumanager.operation

import android.content.Intent
import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.BackgroundOperationType
import java.util.UUID

data class BackgroundOperationRequest(
    val id: String = UUID.randomUUID().toString(),
    val type: BackgroundOperationType,
    val label: String,
    val instanceName: String? = null,
    val secondaryName: String? = null,
    val port: Int = 0,
    val flag1: Boolean = false,
    val flag2: Boolean = false,
    val flag3: Boolean = false,
    val commandId: String? = null,
    val backup: BackupEntry? = null,
) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_ID, id)
        .putExtra(EXTRA_TYPE, type.name)
        .putExtra(EXTRA_LABEL, label)
        .putExtra(EXTRA_INSTANCE_NAME, instanceName)
        .putExtra(EXTRA_SECONDARY_NAME, secondaryName)
        .putExtra(EXTRA_PORT, port)
        .putExtra(EXTRA_FLAG_1, flag1)
        .putExtra(EXTRA_FLAG_2, flag2)
        .putExtra(EXTRA_FLAG_3, flag3)
        .putExtra(EXTRA_COMMAND_ID, commandId)
        .also { target ->
            backup?.let { entry ->
                target.putExtra(EXTRA_BACKUP_FILE_NAME, entry.fileName)
                target.putExtra(EXTRA_BACKUP_PATH, entry.path)
                target.putExtra(EXTRA_BACKUP_INSTANCE, entry.instanceName)
                target.putExtra(EXTRA_BACKUP_SIZE, entry.sizeBytes)
                target.putExtra(EXTRA_BACKUP_MODIFIED, entry.modifiedEpochSeconds)
                target.putExtra(EXTRA_BACKUP_CHECKSUM, entry.checksumPresent)
                target.putExtra(EXTRA_BACKUP_DISPLAY_NAME, entry.displayName)
                target.putExtra(EXTRA_BACKUP_LOGICAL_SIZE, entry.logicalSizeBytes)
                target.putExtra(EXTRA_BACKUP_METADATA, entry.metadataPresent)
                target.putExtra(EXTRA_BACKUP_PORTABLE, entry.portableArchive)
            }
        }

    companion object {
        private const val EXTRA_ID = "background_operation_id"
        private const val EXTRA_TYPE = "background_operation_type"
        private const val EXTRA_LABEL = "background_operation_label"
        private const val EXTRA_INSTANCE_NAME = "background_operation_instance"
        private const val EXTRA_SECONDARY_NAME = "background_operation_secondary_name"
        private const val EXTRA_PORT = "background_operation_port"
        private const val EXTRA_FLAG_1 = "background_operation_flag_1"
        private const val EXTRA_FLAG_2 = "background_operation_flag_2"
        private const val EXTRA_FLAG_3 = "background_operation_flag_3"
        private const val EXTRA_COMMAND_ID = "background_operation_command_id"
        private const val EXTRA_BACKUP_FILE_NAME = "background_operation_backup_file"
        private const val EXTRA_BACKUP_PATH = "background_operation_backup_path"
        private const val EXTRA_BACKUP_INSTANCE = "background_operation_backup_instance"
        private const val EXTRA_BACKUP_SIZE = "background_operation_backup_size"
        private const val EXTRA_BACKUP_MODIFIED = "background_operation_backup_modified"
        private const val EXTRA_BACKUP_CHECKSUM = "background_operation_backup_checksum"
        private const val EXTRA_BACKUP_DISPLAY_NAME = "background_operation_backup_display_name"
        private const val EXTRA_BACKUP_LOGICAL_SIZE = "background_operation_backup_logical_size"
        private const val EXTRA_BACKUP_METADATA = "background_operation_backup_metadata"
        private const val EXTRA_BACKUP_PORTABLE = "background_operation_backup_portable"

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun fromIntent(intent: Intent?): BackgroundOperationRequest? {
            intent ?: return null
            val id = intent.getStringExtra(EXTRA_ID)?.takeIf(ID_PATTERN::matches) ?: return null
            val type = intent.getStringExtra(EXTRA_TYPE)?.let {
                runCatching { BackgroundOperationType.valueOf(it) }.getOrNull()
            } ?: return null
            val label = intent.getStringExtra(EXTRA_LABEL)?.takeIf(String::isNotBlank)
                ?: return null
            val backupPath = intent.getStringExtra(EXTRA_BACKUP_PATH)
            val backup = backupPath?.let {
                BackupEntry(
                    fileName = intent.getStringExtra(EXTRA_BACKUP_FILE_NAME) ?: return null,
                    path = it,
                    instanceName = intent.getStringExtra(EXTRA_BACKUP_INSTANCE) ?: return null,
                    sizeBytes = intent.getLongExtra(EXTRA_BACKUP_SIZE, 0),
                    modifiedEpochSeconds = intent.getLongExtra(EXTRA_BACKUP_MODIFIED, 0),
                    checksumPresent = intent.getBooleanExtra(EXTRA_BACKUP_CHECKSUM, false),
                    displayName = intent.getStringExtra(EXTRA_BACKUP_DISPLAY_NAME)
                        ?: intent.getStringExtra(EXTRA_BACKUP_INSTANCE) ?: return null,
                    logicalSizeBytes = intent.getLongExtra(EXTRA_BACKUP_LOGICAL_SIZE, 0),
                    metadataPresent = intent.getBooleanExtra(EXTRA_BACKUP_METADATA, false),
                    portableArchive = intent.getBooleanExtra(EXTRA_BACKUP_PORTABLE, false),
                )
            }
            return BackgroundOperationRequest(
                id = id,
                type = type,
                label = label,
                instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME),
                secondaryName = intent.getStringExtra(EXTRA_SECONDARY_NAME),
                port = intent.getIntExtra(EXTRA_PORT, 0),
                flag1 = intent.getBooleanExtra(EXTRA_FLAG_1, false),
                flag2 = intent.getBooleanExtra(EXTRA_FLAG_2, false),
                flag3 = intent.getBooleanExtra(EXTRA_FLAG_3, false),
                commandId = intent.getStringExtra(EXTRA_COMMAND_ID),
                backup = backup,
            )
        }

        fun create(
            name: String,
            port: Int,
            initializeSsh: Boolean,
            startAfterCreate: Boolean,
            protectAfterCreate: Boolean,
        ) = BackgroundOperationRequest(
            type = BackgroundOperationType.CREATE,
            label = "正在创建 $name",
            instanceName = name,
            port = port,
            flag1 = initializeSsh,
            flag2 = startAfterCreate,
            flag3 = protectAfterCreate,
        )

        fun rename(name: String, newName: String) = BackgroundOperationRequest(
            type = BackgroundOperationType.RENAME,
            label = "正在将 $name 重命名为 $newName",
            instanceName = name,
            secondaryName = newName,
        )

        fun initializeSsh(name: String) = BackgroundOperationRequest(
            type = BackgroundOperationType.INITIALIZE_SSH,
            label = "正在初始化 $name 的 SSH",
            instanceName = name,
        )

        fun deleteInstance(name: String) = BackgroundOperationRequest(
            type = BackgroundOperationType.DELETE_INSTANCE,
            label = "正在删除 $name",
            instanceName = name,
        )

        fun backup(name: String, displayName: String) = BackgroundOperationRequest(
            type = BackgroundOperationType.BACKUP,
            label = "正在备份 $displayName",
            instanceName = name,
            secondaryName = displayName,
        )

        fun restore(backup: BackupEntry) = BackgroundOperationRequest(
            type = BackgroundOperationType.RESTORE,
            label = "正在恢复 ${backup.instanceName}",
            instanceName = backup.instanceName,
            backup = backup,
        )

        fun executeCommand(name: String, commandId: String) = BackgroundOperationRequest(
            type = BackgroundOperationType.EXECUTE_COMMAND,
            label = "正在执行指令",
            instanceName = name,
            commandId = commandId,
        )

        fun clone(
            sourceName: String,
            targetName: String,
            targetPort: Int,
            protectTarget: Boolean,
        ) = BackgroundOperationRequest(
            type = BackgroundOperationType.CLONE,
            label = "正在复制 $sourceName",
            instanceName = sourceName,
            secondaryName = targetName,
            port = targetPort,
            flag1 = protectTarget,
        )
    }
}
