package cn.termux.ubuntumanager.chroot

import android.content.Context
import android.util.Base64
import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.CommandResult
import cn.termux.ubuntumanager.model.LocalSessionBackend
import cn.termux.ubuntumanager.model.ChrootSession
import cn.termux.ubuntumanager.model.StorageProbe
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.model.UserCommandType
import cn.termux.ubuntumanager.permission.RootCommandExecutor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChrootRuntimeRecord(
    val name: String,
    val running: Boolean,
    val supervisorPid: Int,
    val sshPort: Int?,
    val localTerminalPort: Int?,
)

data class ChrootAutoStartConfiguration(
    val scriptReady: Boolean = false,
    val enabled: Boolean = false,
    val instances: Map<String, Int> = emptyMap(),
)

data class ChrootInspection(
    val rootGranted: Boolean,
    val backendReady: Boolean,
    val backendVersion: String? = null,
    val backupReady: Boolean = false,
    val autoStart: ChrootAutoStartConfiguration = ChrootAutoStartConfiguration(),
    val runtimes: List<ChrootRuntimeRecord> = emptyList(),
    val backups: List<BackupEntry> = emptyList(),
    val error: String? = null,
)

class ChrootClient(
    context: Context,
    private val root: RootCommandExecutor,
) {
    private val appContext = context.applicationContext
    private val packagedSparseCopy = File(
        appContext.applicationInfo.nativeLibraryDir,
        ChrootContract.PACKAGED_SPARSE_COPY_NAME,
    )
    @Volatile private var helperConfirmed = false
    private val autoStartMutex = Mutex()

    suspend fun bootstrap(force: Boolean = false): CommandResult {
        if (!force && helperConfirmed) return success("CHROOT_BACKEND_${ChrootContract.VERSION}")
        if (!force) {
            val existing = root.execute(backendProbeCommand(), 10_000)
            if (backendIsReady(existing)) {
                helperConfirmed = true
                return existing
            }
        }
        val helperInstall = root.execute(
            "set -e; /system/bin/mkdir -p /data/adb/cntermux; " +
                "/system/bin/cat > '${ChrootContract.HELPER_PATH}.tmp'; " +
                "/system/bin/chmod 700 '${ChrootContract.HELPER_PATH}.tmp'; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 " +
                "'${ChrootContract.HELPER_PATH}.tmp' 2>/dev/null || true; " +
                "/system/bin/mv -f '${ChrootContract.HELPER_PATH}.tmp' " +
                "'${ChrootContract.HELPER_PATH}'; " +
                "'${ChrootContract.HELPER_PATH}' bootstrap",
            20_000,
            ByteArrayInputStream(ChrootSupervisorScript.content.toByteArray()),
        )
        if (!helperInstall.isSuccess) return helperInstall
        val autoStartInstall = root.execute(
            "set -e; /system/bin/mkdir -p /data/adb/service.d /data/adb/cntermux; " +
                "/system/bin/cat > '${ChrootContract.AUTO_START_SCRIPT_PATH}.tmp'; " +
                "/system/bin/chmod 700 '${ChrootContract.AUTO_START_SCRIPT_PATH}.tmp'; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 " +
                "'${ChrootContract.AUTO_START_SCRIPT_PATH}.tmp' 2>/dev/null || true; " +
                "/system/bin/mv -f '${ChrootContract.AUTO_START_SCRIPT_PATH}.tmp' " +
                "'${ChrootContract.AUTO_START_SCRIPT_PATH}'; " +
                "if [ ! -e '${ChrootContract.AUTO_START_CONFIG_PATH}' ]; then " +
                "/system/bin/printf '${ChrootContract.AUTO_START_CONFIG_VERSION}|0\\n' > " +
                "'${ChrootContract.AUTO_START_CONFIG_PATH}.tmp'; " +
                "/system/bin/chmod 600 '${ChrootContract.AUTO_START_CONFIG_PATH}.tmp'; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 " +
                "'${ChrootContract.AUTO_START_CONFIG_PATH}.tmp' 2>/dev/null || true; " +
                "/system/bin/mv -f '${ChrootContract.AUTO_START_CONFIG_PATH}.tmp' " +
                "'${ChrootContract.AUTO_START_CONFIG_PATH}'; fi; " +
                "'${ChrootContract.AUTO_START_SCRIPT_PATH}' --version",
            20_000,
            ByteArrayInputStream(ChrootAutoStartScript.content.toByteArray()),
        )
        if (!autoStartInstall.isSuccess) return autoStartInstall
        if (!packagedSparseCopy.isFile) {
            return failure("APK 中缺少 ARM64 稀疏镜像复制器")
        }
        val toolInstall = root.execute(
            "set -e; /system/bin/cat > '${ChrootContract.SPARSE_COPY_PATH}'; " +
                "/system/bin/chmod 700 '${ChrootContract.SPARSE_COPY_PATH}'; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 '${ChrootContract.SPARSE_COPY_PATH}' 2>/dev/null || true; " +
                backendProbeCommand(),
            20_000,
            FileInputStream(packagedSparseCopy),
        )
        helperConfirmed = backendIsReady(toolInstall)
        return toolInstall
    }

    suspend fun version(timeoutMillis: Long = 20_000): CommandResult {
        val ready = bootstrap()
        if (!ready.isSuccess) return ready
        return success("Root Chroot ${ChrootContract.VERSION} · Ubuntu ${ChrootContract.UBUNTU_VERSION}")
    }

    suspend fun storageProbe(timeoutMillis: Long = 20_000): StorageProbe {
        val ready = bootstrap()
        if (!ready.isSuccess) return StorageProbe(false, false, false)
        val result = root.execute(
            "test -d '/storage/emulated/0' && test -w '/storage/emulated/0' && " +
                "test -d '${ChrootContract.CACHE_DIRECTORY}' && " +
                "test -w '${ChrootContract.CACHE_DIRECTORY}'",
            timeoutMillis,
        )
        return StorageProbe(true, true, result.isSuccess)
    }

    suspend fun prepareBackupDirectory(): CommandResult {
        val ready = bootstrap()
        if (!ready.isSuccess) return ready
        return root.execute(
            "/system/bin/mkdir -p '${ChrootContract.PORTABLE_BACKUP_DIRECTORY}' && " +
                "test -w '${ChrootContract.PORTABLE_BACKUP_DIRECTORY}'",
            20_000,
        )
    }

    /**
     * Performs the complete read-only environment refresh through one Alpha su process.
     * Backend installation is intentionally separate because it writes privileged files.
     */
    suspend fun inspect(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ChrootInspection {
        val result = root.execute(
            "/system/bin/printf 'CNTERMUX_ROOT_OK\\n'; " +
                "backend=\$('${ChrootContract.HELPER_PATH}' bootstrap 2>/dev/null || true); " +
                "native=\$('${ChrootContract.SPARSE_COPY_PATH}' --version 2>/dev/null || true); " +
                "autostart=\$('${ChrootContract.AUTO_START_SCRIPT_PATH}' --version 2>/dev/null || true); " +
                "/system/bin/printf 'CNTERMUX_BACKEND|%s|%s|%s\\n' " +
                "\"\${backend}\" \"\${native}\" \"\${autostart}\"; " +
                "if [ \"\${backend}\" != 'CHROOT_BACKEND_${ChrootContract.VERSION}' ] || " +
                "[ \"\${native}\" != '${ChrootContract.SPARSE_COPY_VERSION}' ] || " +
                "[ \"\${autostart}\" != '${ChrootContract.AUTO_START_VERSION}' ]; then exit 0; fi; " +
                "backup=0; [ -d '/storage/emulated/0' ] && [ -w '/storage/emulated/0' ] && " +
                "[ -d '${ChrootContract.CACHE_DIRECTORY}' ] && " +
                "[ -w '${ChrootContract.CACHE_DIRECTORY}' ] && backup=1; " +
                "/system/bin/printf 'CNTERMUX_BACKUP|%s\\n' \"\${backup}\"; " +
                autoStartSnapshotShell() +
                runtimeSnapshotShell("CNTERMUX_RUNTIME|") +
                backupSnapshotShell("CNTERMUX_BACKUP_ENTRY|"),
            timeoutMillis,
        )
        if (!result.isSuccess) {
            return ChrootInspection(
                rootGranted = false,
                backendReady = false,
                error = result.bestError,
            )
        }
        val lines = result.stdout.lineSequence().map(String::trim).toList()
        val rootGranted = "CNTERMUX_ROOT_OK" in lines
        val backendReady = lines.any {
            it == "CNTERMUX_BACKEND|CHROOT_BACKEND_${ChrootContract.VERSION}|" +
                "${ChrootContract.SPARSE_COPY_VERSION}|${ChrootContract.AUTO_START_VERSION}"
        }
        helperConfirmed = backendReady
        if (!rootGranted || !backendReady) {
            return ChrootInspection(
                rootGranted = rootGranted,
                backendReady = false,
                error = if (rootGranted) {
                    "Root Chroot 后端未安装或版本不匹配，请在设置中重新配置"
                } else {
                    "Alpha 没有返回 Root 授权标记"
                },
            )
        }
        return ChrootInspection(
            rootGranted = true,
            backendReady = true,
            backendVersion =
                "Root Chroot ${ChrootContract.VERSION} · Ubuntu ${ChrootContract.UBUNTU_VERSION}",
            backupReady = "CNTERMUX_BACKUP|1" in lines,
            autoStart = parseAutoStartSnapshot(lines.asSequence()),
            runtimes = parseRuntimeSnapshot(
                lines.asSequence().mapNotNull {
                    it.removePrefixOrNull("CNTERMUX_RUNTIME|")
                }.joinToString("\n"),
            ),
            backups = parseBackupSnapshot(
                lines.asSequence().mapNotNull {
                    it.removePrefixOrNull("CNTERMUX_BACKUP_ENTRY|")
                },
            ),
        )
    }

    suspend fun listContainers(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<String> {
        bootstrap().requireSuccess()
        val result = root.execute(
            "for f in '${ChrootContract.IMAGE_DIRECTORY}'/*.img; do " +
                "[ -f \"\${f}\" ] || continue; " +
                "n=\${f##*/}; /system/bin/printf '%s\\n' \"\${n%.img}\"; done",
            timeoutMillis,
        ).requireSuccess()
        return result.stdout.lineSequence().map(String::trim)
            .filter(::isValidName).distinct().sorted().toList()
    }

    suspend fun readAutoStartConfiguration(): ChrootAutoStartConfiguration {
        bootstrap().requireSuccess()
        val result = root.execute(autoStartSnapshotShell(), DEFAULT_TIMEOUT_MILLIS)
            .requireSuccess()
        return parseAutoStartSnapshot(result.stdout.lineSequence())
    }

    suspend fun setAutoStartEnabled(enabled: Boolean): ChrootAutoStartConfiguration =
        autoStartMutex.withLock {
            val current = readAutoStartConfiguration()
            writeAutoStartConfiguration(current.copy(enabled = enabled))
        }

    suspend fun setInstanceAutoStart(
        name: String,
        port: Int,
        enabled: Boolean,
    ): ChrootAutoStartConfiguration = autoStartMutex.withLock {
        requireValidName(name)
        requireValidPort(port)
        val current = readAutoStartConfiguration()
        val entries = if (enabled) {
            current.instances + (name to port)
        } else {
            current.instances - name
        }
        writeAutoStartConfiguration(current.copy(instances = entries))
    }

    suspend fun renameAutoStartInstance(
        oldName: String,
        newName: String,
        port: Int,
    ): ChrootAutoStartConfiguration = autoStartMutex.withLock {
        requireValidName(oldName)
        requireValidName(newName)
        requireValidPort(port)
        val current = readAutoStartConfiguration()
        if (oldName !in current.instances) return@withLock current
        writeAutoStartConfiguration(
            current.copy(instances = current.instances - oldName + (newName to port)),
        )
    }

    suspend fun updateAutoStartPort(
        name: String,
        port: Int,
    ): ChrootAutoStartConfiguration = autoStartMutex.withLock {
        requireValidName(name)
        requireValidPort(port)
        val current = readAutoStartConfiguration()
        if (name !in current.instances) return@withLock current
        writeAutoStartConfiguration(current.copy(instances = current.instances + (name to port)))
    }

    suspend fun removeAutoStartInstance(name: String): ChrootAutoStartConfiguration =
        autoStartMutex.withLock {
            requireValidName(name)
            val current = readAutoStartConfiguration()
            if (name !in current.instances) return@withLock current
            writeAutoStartConfiguration(current.copy(instances = current.instances - name))
        }

    private suspend fun writeAutoStartConfiguration(
        configuration: ChrootAutoStartConfiguration,
    ): ChrootAutoStartConfiguration {
        require(configuration.instances.size <= MAX_AUTO_START_INSTANCES) {
            "开机启动实例不能超过 $MAX_AUTO_START_INSTANCES 个"
        }
        configuration.instances.forEach { (name, port) ->
            requireValidName(name)
            requireValidPort(port)
        }
        val normalized = configuration.copy(
            scriptReady = true,
            instances = configuration.instances.toSortedMap(),
        )
        val result = root.execute(
            "set -e; /system/bin/cat > '${ChrootContract.AUTO_START_CONFIG_PATH}.tmp'; " +
                "/system/bin/chmod 600 '${ChrootContract.AUTO_START_CONFIG_PATH}.tmp'; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 " +
                "'${ChrootContract.AUTO_START_CONFIG_PATH}.tmp' 2>/dev/null || true; " +
                "/system/bin/mv -f '${ChrootContract.AUTO_START_CONFIG_PATH}.tmp' " +
                "'${ChrootContract.AUTO_START_CONFIG_PATH}'",
            DEFAULT_TIMEOUT_MILLIS,
            ByteArrayInputStream(encodeAutoStartConfiguration(normalized).toByteArray()),
        )
        result.requireSuccess()
        return normalized
    }

    /** Reads every instance and its Root-only runtime files through one Alpha su process. */
    suspend fun runtimeSnapshot(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): List<ChrootRuntimeRecord> {
        bootstrap().requireSuccess()
        val result = root.execute(
            runtimeSnapshotShell(),
            timeoutMillis,
        ).requireSuccess()
        return parseRuntimeSnapshot(result.stdout)
    }

    suspend fun listSessions(timeoutMillis: Long = 20_000): List<ChrootSession> {
        return runtimeSnapshot(timeoutMillis).filter { it.running }.map { runtime ->
            ChrootSession(
                pid = runtime.supervisorPid,
                container = runtime.name,
                type = "chroot",
                user = "root",
                uptime = "运行中",
                command = "CNTERMUX_CHROOT_SUPERVISOR",
            )
        }
    }

    suspend fun isPortOpen(port: Int, timeoutMillis: Long = 10_000): Boolean =
        withContext(Dispatchers.IO) {
            requireValidPort(port)
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis.coerceAtMost(2_000).toInt()) }
                true
            }.getOrDefault(false)
        }

    suspend fun runningSshPort(name: String): Int? {
        requireValidName(name)
        val result = root.execute(
            "/system/bin/cat '${ChrootContract.RUNTIME_DIRECTORY}/$name/ssh.port' 2>/dev/null",
            5_000,
        )
        return result.stdout.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
    }

    suspend fun waitForPortState(
        port: Int,
        expectedOpen: Boolean,
        timeoutMillis: Long,
    ): CommandResult {
        requireValidPort(port)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port) == expectedOpen) return success()
            delay(250)
        }
        return failure(if (expectedOpen) "等待端口 $port 开始监听超时" else "等待端口 $port 释放超时")
    }

    suspend fun ensureLocalTerminal(name: String): CommandResult {
        requireValidName(name)
        return executeScript(name, "command -v ttyd && command -v tmux")
    }

    suspend fun activeContainerMaintenance(name: String): String? = null

    suspend fun existingLocalTerminalPort(name: String): Int? {
        requireValidName(name)
        val path = "${ChrootContract.RUNTIME_DIRECTORY}/$name/ttyd.port"
        val result = root.execute("/system/bin/cat '$path' 2>/dev/null", 5_000)
        return result.stdout.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
            ?.takeIf { isPortOpen(it) }
    }

    suspend fun localTerminalBackend(name: String): LocalSessionBackend =
        if (executeScript(name, "tmux has-session -t cntermux 2>/dev/null").isSuccess) {
            LocalSessionBackend.TMUX
        } else {
            LocalSessionBackend.UNKNOWN
        }

    suspend fun captureLocalTerminalHistory(name: String): String {
        requireValidName(name)
        val result = executeScript(
            name,
            "tmux capture-pane -p -S - -t cntermux 2>/dev/null || true",
        )
        return if (result.isSuccess) result.stdout.trimEnd() else ""
    }

    suspend fun hardenExistingLocalTerminal(name: String): CommandResult {
        requireValidName(name)
        val port = root.execute(
            "/system/bin/cat '${ChrootContract.RUNTIME_DIRECTORY}/$name/ttyd.port' 2>/dev/null",
            5_000,
        ).stdout.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: return failure("本地会话端口记录无效")
        val compatibility = executeScript(
            name,
            "mkdir -p /etc/profile.d; " +
                "printf '%s\\n' 'export LANG=C.UTF-8' 'export LC_ALL=C.UTF-8' " +
                "> /etc/profile.d/cntermux-locale.sh; " +
                "chmod 644 /etc/profile.d/cntermux-locale.sh; " +
                "if tmux has-session -t cntermux 2>/dev/null; then " +
                "tmux set-environment -g LANG C.UTF-8; " +
                "tmux set-environment -g LC_ALL C.UTF-8; fi; " +
                "pid=\$(cat /run/cntermux/ttyd.pid 2>/dev/null || true); " +
                "[ -n \"\${pid}\" ] || exit 1; " +
                "tr '\\000' '\\n' < /proc/\${pid}/environ 2>/dev/null; " +
                "printf '%s\\n' ---CMD---; " +
                "tr '\\000' ' ' < /proc/\${pid}/cmdline 2>/dev/null",
        )
        if (
            compatibility.isSuccess &&
            compatibility.stdout.lineSequence().any { it == "LANG=C.UTF-8" } &&
            (
                compatibility.stdout.contains("rendererType=canvas") ||
                    compatibility.stdout.contains("rendererType canvas")
            ) &&
            (
                compatibility.stdout.contains("fontSize=10") ||
                    compatibility.stdout.contains("fontSize 10")
            ) &&
            (
                compatibility.stdout.contains("letterSpacing=0") ||
                    compatibility.stdout.contains("letterSpacing 0")
            ) &&
            (
                compatibility.stdout.contains("fontFamily=serif-monospace") ||
                    compatibility.stdout.contains("fontFamily serif-monospace")
            ) &&
            compatibility.stdout.contains("tmux -u new-session")
        ) {
            return success("ALREADY_COMPATIBLE")
        }

        val stopped = stopLocalTerminal(name, port)
        if (!stopped.isSuccess) return stopped
        val started = startLocalTerminal(name, port)
        if (!started.isSuccess) return started
        return waitForPortState(port, expectedOpen = true, timeoutMillis = 10_000)
    }

    suspend fun startLocalTerminal(name: String, port: Int): CommandResult {
        requireValidName(name); requireValidPort(port)
        val run = "${ChrootContract.RUNTIME_DIRECTORY}/$name"
        val script = """
            set -eu
            mkdir -p /run/cntermux /var/log/cntermux
            mkdir -p /etc/profile.d
            printf '%s\n' 'export LANG=C.UTF-8' 'export LC_ALL=C.UTF-8' \
              >/etc/profile.d/cntermux-locale.sh
            chmod 644 /etc/profile.d/cntermux-locale.sh
            if tmux has-session -t cntermux 2>/dev/null; then
              tmux set-environment -g LANG C.UTF-8
              tmux set-environment -g LC_ALL C.UTF-8
            fi
            if [ -f /run/cntermux/ttyd.pid ] && kill -0 "${'$'}(cat /run/cntermux/ttyd.pid)" 2>/dev/null; then
              exit 0
            fi
            nohup setsid env LANG=C.UTF-8 LC_ALL=C.UTF-8 \
              ttyd -W -i 127.0.0.1 -p $port \
              -t rendererType=canvas \
              -t fontSize=10 \
              -t letterSpacing=0 \
              -t 'fontFamily=serif-monospace,Noto Sans CJK SC,sans-serif' \
              tmux -u new-session -A -s cntermux \
              >/var/log/cntermux/ttyd.log 2>&1 </dev/null &
            printf '%s\n' "${'$'}!" >/run/cntermux/ttyd.pid
        """.trimIndent()
        val result = executeScript(name, script)
        if (result.isSuccess) {
            root.execute(
                "set -e; /system/bin/printf '%s\\n' '$port' > '$run/ttyd.port'; " +
                    "pid=\$(/system/bin/nsenter -t \$(/system/bin/cat '$run/supervisor.pid') -m -- " +
                    "/system/bin/chroot '$run/rootfs' /bin/cat /run/cntermux/ttyd.pid); " +
                    "/system/bin/printf '%s\\n' \"\${pid}\" > '$run/ttyd.pid'",
                10_000,
            )
        }
        return result
    }

    suspend fun stopLocalTerminal(name: String, port: Int): CommandResult {
        requireValidName(name); requireValidPort(port)
        val result = executeScript(
            name,
            "pid=\$(cat /run/cntermux/ttyd.pid 2>/dev/null || true); " +
                "[ -z \"\${pid}\" ] || kill \"\${pid}\" 2>/dev/null || true; " +
                "rm -f /run/cntermux/ttyd.pid",
        )
        root.execute(
            "/system/bin/rm -f '${ChrootContract.RUNTIME_DIRECTORY}/$name/ttyd.pid' " +
                "'${ChrootContract.RUNTIME_DIRECTORY}/$name/ttyd.port'",
            5_000,
        )
        return result
    }

    suspend fun endPersistentLocalSession(name: String): CommandResult {
        requireValidName(name)
        return executeScript(name, "tmux kill-session -t cntermux 2>/dev/null || true")
    }

    suspend fun localTerminalLogs(name: String): String {
        val result = executeScript(name, "tail -n 60 /var/log/cntermux/ttyd.log 2>/dev/null || true")
        return if (result.isSuccess) result.stdout.trim() else result.bestError
    }

    suspend fun startSsh(name: String, port: Int): CommandResult {
        requireValidName(name); requireValidPort(port)
        bootstrap().requireSuccess()
        val status = helper("status $name", 10_000)
        if (status.isSuccess && status.stdout.lineSequence().any { it.trim() == "RUNNING" }) {
            if (runningSshPort(name) == port && isPortOpen(port)) return success("ALREADY_RUNNING")
            val stopped = helper("stop $name", 30_000)
            if (!stopped.isSuccess) return stopped
        }
        return helper("start $name $port", 30_000)
    }

    suspend fun stopSsh(name: String, port: Int): CommandResult = stop(name)

    suspend fun reloadSsh(name: String, port: Int): CommandResult {
        requireValidName(name); requireValidPort(port)
        val run = "${ChrootContract.RUNTIME_DIRECTORY}/$name"
        return root.execute(
            "set -e; pid=\$(/system/bin/cat '$run/sshd.pid'); " +
                "[ \"\$(/system/bin/readlink /proc/\${pid}/root)\" = '$run/rootfs' ]; " +
                "kill -HUP \"\${pid}\"",
            10_000,
        )
    }

    suspend fun stop(name: String, force: Boolean = false): CommandResult {
        requireValidName(name)
        return helper("stop $name", 30_000)
    }

    suspend fun create(name: String, image: String = DEFAULT_UBUNTU_IMAGE): CommandResult {
        requireValidName(name)
        require(image == DEFAULT_UBUNTU_IMAGE) { "只支持 Ubuntu ${ChrootContract.UBUNTU_VERSION}" }
        bootstrap().requireSuccess()
        val remote = "${ChrootContract.CACHE_DIRECTORY}/ubuntu-base-${ChrootContract.UBUNTU_VERSION}.tar.gz"
        val cached = root.execute(
            "[ -f '$remote' ] && " +
                "[ \"\$(/system/bin/sha256sum '$remote' | /system/bin/cut -d' ' -f1)\" = " +
                "'${ChrootContract.UBUNTU_BASE_SHA256}' ]",
            60_000,
        )
        if (cached.isSuccess) return helper("install $name $remote", CREATE_TIMEOUT_MILLIS)
        val archive = downloadUbuntuBase()
        try {
            val upload = root.execute(
                "set -e; /system/bin/cat > '$remote.tmp'; " +
                    "actual=\$(/system/bin/sha256sum '$remote.tmp' | /system/bin/cut -d' ' -f1); " +
                    "[ \"\${actual}\" = '${ChrootContract.UBUNTU_BASE_SHA256}' ]; " +
                    "/system/bin/mv -f '$remote.tmp' '$remote'; /system/bin/chmod 600 '$remote'",
                CREATE_TIMEOUT_MILLIS,
                archive.inputStream(),
            )
            if (!upload.isSuccess) return upload
            return helper("install $name $remote", CREATE_TIMEOUT_MILLIS)
        } finally {
            archive.delete()
        }
    }

    suspend fun initializeSsh(name: String): CommandResult = executeScript(
        name,
        "export DEBIAN_FRONTEND=noninteractive; apt-get update && " +
            "apt-get install -y openssh-server && mkdir -p /run/sshd /etc/ssh/sshd_config.d && " +
            "ssh-keygen -A && printf 'PermitRootLogin yes\\nPasswordAuthentication yes\\nKbdInteractiveAuthentication no\\nUsePAM no\\n' " +
            ">/etc/ssh/sshd_config.d/99-cntermux.conf && sshd -t",
        CREATE_TIMEOUT_MILLIS,
    )

    suspend fun ensureDefaultRootPassword(name: String): CommandResult {
        requireValidName(name)
        val result = executeScript(
            name,
            "field=\$(awk -F: '${'$'}1 == \"root\" {print ${'$'}2}' /etc/shadow); " +
                "case \"\${field}\" in ''|'!'|'!!'|'*'|'!*') printf 'root:${ChrootContract.DEFAULT_ROOT_PASSWORD}\\n' | chpasswd; " +
                "printf '$DEFAULT_PASSWORD_SET_MARKER\\n';; *) printf '$DEFAULT_PASSWORD_UNCHANGED_MARKER\\n';; esac",
        )
        return result
    }

    suspend fun setRootPassword(name: String, password: String): CommandResult {
        requireValidName(name); requireValidRootPassword(password)
        val encoded = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)
        return executeScript(
            name,
            "password=\$(printf '%s' '$encoded' | base64 -d); " +
                "printf 'root:%s\\n' \"\${password}\" | chpasswd; unset password; " +
                "sshd -t",
        )
    }

    suspend fun rename(oldName: String, newName: String): CommandResult {
        requireValidName(oldName); requireValidName(newName)
        val moved = root.execute(
            "set -e; old='${ChrootContract.IMAGE_DIRECTORY}/$oldName.img'; " +
                "new='${ChrootContract.IMAGE_DIRECTORY}/$newName.img'; " +
                "[ -f \"\${old}\" ]; [ ! -e \"\${new}\" ]; /system/bin/mv \"\${old}\" \"\${new}\"; " +
                "[ ! -d '${ChrootContract.RUNTIME_DIRECTORY}/$oldName' ] || /system/bin/mv " +
                "'${ChrootContract.RUNTIME_DIRECTORY}/$oldName' '${ChrootContract.RUNTIME_DIRECTORY}/$newName'; " +
                "[ ! -f '${ChrootContract.LOG_DIRECTORY}/$oldName.log' ] || /system/bin/mv " +
                "'${ChrootContract.LOG_DIRECTORY}/$oldName.log' '${ChrootContract.LOG_DIRECTORY}/$newName.log'",
            LONG_TIMEOUT_MILLIS,
        )
        if (!moved.isSuccess) return moved
        return executeScript(
            newName,
            "printf '%s\\n' '$newName' >/etc/hostname; " +
                "printf '127.0.0.1 localhost\\n127.0.1.1 %s\\n' '$newName' >/etc/hosts",
        )
    }

    suspend fun executeCommand(name: String, command: UserCommand): CommandResult {
        require(command.type == UserCommandType.COMMAND) { "按键动作不能作为后台 Shell 命令执行" }
        return executeScript(name, command.script, LONG_TIMEOUT_MILLIS)
    }

    suspend fun cloneInstance(source: String, target: String): CommandResult {
        requireValidName(source); requireValidName(target); require(source != target)
        return root.execute(
            "set -e; src='${ChrootContract.IMAGE_DIRECTORY}/$source.img'; " +
                "dst='${ChrootContract.IMAGE_DIRECTORY}/$target.img'; [ -f \"\${src}\" ]; [ ! -e \"\${dst}\" ]; " +
                "tmp=\"\${dst}.tmp\"; /system/bin/rm -f \"\${tmp}\"; " +
                "'${ChrootContract.SPARSE_COPY_PATH}' \"\${src}\" \"\${tmp}\"; " +
                "/system/bin/mv \"\${tmp}\" \"\${dst}\"; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 \"\${dst}\" 2>/dev/null || true",
            LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun initializeCloneIdentity(name: String): CommandResult = executeScript(
        name,
        "rm -f /etc/ssh/ssh_host_*; ssh-keygen -A; : >/etc/machine-id; " +
            "printf '%s\\n' '$name' >/etc/hostname",
    )

    suspend fun remove(name: String): CommandResult {
        requireValidName(name)
        return root.execute(
            "set -e; /system/bin/rm -f '${ChrootContract.IMAGE_DIRECTORY}/$name.img'; " +
                "/system/bin/rm -rf '${ChrootContract.RUNTIME_DIRECTORY}/$name'; " +
                "/system/bin/rm -f '${ChrootContract.LOG_DIRECTORY}/$name.log' " +
                "'${ChrootContract.LOG_DIRECTORY}/$name-supervisor.log'",
            LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun backup(name: String, displayName: String): BackupEntry {
        requireValidName(name)
        bootstrap().requireSuccess()
        val normalizedDisplayName = requireValidBackupDisplayName(displayName)
        val encodedDisplayName = Base64.encodeToString(
            normalizedDisplayName.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = portableBackupFileName(name, timestamp, normalizedDisplayName)
        val path = "${ChrootContract.PORTABLE_BACKUP_DIRECTORY}/$file"
        val result = root.execute(
            "set -e; src='${ChrootContract.IMAGE_DIRECTORY}/$name.img'; dst='$path'; " +
                "tmp='${path}.partial'; [ -f \"\${src}\" ]; [ ! -e \"\${dst}\" ]; " +
                "/system/bin/rm -f \"\${tmp}\"; /system/bin/mkdir -p " +
                "'${ChrootContract.PORTABLE_BACKUP_DIRECTORY}'; " +
                "actual=\$(/system/bin/sha256sum \"\${src}\" | /system/bin/cut -d' ' -f1); " +
                "'${ChrootContract.SPARSE_COPY_PATH}' --export-archive \"\${src}\" \"\${tmp}\" " +
                "'$name' '$encodedDisplayName' \"\${actual}\"; " +
                "/system/bin/mv \"\${tmp}\" \"\${dst}\"; " +
                "logical=\$(/system/bin/stat -c %s \"\${src}\"); " +
                "bytes=\$(/system/bin/stat -c %s \"\${dst}\"); " +
                "blocks=\$(( (bytes + 1023) / 1024 )); " +
                "mtime=\$(/system/bin/stat -c %Y \"\${dst}\"); " +
                "/system/bin/printf 'CNTERMUX_PORTABLE_CREATED|%s|%s|%s\\n' " +
                "\"\${logical}\" \"\${blocks}\" \"\${mtime}\"",
            LONG_TIMEOUT_MILLIS,
        )
        if (!result.isSuccess) {
            root.execute(
                "/system/bin/rm -f '${path}.partial'",
                DEFAULT_TIMEOUT_MILLIS,
            )
            result.requireSuccess()
        }
        val fields = result.stdout.lineSequence()
            .firstOrNull { it.startsWith("CNTERMUX_PORTABLE_CREATED|") }
            ?.split('|')
        check(fields?.size == 4) { "备份归档完成，但没有返回容量信息" }
        return BackupEntry(
            fileName = file,
            path = path,
            instanceName = name,
            sizeBytes = (fields[2].toLongOrNull() ?: 0) * 1024,
            modifiedEpochSeconds = fields[3].toLongOrNull() ?: System.currentTimeMillis() / 1_000,
            checksumPresent = true,
            displayName = normalizedDisplayName,
            logicalSizeBytes = fields[1].toLongOrNull() ?: 0,
            metadataPresent = true,
            portableArchive = true,
        )
    }

    suspend fun listBackups(): List<BackupEntry> {
        val result = root.execute(
            backupSnapshotShell(""),
            DEFAULT_TIMEOUT_MILLIS,
        ).requireSuccess()
        return parseBackupSnapshot(result.stdout.lineSequence())
    }

    suspend fun renameBackup(backup: BackupEntry, displayName: String): BackupEntry {
        requireSafeBackup(backup)
        val normalizedDisplayName = requireValidBackupDisplayName(displayName)
        val encodedDisplayName = Base64.encodeToString(
            normalizedDisplayName.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        if (backup.portableArchive) {
            bootstrap().requireSuccess()
            val match = PORTABLE_BACKUP_REGEX.matchEntire(backup.fileName)
                ?: error("便携备份文件名不合法")
            val file = portableBackupFileName(
                backup.instanceName,
                match.groupValues[2],
                normalizedDisplayName,
            )
            val path = "${ChrootContract.PORTABLE_BACKUP_DIRECTORY}/$file"
            root.execute(
                "set -e; [ -f '${backup.path}' ]; " +
                    if (path == backup.path) {
                        "'${ChrootContract.SPARSE_COPY_PATH}' --rename-archive " +
                            "'${backup.path}' '$encodedDisplayName'"
                    } else {
                        "[ ! -e '$path' ]; '${ChrootContract.SPARSE_COPY_PATH}' " +
                            "--rename-archive '${backup.path}' '$encodedDisplayName'; " +
                            "/system/bin/mv '${backup.path}' '$path'"
                    },
                DEFAULT_TIMEOUT_MILLIS,
            ).requireSuccess()
            return backup.copy(
                fileName = file,
                path = path,
                displayName = normalizedDisplayName,
                metadataPresent = true,
            )
        }
        root.execute(
            "set -e; src='${backup.path}'; [ -f \"\${src}\" ]; " +
                "logical=\$(/system/bin/stat -c %s \"\${src}\"); " +
                "created=\$(/system/bin/stat -c %Y \"\${src}\"); " +
                "/system/bin/printf '{\"version\":1,\"displayNameBase64\":\"$encodedDisplayName\"," +
                "\"instanceName\":\"${backup.instanceName}\",\"logicalSizeBytes\":%s," +
                "\"createdEpochSeconds\":%s}\\n' \"\${logical}\" \"\${created}\" " +
                "> \"\${src}.meta.json.tmp\"; " +
                "/system/bin/mv -f \"\${src}.meta.json.tmp\" \"\${src}.meta.json\"",
            DEFAULT_TIMEOUT_MILLIS,
        ).requireSuccess()
        return backup.copy(displayName = normalizedDisplayName, metadataPresent = true)
    }

    suspend fun validateBackup(backup: BackupEntry): String {
        requireSafeBackup(backup)
        if (backup.portableArchive) {
            bootstrap().requireSuccess()
            val result = root.execute(
                "'${ChrootContract.SPARSE_COPY_PATH}' --inspect-archive '${backup.path}'",
                DEFAULT_TIMEOUT_MILLIS,
            ).requireSuccess()
            val fields = result.stdout.lineSequence()
                .firstOrNull { it.startsWith("CNTERMUX_ARCHIVE|") }
                ?.split('|')
            require(fields?.size == 5 && fields[2] == backup.instanceName) {
                "便携备份元数据与文件名不一致"
            }
            return fields[2]
        }
        if (backup.checksumPresent) {
            root.execute(
                "cd '${ChrootContract.BACKUP_DIRECTORY}' && /system/bin/sha256sum -c '${backup.fileName}.sha256'",
                LONG_TIMEOUT_MILLIS,
            ).requireSuccess()
        }
        return backup.instanceName
    }

    suspend fun restore(backup: BackupEntry): CommandResult {
        requireSafeBackup(backup)
        val ready = bootstrap()
        if (!ready.isSuccess) return ready
        val target = "${ChrootContract.IMAGE_DIRECTORY}/${backup.instanceName}.img"
        if (backup.portableArchive) {
            val image = "${ChrootContract.CACHE_DIRECTORY}/restore-${backup.instanceName}.img"
            return root.execute(
                "set -e; archive='${backup.path}'; image='$image'; [ -f \"\${archive}\" ]; " +
                    "/system/bin/rm -f \"\${image}\"; " +
                    "info=\$('${ChrootContract.SPARSE_COPY_PATH}' --inspect-archive \"\${archive}\"); " +
                    "expected=\$(/system/bin/printf '%s\\n' \"\${info}\" | /system/bin/cut -d'|' -f5); " +
                    "'${ChrootContract.SPARSE_COPY_PATH}' --restore-archive " +
                    "\"\${archive}\" \"\${image}\"; " +
                    "actual=\$(/system/bin/sha256sum \"\${image}\" | /system/bin/cut -d' ' -f1); " +
                    "[ \"\${actual}\" = \"\${expected}\" ]; " +
                    "/system/bin/chcon u:object_r:magisk_file:s0 \"\${image}\" 2>/dev/null || true; " +
                    "/system/bin/mv -f \"\${image}\" '$target'; " +
                    "/system/bin/printf 'CNTERMUX_ARCHIVE_RESTORED\\n'",
                LONG_TIMEOUT_MILLIS,
            )
        }
        return root.execute(
            "set -e; src='${backup.path}'; dst='$target'; [ -f \"\${src}\" ]; " +
                "tmp='${ChrootContract.CACHE_DIRECTORY}/restore-${backup.instanceName}.img'; /system/bin/rm -f \"\${tmp}\"; " +
                "'${ChrootContract.SPARSE_COPY_PATH}' \"\${src}\" \"\${tmp}\"; " +
                "/system/bin/chcon u:object_r:magisk_file:s0 \"\${tmp}\" 2>/dev/null || true; " +
                "/system/bin/mv -f \"\${tmp}\" \"\${dst}\"",
            LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun deleteBackup(backup: BackupEntry): CommandResult {
        requireSafeBackup(backup)
        if (backup.portableArchive) {
            return root.execute(
                "/system/bin/rm -f '${backup.path}' '${backup.path}.partial'",
                DEFAULT_TIMEOUT_MILLIS,
            )
        }
        return root.execute(
            "/system/bin/rm -f '${backup.path}' '${backup.path}.sha256' " +
                "'${backup.path}.meta.json' '${backup.path}.meta.json.tmp'",
            DEFAULT_TIMEOUT_MILLIS,
        )
    }

    suspend fun logs(name: String): String {
        requireValidName(name)
        val result = root.execute(
            "for f in '${ChrootContract.LOG_DIRECTORY}/$name.log' " +
                "'${ChrootContract.LOG_DIRECTORY}/$name-supervisor.log'; do " +
                "[ ! -f \"\${f}\" ] || { /system/bin/printf '===== %s =====\\n' \"\${f##*/}\"; " +
                "/system/bin/tail -n 150 \"\${f}\"; }; done",
            DEFAULT_TIMEOUT_MILLIS,
        )
        return if (result.isSuccess) result.stdout.trim().ifBlank { "暂无日志。" } else result.bestError
    }

    fun parseVersion(raw: String): String? = raw.lineSequence().firstOrNull { it.contains("Root Chroot") }
    fun isCompatibleVersion(version: String?): Boolean = version != null

    private suspend fun helper(args: String, timeoutMillis: Long): CommandResult {
        val ready = bootstrap()
        if (!ready.isSuccess) return ready
        return root.execute("'${ChrootContract.HELPER_PATH}' $args", timeoutMillis)
    }

    private fun backendProbeCommand(): String =
        "[ -x '${ChrootContract.HELPER_PATH}' ] && " +
            "'${ChrootContract.HELPER_PATH}' bootstrap && " +
            "[ -x '${ChrootContract.SPARSE_COPY_PATH}' ] && " +
            "'${ChrootContract.SPARSE_COPY_PATH}' --version && " +
            "[ -x '${ChrootContract.AUTO_START_SCRIPT_PATH}' ] && " +
            "'${ChrootContract.AUTO_START_SCRIPT_PATH}' --version && " +
            "[ -r '${ChrootContract.AUTO_START_CONFIG_PATH}' ]"

    private fun backendIsReady(result: CommandResult): Boolean =
        result.isSuccess &&
            result.stdout.contains("CHROOT_BACKEND_${ChrootContract.VERSION}") &&
            result.stdout.contains(ChrootContract.SPARSE_COPY_VERSION) &&
            result.stdout.contains(ChrootContract.AUTO_START_VERSION)

    private fun autoStartSnapshotShell(): String =
        "script=0; master=0; valid=0; " +
            "if [ -x '${ChrootContract.AUTO_START_SCRIPT_PATH}' ] && " +
            "[ \"\$('${ChrootContract.AUTO_START_SCRIPT_PATH}' --version 2>/dev/null)\" = " +
            "'${ChrootContract.AUTO_START_VERSION}' ]; then script=1; fi; " +
            "header=\$(/system/bin/head -n 1 '${ChrootContract.AUTO_START_CONFIG_PATH}' 2>/dev/null || true); " +
            "case \"\${header}\" in " +
            "'${ChrootContract.AUTO_START_CONFIG_VERSION}|0') valid=1;; " +
            "'${ChrootContract.AUTO_START_CONFIG_VERSION}|1') valid=1; master=1;; esac; " +
            "/system/bin/printf 'CNTERMUX_AUTOSTART|%s|%s\\n' \"\${script}\" \"\${master}\"; " +
            "if [ \"\${valid}\" = 1 ]; then " +
            "/system/bin/tail -n +2 '${ChrootContract.AUTO_START_CONFIG_PATH}' 2>/dev/null | " +
            "while IFS='|' read -r name port extra; do " +
            "case \"\${name}\" in ''|*[!a-zA-Z0-9_-]*|_*|-*) continue;; esac; " +
            "case \"\${port}\" in ''|*[!0-9]*) continue;; esac; " +
            "[ -z \"\${extra}\" ] && [ \"\${port}\" -ge 1024 ] 2>/dev/null && " +
            "[ \"\${port}\" -le 65535 ] 2>/dev/null || continue; " +
            "/system/bin/printf 'CNTERMUX_AUTOSTART_ENTRY|%s|%s\\n' " +
            "\"\${name}\" \"\${port}\"; done; fi; "

    private fun runtimeSnapshotShell(prefix: String = ""): String =
        "for f in '${ChrootContract.IMAGE_DIRECTORY}'/*.img; do " +
            "[ -f \"\${f}\" ] || continue; n=\${f##*/}; name=\${n%.img}; " +
            "case \"\${name}\" in ''|*[!a-zA-Z0-9_-]*|_*|-*) continue;; esac; " +
            "status=\$('${ChrootContract.HELPER_PATH}' status \"\${name}\" 2>/dev/null || true); " +
            "pid=0; ssh=0; ttyd=0; if [ \"\${status}\" = RUNNING ]; then " +
            "pid=\$(/system/bin/head -n 1 '${ChrootContract.RUNTIME_DIRECTORY}'/\"\${name}\"/supervisor.pid 2>/dev/null || true); " +
            "ssh=\$(/system/bin/head -n 1 '${ChrootContract.RUNTIME_DIRECTORY}'/\"\${name}\"/ssh.port 2>/dev/null || true); " +
            "ttyd=\$(/system/bin/head -n 1 '${ChrootContract.RUNTIME_DIRECTORY}'/\"\${name}\"/ttyd.port 2>/dev/null || true); fi; " +
            "/system/bin/printf '${prefix}%s|%s|%s|%s|%s\\n' " +
            "\"\${name}\" \"\${status}\" \"\${pid:-0}\" \"\${ssh:-0}\" \"\${ttyd:-0}\"; done; "

    private fun backupSnapshotShell(prefix: String): String =
        "for f in '${ChrootContract.BACKUP_DIRECTORY}'/*.img.sparse; do " +
            "[ -f \"\${f}\" ] || continue; n=\${f##*/}; " +
            "blocks=\$(/system/bin/du -k \"\${f}\" | /system/bin/cut -f1); " +
            "logical=\$(/system/bin/stat -c %s \"\${f}\"); " +
            "mtime=\$(/system/bin/stat -c %Y \"\${f}\"); " +
            "sum=0; [ ! -s \"\${f}.sha256\" ] || sum=1; meta=''; " +
            "[ ! -s \"\${f}.meta.json\" ] || " +
            "meta=\$(/system/bin/base64 \"\${f}.meta.json\" | /system/bin/tr -d '\\n'); " +
            "/system/bin/printf '${prefix}%s|%s|%s|%s|%s|%s\\n' " +
            "\"\${n}\" \"\${blocks}\" \"\${logical}\" \"\${mtime}\" \"\${sum}\" \"\${meta}\"; done; " +
            "for f in '${ChrootContract.PORTABLE_BACKUP_DIRECTORY}'/*.cnubuntu; do " +
            "[ -f \"\${f}\" ] || continue; n=\${f##*/}; " +
            "bytes=\$(/system/bin/stat -c %s \"\${f}\"); " +
            "blocks=\$(( (bytes + 1023) / 1024 )); " +
            "mtime=\$(/system/bin/stat -c %Y \"\${f}\"); " +
            "info=\$('${ChrootContract.SPARSE_COPY_PATH}' --inspect-archive \"\${f}\" 2>/dev/null) || continue; " +
            "logical=\$(/system/bin/printf '%s\\n' \"\${info}\" | /system/bin/cut -d'|' -f2); " +
            "archive_instance=\$(/system/bin/printf '%s\\n' \"\${info}\" | /system/bin/cut -d'|' -f3); " +
            "file_instance=\${n%%--*}; [ \"\${archive_instance}\" = \"\${file_instance}\" ] || continue; " +
            "display=\$(/system/bin/printf '%s\\n' \"\${info}\" | /system/bin/cut -d'|' -f4); " +
            "/system/bin/printf '${prefix}%s|%s|%s|%s|1|%s\\n' " +
            "\"\${n}\" \"\${blocks}\" \"\${logical}\" \"\${mtime}\" \"\${display}\"; done; "

    private suspend fun executeScript(
        name: String,
        script: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): CommandResult {
        requireValidName(name)
        val encoded = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        return helper("exec $name $encoded", timeoutMillis)
    }

    private suspend fun downloadUbuntuBase(): File = withContext(Dispatchers.IO) {
        val target = File(appContext.cacheDir, "ubuntu-base-${ChrootContract.UBUNTU_VERSION}.tar.gz")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(ChrootContract.UBUNTU_BASE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode in 200..299) { "Ubuntu Base 下载失败：HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == ChrootContract.UBUNTU_BASE_SHA256) { "Ubuntu Base SHA-256 校验失败" }
            target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSafeBackup(backup: BackupEntry) {
        val expectedDirectory = if (backup.portableArchive) {
            require(PORTABLE_BACKUP_REGEX.matches(backup.fileName)) { "便携备份文件名不合法" }
            ChrootContract.PORTABLE_BACKUP_DIRECTORY
        } else {
            require(BACKUP_REGEX.matches(backup.fileName)) { "备份文件名不合法" }
            ChrootContract.BACKUP_DIRECTORY
        }
        require(backup.path == "$expectedDirectory/${backup.fileName}") { "备份路径不安全" }
    }

    private fun requireValidBackupDisplayName(displayName: String): String {
        val normalized = displayName.trim()
        require(normalized.isNotEmpty()) { "请输入备份名称" }
        require(normalized.length <= MAX_BACKUP_DISPLAY_NAME_LENGTH) {
            "备份名称不能超过 $MAX_BACKUP_DISPLAY_NAME_LENGTH 个字符"
        }
        require(normalized.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "备份名称不能包含换行或空字符"
        }
        return normalized
    }

    private fun CommandResult.requireSuccess(): CommandResult {
        check(isSuccess) { bestError }
        return this
    }

    private fun success(stdout: String = ""): CommandResult = CommandResult(stdout = stdout, exitCode = 0)
    private fun failure(message: String): CommandResult = CommandResult(stderr = message, exitCode = 1)

    companion object {
        const val DEFAULT_UBUNTU_IMAGE = "ubuntu-base:24.04"
        const val DEFAULT_ROOT_PASSWORD = ChrootContract.DEFAULT_ROOT_PASSWORD
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val LONG_TIMEOUT_MILLIS = 30 * 60_000L
        private const val CREATE_TIMEOUT_MILLIS = 30 * 60_000L
        private const val MAX_AUTO_START_INSTANCES = 32
        const val MAX_BACKUP_DISPLAY_NAME_LENGTH = 60
        const val DEFAULT_PASSWORD_SET_MARKER = "ROOT_PASSWORD_DEFAULT_SET"
        const val DEFAULT_PASSWORD_UNCHANGED_MARKER = "ROOT_PASSWORD_DEFAULT_UNCHANGED"
        private val NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}${'$'}")
        private val BACKUP_REGEX = Regex("^([A-Za-z0-9][A-Za-z0-9_-]{0,31})_[0-9]{8}_[0-9]{6}\\.img\\.sparse${'$'}")
        private val PORTABLE_BACKUP_REGEX = Regex(
            "^([A-Za-z0-9][A-Za-z0-9_-]{0,31})--([0-9]{8}_[0-9]{6})--" +
                "([\\p{L}\\p{N}._ -]{1,32})\\.cnubuntu${'$'}",
        )

        private fun portableBackupFileName(
            instanceName: String,
            timestamp: String,
            displayName: String,
        ): String {
            requireValidName(instanceName)
            require(Regex("[0-9]{8}_[0-9]{6}").matches(timestamp)) { "备份时间格式不正确" }
            val safeLabel = displayName.map { character ->
                when {
                    character.isLetterOrDigit() -> character
                    character in setOf(' ', '.', '_', '-') -> character
                    else -> '_'
                }
            }.joinToString("")
                .trim(' ', '.', '_', '-')
                .ifBlank { "备份" }
                .take(MAX_PORTABLE_FILE_LABEL_LENGTH)
            return "$instanceName--$timestamp--$safeLabel.cnubuntu"
        }

        internal fun parseRuntimeSnapshot(raw: String): List<ChrootRuntimeRecord> =
            raw.lineSequence().mapNotNull { line ->
                val fields = line.trim().split('|')
                if (fields.size != 5 || !isValidName(fields[0])) return@mapNotNull null
                val running = when (fields[1]) {
                    "RUNNING" -> true
                    "STOPPED" -> false
                    else -> return@mapNotNull null
                }
                ChrootRuntimeRecord(
                    name = fields[0],
                    running = running,
                    supervisorPid = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: 0,
                    sshPort = fields[3].toRuntimePort(),
                    localTerminalPort = fields[4].toRuntimePort(),
                )
            }.distinctBy { it.name }.sortedBy { it.name }.toList()

        internal fun parseBackupSnapshot(lines: Sequence<String>): List<BackupEntry> =
            lines.mapNotNull { line ->
                val parts = line.trim().split('|')
                if (parts.size !in setOf(4, 6)) return@mapNotNull null
                val legacyMatch = BACKUP_REGEX.matchEntire(parts[0])
                val portableMatch = PORTABLE_BACKUP_REGEX.matchEntire(parts[0])
                if (legacyMatch == null && portableMatch == null) return@mapNotNull null
                val portable = portableMatch != null
                val modernFormat = parts.size == 6
                val metadata = when {
                    !modernFormat -> null
                    portable -> decodePortableDisplayName(parts[5])
                    else -> decodeBackupMetadata(parts[5])
                }
                val instanceName = portableMatch?.groupValues?.get(1)
                    ?: legacyMatch!!.groupValues[1]
                BackupEntry(
                    fileName = parts[0],
                    path = "${if (portable) {
                        ChrootContract.PORTABLE_BACKUP_DIRECTORY
                    } else {
                        ChrootContract.BACKUP_DIRECTORY
                    }}/${parts[0]}",
                    instanceName = instanceName,
                    sizeBytes = (parts[1].toLongOrNull() ?: 0) * 1024,
                    modifiedEpochSeconds = parts[if (modernFormat) 3 else 2].toLongOrNull() ?: 0,
                    checksumPresent = parts[if (modernFormat) 4 else 3] == "1",
                    displayName = metadata
                        ?: portableMatch?.groupValues?.get(3)
                        ?: instanceName,
                    logicalSizeBytes = if (modernFormat) {
                        parts[2].toLongOrNull() ?: 0
                    } else {
                        0
                    },
                    metadataPresent = portable || metadata != null,
                    portableArchive = portable,
                )
            }.sortedByDescending { it.modifiedEpochSeconds }.toList()

        private fun decodeBackupMetadata(encodedMetadata: String): String? = runCatching {
            if (encodedMetadata.isBlank()) return@runCatching null
            val json = String(
                java.util.Base64.getDecoder().decode(encodedMetadata),
                Charsets.UTF_8,
            )
            val encodedDisplayName = DISPLAY_NAME_JSON_REGEX.find(json)
                ?.groupValues?.get(1) ?: return@runCatching null
            String(
                java.util.Base64.getUrlDecoder().decode(encodedDisplayName),
                Charsets.UTF_8,
            ).trim().takeIf {
                it.isNotEmpty() && it.length <= MAX_BACKUP_DISPLAY_NAME_LENGTH
            }
        }.getOrNull()

        private fun decodePortableDisplayName(encodedDisplayName: String): String? = runCatching {
            if (encodedDisplayName.isBlank()) return@runCatching null
            String(
                java.util.Base64.getUrlDecoder().decode(encodedDisplayName),
                Charsets.UTF_8,
            ).trim().takeIf {
                it.isNotEmpty() && it.length <= MAX_BACKUP_DISPLAY_NAME_LENGTH &&
                    it.none { character ->
                        character == '\n' || character == '\r' || character == '\u0000'
                    }
            }
        }.getOrNull()

        private val DISPLAY_NAME_JSON_REGEX =
            Regex("\\\"displayNameBase64\\\":\\\"([A-Za-z0-9_-]+)\\\"")

        private const val MAX_PORTABLE_FILE_LABEL_LENGTH = 32

        internal fun parseAutoStartSnapshot(
            lines: Sequence<String>,
        ): ChrootAutoStartConfiguration {
            val rows = lines.map(String::trim).toList()
            val status = rows.firstNotNullOfOrNull { row ->
                val fields = row.removePrefixOrNull("CNTERMUX_AUTOSTART|")
                    ?.split('|') ?: return@firstNotNullOfOrNull null
                if (fields.size != 2) return@firstNotNullOfOrNull null
                (fields[0] == "1") to (fields[1] == "1")
            } ?: return ChrootAutoStartConfiguration()
            val entries = rows.mapNotNull { row ->
                val fields = row.removePrefixOrNull("CNTERMUX_AUTOSTART_ENTRY|")
                    ?.split('|') ?: return@mapNotNull null
                if (fields.size != 2 || !isValidName(fields[0])) return@mapNotNull null
                val port = fields[1].toIntOrNull()?.takeIf { it in 1024..65535 }
                    ?: return@mapNotNull null
                fields[0] to port
            }.distinctBy { it.first }.toMap()
            return ChrootAutoStartConfiguration(
                scriptReady = status.first,
                enabled = status.second,
                instances = entries,
            )
        }

        internal fun encodeAutoStartConfiguration(
            configuration: ChrootAutoStartConfiguration,
        ): String = buildString {
            append(ChrootContract.AUTO_START_CONFIG_VERSION)
            append('|')
            append(if (configuration.enabled) '1' else '0')
            append('\n')
            configuration.instances.toSortedMap().forEach { (name, port) ->
                requireValidName(name)
                requireValidPort(port)
                append(name).append('|').append(port).append('\n')
            }
        }

        private fun String.toRuntimePort(): Int? =
            toIntOrNull()?.takeIf { it in 1024..65535 }

        private fun String.removePrefixOrNull(prefix: String): String? =
            takeIf { it.startsWith(prefix) }?.removePrefix(prefix)

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
        fun requireValidName(name: String) = require(isValidName(name)) {
            "实例名称只能包含字母、数字、下划线和连字符，长度 1～32 位"
        }
        fun requireValidPort(port: Int) = require(port in 1024..65535) { "端口必须在 1024～65535 之间" }
        fun rootPasswordValidationError(password: String): String? = when {
            password.length < 6 -> "密码至少需要 6 个字符"
            password.length > 128 -> "密码不能超过 128 个字符"
            password.any { it == ':' || it == '\n' || it == '\r' || it == '\u0000' } ->
                "密码不能包含冒号、换行符或空字符"
            else -> null
        }
        fun requireValidRootPassword(password: String) {
            require(rootPasswordValidationError(password) == null) {
                rootPasswordValidationError(password) ?: "密码格式不正确"
            }
        }
        fun defaultPasswordWasSet(result: CommandResult): Boolean =
            result.stdout.lineSequence().any { it.trim() == DEFAULT_PASSWORD_SET_MARKER }
    }
}
