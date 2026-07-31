package cn.termux.ubuntumanager.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.termux.ubuntumanager.command.TerminalShortcuts
import cn.termux.ubuntumanager.model.BackgroundOperationRecord
import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.BackgroundOperationType
import cn.termux.ubuntumanager.model.CommandHistoryEntry
import cn.termux.ubuntumanager.model.CommandTag
import cn.termux.ubuntumanager.model.TerminalShortcutAction
import cn.termux.ubuntumanager.model.TerminalShortcutPreference
import cn.termux.ubuntumanager.model.UserCommand
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ubuntu_manager")

data class RegistrySnapshot(
    val protectedNames: Set<String> = emptySet(),
    val unprotectedNames: Set<String> = emptySet(),
    val managedNames: Set<String> = emptySet(),
    val ports: Map<String, Int> = emptyMap(),
    val commandHistory: List<CommandHistoryEntry> = emptyList(),
    val commands: List<UserCommand> = emptyList(),
    val commandTags: List<CommandTag> = emptyList(),
    val terminalShortcuts: List<TerminalShortcutPreference> = TerminalShortcuts.defaults,
    val firstDiscoveryCompleted: Boolean = false,
    val externalAppsConfigured: Boolean = false,
    val dynamicColors: Boolean = true,
    val hideMaintenanceFromRecents: Boolean = true,
    val termuxBackgroundProtection: Boolean = true,
    val termuxWakeLockAlwaysOn: Boolean = false,
    val termuxBackgroundProtectionActive: Boolean = false,
    val backgroundOperation: BackgroundOperationRecord? = null,
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val protectedNames = stringSetPreferencesKey("protected_names")
        val unprotectedNames = stringSetPreferencesKey("unprotected_names")
        val managedNames = stringSetPreferencesKey("managed_names")
        val ports = stringPreferencesKey("ssh_ports")
        val commandHistory = stringPreferencesKey("command_history")
        val commands = stringPreferencesKey("user_commands")
        val commandTags = stringPreferencesKey("command_tags")
        val terminalShortcuts = stringPreferencesKey("terminal_shortcuts")
        val firstDiscoveryCompleted = booleanPreferencesKey("first_discovery_completed")
        val externalAppsConfigured = booleanPreferencesKey("external_apps_configured")
        val dynamicColors = booleanPreferencesKey("dynamic_colors")
        val hideMaintenanceFromRecents =
            booleanPreferencesKey("hide_maintenance_from_recents")
        val termuxBackgroundProtection =
            booleanPreferencesKey("termux_background_protection")
        val termuxWakeLockAlwaysOn =
            booleanPreferencesKey("termux_wake_lock_always_on")
        val termuxBackgroundProtectionActive =
            booleanPreferencesKey("termux_background_protection_active")
        val backgroundOperation = stringPreferencesKey("background_operation")
    }

    val registry: Flow<RegistrySnapshot> = context.dataStore.data.map(::decode)

    suspend fun snapshot(): RegistrySnapshot = registry.first()

    suspend fun protectDiscovered(names: Set<String>) {
        context.dataStore.edit { values ->
            val currentManaged = values[Keys.managedNames].orEmpty().intersect(names)
            val currentProtected = values[Keys.protectedNames].orEmpty()
            val explicitlyUnprotected =
                values[Keys.unprotectedNames].orEmpty().intersect(names)
            val externallyDiscovered = names - currentManaged
            values[Keys.managedNames] = currentManaged
            values[Keys.unprotectedNames] = explicitlyUnprotected
            values[Keys.protectedNames] =
                (
                    currentProtected.intersect(names) +
                        externallyDiscovered -
                        explicitlyUnprotected
                    ).toSet()
            values[Keys.firstDiscoveryCompleted] = true
        }
    }

    suspend fun registerManaged(name: String, port: Int, protected: Boolean = true) {
        context.dataStore.edit { values ->
            values[Keys.managedNames] = values[Keys.managedNames].orEmpty() + name
            values[Keys.ports] = encodePorts(decodePorts(values[Keys.ports]) + (name to port))
            if (protected) {
                values[Keys.protectedNames] = values[Keys.protectedNames].orEmpty() + name
                values[Keys.unprotectedNames] = values[Keys.unprotectedNames].orEmpty() - name
            }
        }
    }

    suspend fun removeInstance(name: String) {
        context.dataStore.edit { values ->
            values[Keys.managedNames] = values[Keys.managedNames].orEmpty() - name
            values[Keys.protectedNames] = values[Keys.protectedNames].orEmpty() - name
            values[Keys.unprotectedNames] = values[Keys.unprotectedNames].orEmpty() - name
            values[Keys.ports] = encodePorts(decodePorts(values[Keys.ports]) - name)
        }
    }

    suspend fun setProtected(name: String, protected: Boolean) {
        context.dataStore.edit { values ->
            if (protected) {
                values[Keys.protectedNames] = values[Keys.protectedNames].orEmpty() + name
                values[Keys.unprotectedNames] = values[Keys.unprotectedNames].orEmpty() - name
            } else {
                values[Keys.protectedNames] = values[Keys.protectedNames].orEmpty() - name
                values[Keys.unprotectedNames] = values[Keys.unprotectedNames].orEmpty() + name
            }
        }
    }

    suspend fun updatePort(name: String, port: Int) {
        context.dataStore.edit { values ->
            values[Keys.ports] = encodePorts(decodePorts(values[Keys.ports]) + (name to port))
        }
    }

    suspend fun renameInstance(oldName: String, newName: String) {
        context.dataStore.edit { values ->
            values[Keys.managedNames] =
                values[Keys.managedNames].orEmpty().renameEntry(oldName, newName)
            values[Keys.protectedNames] =
                values[Keys.protectedNames].orEmpty().renameEntry(oldName, newName)
            values[Keys.unprotectedNames] =
                values[Keys.unprotectedNames].orEmpty().renameEntry(oldName, newName)
            val ports = decodePorts(values[Keys.ports]).toMutableMap()
            ports.remove(oldName)?.let { ports[newName] = it }
            values[Keys.ports] = encodePorts(ports)
        }
    }

    suspend fun saveAssignedPorts(ports: Map<String, Int>) {
        context.dataStore.edit { values ->
            values[Keys.ports] = encodePorts(ports)
        }
    }

    suspend fun addCommandHistory(entry: CommandHistoryEntry) {
        context.dataStore.edit { values ->
            val history = decodeCommandHistory(values[Keys.commandHistory])
            values[Keys.commandHistory] =
                encodeCommandHistory((listOf(entry) + history).take(MAX_COMMAND_HISTORY))
        }
    }

    suspend fun saveCommandTags(tags: List<CommandTag>) {
        require(tags.size <= MAX_COMMAND_TAGS) { "标签数量不能超过 $MAX_COMMAND_TAGS 个" }
        val normalized = tags.map { tag ->
            require(COMMAND_TAG_ID_REGEX.matches(tag.id)) { "标签 ID 不安全" }
            val name = tag.name.trim()
            require(name.isNotEmpty() && name.length <= MAX_COMMAND_TAG_NAME_LENGTH) {
                "标签名称必须为 1～$MAX_COMMAND_TAG_NAME_LENGTH 个字符"
            }
            tag.copy(name = name)
        }
        require(normalized.map { it.id }.distinct().size == normalized.size) { "标签 ID 重复" }
        require(normalized.map { it.name }.distinct().size == normalized.size) { "标签名称重复" }
        val validIds = normalized.map { it.id }.toSet()
        context.dataStore.edit { values ->
            values[Keys.commandTags] = encodeCommandTags(normalized)
            values[Keys.commands] = encodeCommands(
                decodeCommands(values[Keys.commands]).map { command ->
                    command.copy(tagIds = command.tagIds.intersect(validIds))
                },
            )
        }
    }

    suspend fun saveCommand(command: UserCommand) {
        require(COMMAND_ID_REGEX.matches(command.id)) { "指令 ID 不安全" }
        val title = command.title.trim()
        val script = command.script.trim()
        require(title.isNotEmpty() && title.length <= MAX_COMMAND_TITLE_LENGTH) {
            "指令名称必须为 1～$MAX_COMMAND_TITLE_LENGTH 个字符"
        }
        require(script.isNotEmpty() && script.length <= MAX_COMMAND_SCRIPT_LENGTH) {
            "命令必须为 1～$MAX_COMMAND_SCRIPT_LENGTH 个字符"
        }
        val available = snapshot().commandTags.map { it.id }.toSet()
        require(command.tagIds.all { it in available }) { "包含不存在的标签" }
        context.dataStore.edit { values ->
            val commands = decodeCommands(values[Keys.commands]).toMutableList()
            val normalized = command.copy(title = title, script = script)
            val index = commands.indexOfFirst { it.id == command.id }
            if (index >= 0) {
                commands[index] = normalized
            } else {
                require(commands.size < MAX_COMMANDS) {
                    "指令数量不能超过 $MAX_COMMANDS 条"
                }
                commands += normalized
            }
            values[Keys.commands] = encodeCommands(commands)
        }
    }

    suspend fun deleteCommand(commandId: String) {
        context.dataStore.edit { values ->
            values[Keys.commands] = encodeCommands(
                decodeCommands(values[Keys.commands]).filterNot { it.id == commandId },
            )
        }
    }

    suspend fun saveTerminalShortcuts(shortcuts: List<TerminalShortcutPreference>) {
        require(shortcuts.size <= MAX_TERMINAL_SHORTCUTS) {
            "快捷键数量不能超过 $MAX_TERMINAL_SHORTCUTS 个"
        }
        require(shortcuts.map { it.id }.toSet().containsAll(TerminalShortcuts.requiredIds)) {
            "快捷键配置不完整"
        }
        require(shortcuts.map { it.id }.distinct().size == shortcuts.size) {
            "快捷键配置重复"
        }
        shortcuts.forEach { shortcut ->
            require(COMMAND_ID_REGEX.matches(shortcut.id)) { "快捷键 ID 不安全" }
            require(shortcut.label.trim().length in 1..MAX_SHORTCUT_LABEL_LENGTH) {
                "快捷键名称必须为 1～$MAX_SHORTCUT_LABEL_LENGTH 个字符"
            }
            when (shortcut.action) {
                TerminalShortcutAction.BUILTIN -> require(
                    TerminalShortcuts.isValidBuiltinAction(shortcut.payload),
                ) { "不支持的特殊按键" }
                TerminalShortcutAction.TEXT -> require(
                    shortcut.payload.length <= MAX_SHORTCUT_TEXT_LENGTH,
                ) { "快捷键文本过长" }
                TerminalShortcutAction.COMMAND -> require(
                    COMMAND_ID_REGEX.matches(shortcut.payload),
                ) { "绑定的指令 ID 不安全" }
            }
        }
        context.dataStore.edit { values ->
            values[Keys.terminalShortcuts] = encodeTerminalShortcuts(
                shortcuts.map { it.copy(label = it.label.trim()) },
            )
        }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        context.dataStore.edit { it[Keys.dynamicColors] = enabled }
    }

    suspend fun setExternalAppsConfigured(configured: Boolean) {
        context.dataStore.edit { it[Keys.externalAppsConfigured] = configured }
    }

    suspend fun setHideMaintenanceFromRecents(enabled: Boolean) {
        context.dataStore.edit { it[Keys.hideMaintenanceFromRecents] = enabled }
    }

    suspend fun setTermuxBackgroundProtection(enabled: Boolean) {
        context.dataStore.edit { it[Keys.termuxBackgroundProtection] = enabled }
    }

    suspend fun setTermuxWakeLockAlwaysOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.termuxWakeLockAlwaysOn] = enabled }
    }

    suspend fun setTermuxBackgroundProtectionActive(active: Boolean) {
        context.dataStore.edit { it[Keys.termuxBackgroundProtectionActive] = active }
    }

    suspend fun setBackgroundOperation(record: BackgroundOperationRecord?) {
        context.dataStore.edit { values ->
            if (record == null) {
                values.remove(Keys.backgroundOperation)
            } else {
                values[Keys.backgroundOperation] = encodeBackgroundOperation(record)
            }
        }
    }

    private fun decode(preferences: Preferences): RegistrySnapshot = RegistrySnapshot(
        protectedNames =
            preferences[Keys.protectedNames].orEmpty() -
                preferences[Keys.unprotectedNames].orEmpty(),
        unprotectedNames = preferences[Keys.unprotectedNames].orEmpty(),
        managedNames = preferences[Keys.managedNames].orEmpty(),
        ports = decodePorts(preferences[Keys.ports]),
        commandHistory = decodeCommandHistory(preferences[Keys.commandHistory]),
        commands = decodeCommands(preferences[Keys.commands]),
        commandTags = decodeCommandTags(preferences[Keys.commandTags]),
        terminalShortcuts = decodeTerminalShortcuts(preferences[Keys.terminalShortcuts]),
        firstDiscoveryCompleted = preferences[Keys.firstDiscoveryCompleted] ?: false,
        externalAppsConfigured = preferences[Keys.externalAppsConfigured] ?: false,
        dynamicColors = preferences[Keys.dynamicColors] ?: true,
        hideMaintenanceFromRecents =
            preferences[Keys.hideMaintenanceFromRecents] ?: true,
        termuxBackgroundProtection =
            preferences[Keys.termuxBackgroundProtection] ?: true,
        termuxWakeLockAlwaysOn =
            preferences[Keys.termuxWakeLockAlwaysOn] ?: false,
        termuxBackgroundProtectionActive =
            preferences[Keys.termuxBackgroundProtectionActive] ?: false,
        backgroundOperation = decodeBackgroundOperation(preferences[Keys.backgroundOperation]),
    )

    private fun encodeBackgroundOperation(record: BackgroundOperationRecord): String =
        listOf(
            record.id,
            record.type.name,
            record.status.name,
            record.startedEpochMillis.toString(),
            record.updatedEpochMillis.toString(),
            encodeText(record.label),
            encodeText(record.instanceName.orEmpty()),
            encodeText(record.message.orEmpty()),
        ).joinToString("|")

    private fun decodeBackgroundOperation(raw: String?): BackgroundOperationRecord? {
        val columns = raw?.split('|', limit = 8) ?: return null
        if (columns.size != 8 || !COMMAND_ID_REGEX.matches(columns[0])) return null
        val type = runCatching { BackgroundOperationType.valueOf(columns[1]) }.getOrNull()
            ?: return null
        val status = runCatching { BackgroundOperationStatus.valueOf(columns[2]) }.getOrNull()
            ?: return null
        val started = columns[3].toLongOrNull() ?: return null
        val updated = columns[4].toLongOrNull() ?: return null
        val label = decodeText(columns[5])?.takeIf(String::isNotBlank) ?: return null
        val instanceName = decodeText(columns[6])?.ifBlank { null }
        val message = decodeText(columns[7])?.ifBlank { null }
        return BackgroundOperationRecord(
            id = columns[0],
            type = type,
            label = label,
            instanceName = instanceName,
            status = status,
            startedEpochMillis = started,
            updatedEpochMillis = updated,
            message = message,
        )
    }

    private fun encodePorts(ports: Map<String, Int>): String = ports.entries
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}=${it.value}" }

    private fun decodePorts(raw: String?): Map<String, Int> = raw.orEmpty()
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = entry.substring(0, separator)
            val port = entry.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
            name to port
        }
        .toMap()

    private fun encodeCommandHistory(history: List<CommandHistoryEntry>): String =
        history.joinToString("\n") { entry ->
            listOf(
                entry.timestampEpochMillis.toString(),
                encodeText(entry.commandId),
                encodeText(entry.commandTitle),
                encodeText(entry.instanceName),
                entry.exitCode.toString(),
                encodeText(entry.output),
            ).joinToString("|")
        }

    private fun decodeCommandHistory(raw: String?): List<CommandHistoryEntry> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val columns = line.split('|', limit = 6)
            if (columns.size != 6) return@mapNotNull null
            CommandHistoryEntry(
                timestampEpochMillis = columns[0].toLongOrNull() ?: return@mapNotNull null,
                commandId = decodeText(columns[1]) ?: return@mapNotNull null,
                commandTitle = decodeText(columns[2]) ?: return@mapNotNull null,
                instanceName = decodeText(columns[3]) ?: return@mapNotNull null,
                exitCode = columns[4].toIntOrNull() ?: return@mapNotNull null,
                output = decodeText(columns[5]) ?: return@mapNotNull null,
            )
        }.take(MAX_COMMAND_HISTORY).toList()

    private fun encodeCommandTags(tags: List<CommandTag>): String =
        tags.joinToString("\n") { "${it.id}|${encodeText(it.name)}" }

    private fun decodeCommandTags(raw: String?): List<CommandTag> {
        if (raw == null) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val columns = line.split('|', limit = 2)
            if (columns.size != 2 || !COMMAND_TAG_ID_REGEX.matches(columns[0])) {
                return@mapNotNull null
            }
            val name = decodeText(columns[1])?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            CommandTag(columns[0], name)
        }.filterNot { tag ->
            tag.id to tag.name in LEGACY_DEFAULT_TAGS
        }.take(MAX_COMMAND_TAGS).toList()
    }

    private fun encodeCommands(commands: List<UserCommand>): String =
        commands.joinToString("\n") { command ->
            listOf(
                command.id,
                encodeText(command.title),
                encodeText(command.script),
                command.tagIds.sorted().joinToString(","),
            ).joinToString("|")
        }

    private fun decodeCommands(raw: String?): List<UserCommand> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val columns = line.split('|', limit = 4)
            if (columns.size != 4 || !COMMAND_ID_REGEX.matches(columns[0])) {
                return@mapNotNull null
            }
            val title = decodeText(columns[1])?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val script = decodeText(columns[2])?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            UserCommand(
                id = columns[0],
                title = title,
                script = script,
                tagIds = columns[3].split(',')
                    .filter(COMMAND_TAG_ID_REGEX::matches)
                    .toSet(),
            )
        }.take(MAX_COMMANDS).toList()

    private fun encodeTerminalShortcuts(
        shortcuts: List<TerminalShortcutPreference>,
    ): String = shortcuts.joinToString("\n") { shortcut ->
        listOf(
            shortcut.id,
            if (shortcut.enabled) "1" else "0",
            shortcut.action.name,
            encodeText(shortcut.label),
            encodeText(shortcut.payload),
            if (shortcut.appendEnter) "1" else "0",
        ).joinToString("|")
    }

    private fun decodeTerminalShortcuts(raw: String?): List<TerminalShortcutPreference> {
        if (raw == null) return TerminalShortcuts.defaults
        val decoded = raw.lineSequence().mapNotNull { line ->
            val columns = line.split('|', limit = 6)
            val id = columns.getOrNull(0)?.takeIf(COMMAND_ID_REGEX::matches)
                ?: return@mapNotNull null
            if (columns.size == 2) {
                val default = TerminalShortcuts.defaults.firstOrNull { it.id == id }
                    ?: return@mapNotNull null
                return@mapNotNull default.copy(enabled = columns[1] != "0")
            }
            if (columns.size != 6) return@mapNotNull null
            val action = runCatching {
                TerminalShortcutAction.valueOf(columns[2])
            }.getOrNull() ?: return@mapNotNull null
            val label = decodeText(columns[3])
                ?.takeIf { it.length in 1..MAX_SHORTCUT_LABEL_LENGTH }
                ?: return@mapNotNull null
            val payload = decodeText(columns[4]) ?: return@mapNotNull null
            TerminalShortcutPreference(
                id = id,
                label = label,
                action = action,
                payload = payload,
                enabled = columns[1] != "0",
                appendEnter = columns[5] == "1",
            )
        }.distinctBy { it.id }.take(MAX_TERMINAL_SHORTCUTS).toMutableList()
        TerminalShortcuts.defaults.forEach { default ->
            if (decoded.none { it.id == default.id }) {
                if (default.id == TerminalShortcuts.BACKSPACE) {
                    val enterIndex = decoded.indexOfFirst { it.id == TerminalShortcuts.ENTER }
                    if (enterIndex >= 0) decoded.add(enterIndex, default) else decoded += default
                } else {
                    decoded += default
                }
            }
        }
        return decoded
    }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun Set<String>.renameEntry(oldName: String, newName: String): Set<String> =
        if (oldName in this) this - oldName + newName else this

    companion object {
        private const val MAX_COMMAND_HISTORY = 50
        private const val MAX_COMMANDS = 200
        private const val MAX_COMMAND_TAGS = 20
        private const val MAX_COMMAND_TITLE_LENGTH = 40
        private const val MAX_COMMAND_SCRIPT_LENGTH = 32_768
        private const val MAX_COMMAND_TAG_NAME_LENGTH = 12
        private const val MAX_TERMINAL_SHORTCUTS = 32
        private const val MAX_SHORTCUT_LABEL_LENGTH = 12
        private const val MAX_SHORTCUT_TEXT_LENGTH = 4096
        private val LEGACY_DEFAULT_TAGS = setOf(
            "tools" to "工具",
            "system" to "系统",
            "software" to "软件安装",
        )
        private val COMMAND_TAG_ID_REGEX = Regex("[A-Za-z0-9_-]{1,48}")
        private val COMMAND_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
