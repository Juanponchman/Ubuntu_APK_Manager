package cn.termux.ubuntumanager.proot

import cn.termux.ubuntumanager.model.BackupEntry
import cn.termux.ubuntumanager.model.CommandResult
import cn.termux.ubuntumanager.model.LocalSessionBackend
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.model.ProotSession
import cn.termux.ubuntumanager.model.StorageProbe
import cn.termux.ubuntumanager.termux.TermuxCommandClient
import cn.termux.ubuntumanager.termux.TermuxContract
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

class ProotDistroClient(
    private val termux: TermuxCommandClient,
) {
    suspend fun version(
        timeoutMillis: Long = 20_000,
    ): CommandResult {
        val script = """
            version="$(${TermuxContract.PREFIX}/bin/dpkg-query -W -f='${'$'}{Version}' proot-distro 2>/dev/null || true)"
            if [ -z "${'$'}version" ] && [ -x ${TermuxContract.PREFIX}/bin/python ]; then
              version="$(${TermuxContract.PREFIX}/bin/python -c 'import importlib.metadata; print(importlib.metadata.version("proot-distro"))' 2>/dev/null || true)"
            fi
            printf '%s\n' "${'$'}version"
            ${TermuxContract.PROOT_DISTRO} ps --help >/dev/null 2>&1 &&
              ${TermuxContract.PROOT_DISTRO} kill --help >/dev/null 2>&1 &&
              ${TermuxContract.PROOT_DISTRO} login --help 2>&1 | grep -q -- '--detach'
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf("-lc", script),
            label = "检查 PRoot Distro 版本",
            timeoutMillis = timeoutMillis,
        )
    }

    suspend fun storageProbe(
        timeoutMillis: Long = 20_000,
    ): StorageProbe {
        val script = """
            storage_directory="${'$'}HOME/storage"
            directory="${'$'}HOME/storage/downloads"
            directory_present=0
            links=0
            writable=0
            if [ -d "${'$'}storage_directory" ]; then
              directory_present=1
            fi
            if [ -L "${'$'}directory" ] && [ -d "${'$'}directory" ]; then
              links=1
              test_file="${'$'}directory/.ubuntu-manager-write-test-${'$'}${'$'}"
              if : > "${'$'}test_file" 2>/dev/null; then
                rm -f "${'$'}test_file"
                writable=1
              fi
            fi
            printf 'directory=%s\nlinks=%s\nwritable=%s\n' \
              "${'$'}directory_present" "${'$'}links" "${'$'}writable"
        """.trimIndent()
        val result = termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf("-lc", script),
            label = "检查备份目录",
            timeoutMillis = timeoutMillis,
        )
        if (!result.isSuccess) return StorageProbe(false, false, false)
        val values = result.stdout.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        return StorageProbe(
            directoryPresent = values["directory"] == "1",
            linksPresent = values["links"] == "1",
            writeTestPassed = values["writable"] == "1",
        )
    }

    suspend fun storageReady(): Boolean = storageProbe().writeTestPassed

    suspend fun acquireWakeLock(): CommandResult = termux.execute(
        commandPath = TermuxContract.WAKE_LOCK,
        label = "启用 Termux 后台保护",
        description = "有运行中的 Ubuntu 时保持 Termux 前台服务和 CPU 唤醒",
        timeoutMillis = 10_000,
    )

    suspend fun releaseWakeLock(): CommandResult = termux.execute(
        commandPath = TermuxContract.WAKE_UNLOCK,
        label = "释放 Termux 后台保护",
        timeoutMillis = 10_000,
    )

    suspend fun prepareBackupDirectory(): CommandResult {
        return termux.execute(
            commandPath = TermuxContract.MKDIR,
            arguments = listOf("-p", BACKUP_DIRECTORY),
            label = "准备备份目录",
            description = "以普通 Termux 用户创建 Download/UbuntuManager",
            timeoutMillis = 20_000,
        )
    }

    suspend fun listContainers(
        timeoutMillis: Long = TermuxCommandClient.DEFAULT_TIMEOUT_MILLIS,
    ): List<String> {
        val result = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf("list", "--quiet"),
            label = "读取 Ubuntu 实例",
            timeoutMillis = timeoutMillis,
        ).requireSuccess()
        return result.stdout.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && isValidName(it) }
            .distinct()
            .sorted()
            .toList()
    }

    suspend fun listSessions(
        timeoutMillis: Long = 20_000,
    ): List<ProotSession> {
        val structured = termux.execute(
            commandPath = TermuxContract.PYTHON,
            arguments = listOf("-c", SESSION_QUERY_SCRIPT),
            label = "读取 PRoot 会话",
            timeoutMillis = timeoutMillis,
        )
        if (structured.isSuccess) {
            val parsed = parseStructuredSessions(structured.stdout)
            if (structured.stdout.isBlank() || parsed.isNotEmpty()) {
                return parsed
            }
        }

        val fallback = termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf(
                "-lc",
                "export PD_FORCE_NO_COLORS=true\nexec ${TermuxContract.PROOT_DISTRO} ps",
            ),
            label = "兼容检查 PRoot 会话",
            timeoutMillis = timeoutMillis,
        ).requireSuccess()
        return parseSessionTable(fallback.stdout + "\n" + fallback.stderr)
    }

    suspend fun isPortOpen(
        port: Int,
        timeoutMillis: Long = 10_000,
    ): Boolean {
        requireValidPort(port)
        val result = termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf(
                "-lc",
                "timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/$port' >/dev/null 2>&1",
            ),
            label = "检查 SSH 端口",
            timeoutMillis = timeoutMillis,
        )
        return result.isSuccess
    }

    suspend fun ensureLocalTerminal(name: String): CommandResult {
        requireValidName(name)
        val check = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                "command -v ttyd >/dev/null 2>&1 && " +
                    "command -v tmux >/dev/null 2>&1 && " +
                    "command -v dtach >/dev/null 2>&1 && " +
                    "command -v script >/dev/null 2>&1",
            ),
            label = "检查 $name 的本地会话组件",
        )
        if (check.isSuccess) return check

        val installScript = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y ttyd tmux dtach util-linux
            command -v ttyd >/dev/null 2>&1
            command -v tmux >/dev/null 2>&1
            command -v dtach >/dev/null 2>&1
            command -v script >/dev/null 2>&1
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                installScript,
            ),
            label = "安装 $name 的本地会话组件",
            description = "首次使用需要从 Ubuntu 软件源安装 ttyd、tmux 和 dtach",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun activeContainerMaintenance(name: String): String? {
        requireValidName(name)
        val script = """
            import os
            import sys

            name = sys.argv[1]
            operations = {"backup", "restore", "remove", "rename", "install"}
            for entry in os.scandir("/proc"):
                if not entry.name.isdigit():
                    continue
                try:
                    with open(entry.path + "/cmdline", "rb") as stream:
                        args = [
                            part.decode("utf-8", "replace")
                            for part in stream.read().split(b"\0")
                            if part
                        ]
                except (FileNotFoundError, PermissionError, ProcessLookupError):
                    continue
                for index, argument in enumerate(args[:-1]):
                    if not argument.endswith("/proot-distro"):
                        continue
                    operation = args[index + 1]
                    if operation in operations and name in args[index + 2:]:
                        print(operation)
                        raise SystemExit(0)
            raise SystemExit(1)
        """.trimIndent()
        val result = termux.execute(
            commandPath = TermuxContract.PYTHON,
            arguments = listOf("-c", script, name),
            label = "检查 $name 的维护任务",
            timeoutMillis = 20_000,
        )
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotEmpty() }
    }

    suspend fun existingLocalTerminalPort(name: String): Int? {
        requireValidName(name)
        val script = """
            set -u
            session_dir=/run/ubuntu-manager
            for pid_file in "${'$'}session_dir"/local-session-*.pid; do
              [ -f "${'$'}pid_file" ] || continue
              pid="$(cat "${'$'}pid_file" 2>/dev/null || true)"
              case "${'$'}pid" in
                ''|*[!0-9]*) continue ;;
              esac
              [ -r "/proc/${'$'}pid/cmdline" ] || continue
              command_line="$(tr '\000' ' ' < "/proc/${'$'}pid/cmdline")"
              case "${'$'}command_line" in
                *ttyd*) ;;
                *) continue ;;
              esac
              file_name="${'$'}{pid_file##*/}"
              port="${'$'}{file_name#local-session-}"
              port="${'$'}{port%.pid}"
              case "${'$'}port" in
                ''|*[!0-9]*) continue ;;
              esac
              printf '%s\n' "${'$'}port"
              exit 0
            done
        """.trimIndent()
        val result = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "查找 $name 的后台会话",
            timeoutMillis = 20_000,
        )
        return result.stdout.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
    }

    suspend fun localTerminalBackend(name: String): LocalSessionBackend {
        requireValidName(name)
        val script = """
            session_dir=/run/ubuntu-manager
            tmux_socket="${'$'}session_dir/tmux.sock"
            if command -v tmux >/dev/null 2>&1 &&
               tmux -S "${'$'}tmux_socket" has-session -t ubuntu-manager 2>/dev/null; then
              printf 'tmux\n'
            elif [ -S "${'$'}session_dir/session.sock" ]; then
              printf 'dtach\n'
            else
              printf 'unknown\n'
            fi
        """.trimIndent()
        val result = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "识别 $name 的本地会话后端",
            timeoutMillis = 20_000,
        )
        if (!result.isSuccess) return LocalSessionBackend.UNKNOWN
        return when (result.stdout.trim()) {
            "tmux" -> LocalSessionBackend.TMUX
            "dtach" -> LocalSessionBackend.DTACH
            else -> LocalSessionBackend.UNKNOWN
        }
    }

    suspend fun hardenExistingLocalTerminal(name: String): CommandResult {
        requireValidName(name)
        val script = """
            set -eu
            session_dir=/run/ubuntu-manager
            attach_script="${'$'}session_dir/attach-session.sh"
            if [ -f "${'$'}attach_script" ] &&
               grep -q '/usr/bin/dtach' "${'$'}attach_script"; then
              cat >"${'$'}attach_script" <<'EOF'
            #!/bin/bash
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            exec /usr/bin/dtach -a /run/ubuntu-manager/session.sock -r winch
            EOF
              chmod 700 "${'$'}attach_script"
            fi
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "升级 $name 的旧本地会话",
            description = "保留后台程序并移除不安全的原始终端记录回放",
            timeoutMillis = 20_000,
        )
    }

    suspend fun startLocalTerminal(name: String, port: Int): CommandResult {
        requireValidName(name)
        requireValidPort(port)
        val script = """
            set -eu
            UBUNTU_MANAGER_PERSISTENT_SESSION=1
            export UBUNTU_MANAGER_PERSISTENT_SESSION
            ttyd_path="$(command -v ttyd || true)"
            tmux_path="$(command -v tmux || true)"
            dtach_path="$(command -v dtach || true)"
            if [ -z "${'$'}ttyd_path" ] || [ -z "${'$'}tmux_path" ] ||
               [ -z "${'$'}dtach_path" ]; then
              echo '没有安装 ttyd、tmux 或 dtach。' >&2
              exit 127
            fi
            session_dir=/run/ubuntu-manager
            dtach_socket="${'$'}session_dir/session.sock"
            tmux_socket="${'$'}session_dir/tmux.sock"
            tmux_name=ubuntu-manager
            attach_script="${'$'}session_dir/attach-session.sh"
            pid_file="${'$'}session_dir/local-session-$port.pid"
            log_file=/var/log/ubuntu-manager/local-session.log
            mkdir -p "${'$'}session_dir" /var/log/ubuntu-manager
            export HOME=/root
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            cd /root

            if [ -S "${'$'}dtach_socket" ]; then
              backend=dtach
              cat >"${'$'}attach_script" <<'EOF'
            #!/bin/bash
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            exec /usr/bin/dtach -a /run/ubuntu-manager/session.sock -r winch
            EOF
            else
              backend=tmux
              if ! "${'$'}tmux_path" -S "${'$'}tmux_socket" \
                   has-session -t "${'$'}tmux_name" 2>/dev/null; then
                "${'$'}tmux_path" -S "${'$'}tmux_socket" new-session -d \
                  -s "${'$'}tmux_name" -c /root /bin/bash -l
                "${'$'}tmux_path" -S "${'$'}tmux_socket" set-option -g \
                  history-limit 20000
              fi
              cat >"${'$'}attach_script" <<'EOF'
            #!/bin/bash
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            tmux_socket=/run/ubuntu-manager/tmux.sock
            tmux_name=ubuntu-manager
            /usr/bin/tmux -S "${'$'}tmux_socket" capture-pane -p -J \
              -S -20000 -t "${'$'}tmux_name" 2>/dev/null || true
            exec /usr/bin/tmux -S "${'$'}tmux_socket" attach-session \
              -t "${'$'}tmux_name"
            EOF
            fi
            chmod 700 "${'$'}attach_script"

            "${'$'}ttyd_path" \
              --interface 127.0.0.1 \
              --port $port \
              --writable \
              --max-clients 1 \
              --check-origin \
              --terminal-type xterm-256color \
              --client-option rendererType=canvas \
              --client-option scrollback=20000 \
              "${'$'}attach_script" >>"${'$'}log_file" 2>&1 &
            ttyd_pid="${'$'}!"
            printf '%s\n' "${'$'}ttyd_pid" > "${'$'}pid_file"
            (
              if [ "${'$'}backend" = dtach ]; then
                while [ -S "${'$'}dtach_socket" ]; do sleep 1; done
              else
                while "${'$'}tmux_path" -S "${'$'}tmux_socket" \
                    has-session -t "${'$'}tmux_name" 2>/dev/null; do
                  sleep 1
                done
              fi
              kill "${'$'}ttyd_pid" 2>/dev/null || true
            ) &
            session_watcher_pid="${'$'}!"
            wait "${'$'}ttyd_pid" || true
            kill "${'$'}session_watcher_pid" 2>/dev/null || true
            rm -f "${'$'}pid_file"
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                "--detach",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "打开 $name 的本地会话",
            description = "连接 127.0.0.1:$port，命令会话由 dtach 在后台保持",
        )
    }

    suspend fun stopLocalTerminal(name: String, port: Int): CommandResult {
        requireValidName(name)
        requireValidPort(port)
        val script = """
            set -u
            pid_file=/run/ubuntu-manager/local-session-$port.pid
            if [ ! -f "${'$'}pid_file" ]; then
              exit 0
            fi
            pid="$(cat "${'$'}pid_file" 2>/dev/null || true)"
            case "${'$'}pid" in
              ''|*[!0-9]*) rm -f "${'$'}pid_file"; exit 0 ;;
            esac
            if [ -r "/proc/${'$'}pid/cmdline" ]; then
              command_line="$(tr '\000' ' ' < "/proc/${'$'}pid/cmdline")"
              case "${'$'}command_line" in
                *ttyd*"$port"*)
                  kill "${'$'}pid" 2>/dev/null || true
                  ;;
              esac
            fi
            rm -f "${'$'}pid_file"
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "关闭 $name 的本地会话",
            timeoutMillis = 20_000,
        )
    }

    suspend fun endPersistentLocalSession(name: String): CommandResult {
        requireValidName(name)
        val script = """
            set -u
            session_dir=/run/ubuntu-manager
            dtach_socket="${'$'}session_dir/session.sock"
            tmux_socket="${'$'}session_dir/tmux.sock"
            for pid_file in "${'$'}session_dir"/local-session-*.pid; do
              [ -f "${'$'}pid_file" ] || continue
              pid="$(cat "${'$'}pid_file" 2>/dev/null || true)"
              case "${'$'}pid" in
                ''|*[!0-9]*) ;;
                *) kill "${'$'}pid" 2>/dev/null || true ;;
              esac
              rm -f "${'$'}pid_file"
            done
            for process in /proc/[0-9]*; do
              [ -r "${'$'}process/comm" ] || continue
              [ "$(cat "${'$'}process/comm" 2>/dev/null)" = dtach ] || continue
              command_line="$(tr '\000' ' ' < "${'$'}process/cmdline" 2>/dev/null || true)"
              case "${'$'}command_line" in
                *"${'$'}dtach_socket"*) ;;
                *) continue ;;
              esac
              pid="${'$'}{process##*/}"
              kill "${'$'}pid" 2>/dev/null || true
            done
            if command -v tmux >/dev/null 2>&1; then
              tmux -S "${'$'}tmux_socket" kill-server 2>/dev/null || true
            fi
            rm -f "${'$'}dtach_socket" "${'$'}tmux_socket"
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "结束 $name 的后台本地会话",
            timeoutMillis = 20_000,
        )
    }

    suspend fun localTerminalLogs(name: String): String {
        requireValidName(name)
        val result = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                "tail -n 40 /var/log/ubuntu-manager/local-session.log 2>/dev/null || true",
            ),
            label = "读取 $name 的本地会话日志",
            timeoutMillis = 20_000,
        )
        return if (result.isSuccess) result.stdout.trim() else result.bestError
    }

    suspend fun startSsh(name: String, port: Int): CommandResult {
        requireValidName(name)
        requireValidPort(port)

        val preflight = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                "test -x /usr/sbin/sshd || { echo '没有安装 openssh-server，请先初始化 SSH。' >&2; exit 127; }",
            ),
            label = "检查 $name 的 SSH",
        )
        if (!preflight.isSuccess) return preflight

        val script = """
            set -eu
            mkdir -p /run/sshd /var/log/ubuntu-manager
            if command -v ssh-keygen >/dev/null 2>&1; then
              ssh-keygen -A
            fi
            exec /usr/sbin/sshd -D -e -p $port >>/var/log/ubuntu-manager/sshd.log 2>&1
        """.trimIndent()

        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                "--detach",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "启动 $name",
            description = "后台启动 Ubuntu 中的 SSH 服务",
        )
    }

    suspend fun stop(name: String, force: Boolean = false): CommandResult {
        requireValidName(name)
        val args = buildList {
            add("kill")
            if (force) {
                add("--signal")
                add("KILL")
            }
            add(name)
        }
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = args,
            label = if (force) "强制停止 $name" else "停止 $name",
        )
    }

    suspend fun create(
        name: String,
        image: String = DEFAULT_UBUNTU_IMAGE,
    ): CommandResult {
        requireValidName(name)
        require(image in SUPPORTED_IMAGES) { "不支持的 Ubuntu 镜像" }
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf("install", "--name", name, image),
            label = "创建 $name",
            description = "从官方 Ubuntu 镜像创建新的 PRoot 实例",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun initializeSsh(name: String): CommandResult {
        requireValidName(name)
        val script = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y openssh-server
            mkdir -p /run/sshd /var/log/ubuntu-manager
            ssh-keygen -A
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "初始化 $name 的 SSH",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun rename(oldName: String, newName: String): CommandResult {
        requireValidName(oldName)
        requireValidName(newName)
        require(oldName != newName) { "新旧实例名称不能相同" }
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf("rename", oldName, newName),
            label = "重命名 $oldName",
            description = "将实例重命名为 $newName",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun executeCommand(
        name: String,
        command: UserCommand,
    ): CommandResult {
        requireValidName(name)
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                command.script,
            ),
            label = "${command.title} · $name",
            description = "执行用户自定义指令",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun cloneInstance(source: String, target: String): CommandResult {
        requireValidName(source)
        requireValidName(target)
        require(source != target) { "复制目标名称不能与源实例相同" }
        val script = """
            set -euo pipefail
            export PD_FORCE_NO_COLORS=true
            ${TermuxContract.PROOT_DISTRO} backup --compress none ${shellQuote(source)} |
              ${TermuxContract.PYTHON} -c ${shellQuote(CLONE_TRANSFORM_SCRIPT)} ${shellQuote(source)} ${shellQuote(target)} |
              ${TermuxContract.PROOT_DISTRO} restore
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf("-lc", script),
            label = "复制 $source",
            description = "创建新实例 $target",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun initializeCloneIdentity(name: String): CommandResult {
        requireValidName(name)
        val script = """
            set -eu
            if command -v ssh-keygen >/dev/null 2>&1; then
              rm -f /etc/ssh/ssh_host_*
              ssh-keygen -A
            fi
            if [ -e /etc/machine-id ] || [ -L /etc/machine-id ]; then
              : > /etc/machine-id
            fi
            printf '%s\n' ${shellQuote(name)} > /etc/hostname
        """.trimIndent()
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "初始化 $name 的独立身份",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun remove(name: String): CommandResult {
        requireValidName(name)
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf("remove", name),
            label = "删除 $name",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun backup(name: String): String {
        requireValidName(name)
        val directory = BACKUP_DIRECTORY
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val path = "$directory/${name}_$timestamp.tar.xz"
        val script = AtomicBackupScript.build(name, directory, path)
        termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf("-lc", script),
            label = "备份 $name",
            description = "先写入临时文件，校验完成后再原子发布到备份列表",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        ).requireSuccess()
        return path
    }

    suspend fun listBackups(): List<BackupEntry> {
        val script = """
            dir=${shellQuote(BACKUP_DIRECTORY)}
            [ -d "${'$'}dir" ] || exit 0
            find "${'$'}dir" -maxdepth 1 -type f -name '*.tar.xz' -printf '%f|%s|%T@|%p\n'
        """.trimIndent()
        val result = termux.execute(
            commandPath = TermuxContract.BASH,
            arguments = listOf("-lc", script),
            label = "读取备份列表",
        ).requireSuccess()

        val parsed = result.stdout.lineSequence().mapNotNull { line ->
            val columns = line.split('|', limit = 4)
            if (columns.size != 4) return@mapNotNull null
            val match = BACKUP_FILE_REGEX.matchEntire(columns[0]) ?: return@mapNotNull null
            val path = columns[3]
            BackupEntry(
                fileName = columns[0],
                path = path,
                instanceName = match.groupValues[1],
                sizeBytes = columns[1].toLongOrNull() ?: 0,
                modifiedEpochSeconds =
                    columns[2].substringBefore('.').toLongOrNull() ?: 0,
                checksumPresent = false,
            )
        }.toList()

        val withChecksums = mutableListOf<BackupEntry>()
        for (entry in parsed) {
            val checksumExists = termux.execute(
                commandPath = TermuxContract.BASH,
                arguments = listOf("-lc", "test -f ${shellQuote("${entry.path}.sha256")}"),
                label = "检查备份校验文件",
                timeoutMillis = 10_000,
            ).isSuccess
            withChecksums += entry.copy(checksumPresent = checksumExists)
        }
        return withChecksums.sortedByDescending { it.modifiedEpochSeconds }
    }

    suspend fun validateBackup(backup: BackupEntry): String {
        requireSafeBackupPath(backup.path)
        if (backup.checksumPresent) {
            termux.execute(
                commandPath = TermuxContract.SHA256SUM,
                arguments = listOf("-c", "${backup.path}.sha256"),
                label = "验证备份完整性",
                timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
            ).requireSuccess()
        }

        val listing = termux.execute(
            commandPath = TermuxContract.TAR,
            arguments = listOf("-tf", backup.path),
            label = "检查备份内容",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        ).requireSuccess().stdout

        val containerNames = listing.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.removePrefix("./").substringBefore('/') }
            .filter(::isValidName)
            .toSet()

        require(containerNames.size == 1) { "备份中没有唯一的实例目录" }
        return containerNames.single()
    }

    suspend fun restore(backup: BackupEntry): CommandResult {
        requireSafeBackupPath(backup.path)
        return termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf("restore", backup.path),
            label = "恢复 ${backup.instanceName}",
            description = "从校验过的备份恢复 Ubuntu 实例",
            timeoutMillis = TermuxCommandClient.LONG_TIMEOUT_MILLIS,
        )
    }

    suspend fun deleteBackup(backup: BackupEntry): CommandResult {
        requireSafeBackupPath(backup.path)
        return termux.execute(
            commandPath = TermuxContract.RM,
            arguments = listOf("-f", "--", backup.path, "${backup.path}.sha256"),
            label = "删除 ${backup.fileName}",
            description = "删除备份压缩包及其 SHA-256 校验文件",
        )
    }

    suspend fun logs(name: String): String {
        requireValidName(name)
        val script = """
            file=/var/log/ubuntu-manager/sshd.log
            if [ -f "${'$'}file" ]; then
              tail -n 200 "${'$'}file"
            else
              echo "暂无 SSH 日志。"
            fi
        """.trimIndent()
        val result = termux.execute(
            commandPath = TermuxContract.PROOT_DISTRO,
            arguments = listOf(
                "login",
                name,
                "--",
                "/bin/bash",
                "-lc",
                script,
            ),
            label = "读取 $name 日志",
        )
        return if (result.isSuccess) result.stdout.trimEnd() else result.bestError
    }

    fun parseVersion(raw: String): String? =
        VERSION_REGEX.find(raw)?.value

    fun isCompatibleVersion(version: String?): Boolean =
        version?.substringBefore('.')?.toIntOrNull()?.let { it >= 5 } == true

    private fun CommandResult.requireSuccess(): CommandResult {
        check(isSuccess) { bestError }
        return this
    }

    private fun requireSafeBackupPath(path: String) {
        require(path.startsWith("$BACKUP_DIRECTORY/")) { "备份文件不在受信任目录中" }
        require(path.endsWith(".tar.xz")) { "不支持的备份格式" }
        require(!path.contains("/../")) { "备份路径不安全" }
        val fileName = path.substringAfterLast('/')
        require(path == "$BACKUP_DIRECTORY/$fileName") { "备份路径不安全" }
        require(BACKUP_FILE_REGEX.matches(fileName)) { "备份文件名不合法" }
    }

    companion object {
        const val DEFAULT_UBUNTU_IMAGE = "ubuntu:24.04"
        val SUPPORTED_IMAGES = setOf(DEFAULT_UBUNTU_IMAGE)
        const val BACKUP_DIRECTORY =
            "${TermuxContract.HOME}/storage/downloads/UbuntuManager"

        private val NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")
        private val VERSION_REGEX = Regex("\\d+\\.\\d+(?:\\.\\d+)?")
        private val BACKUP_FILE_REGEX =
            Regex("(.+)_\\d{8}_\\d{6}\\.tar\\.xz")
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;\\d]*m")
        private val SESSION_TABLE_REGEX =
            Regex("""^(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)(?:\s+(.*))?$""")

        private val SESSION_QUERY_SCRIPT = """
            import base64
            import shlex
            import time
            from proot_distro.session import active_sessions

            def enc(value):
                raw = str(value or "").encode("utf-8")
                return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

            def uptime(seconds):
                seconds = max(0, int(seconds))
                if seconds < 3600:
                    return f"{seconds // 60}m{seconds % 60:02d}s"
                if seconds < 86400:
                    return f"{seconds // 3600}h{(seconds % 3600) // 60:02d}m"
                return f"{seconds // 86400}d{(seconds % 86400) // 3600:02d}h"

            now = time.time()
            for session in active_sessions():
                command = session.get("command", "")
                if isinstance(command, (list, tuple)):
                    command = shlex.join([str(part) for part in command])
                kind = str(session.get("kind", ""))
                if session.get("detach"):
                    kind += "*"
                print("\t".join([
                    str(session.get("pid", "")),
                    enc(session.get("container", "")),
                    enc(kind),
                    enc(session.get("user", "")),
                    enc(uptime(now - session.get("start_time", now))),
                    enc(command),
                ]))
        """.trimIndent()

        private val CLONE_TRANSFORM_SCRIPT = """
            import sys
            import tarfile

            source, target = sys.argv[1], sys.argv[2]
            source_prefix = source + "/"

            def renamed(value):
                if value == source:
                    return target
                if value.startswith(source_prefix):
                    return target + "/" + value[len(source_prefix):]
                return value

            with tarfile.open(fileobj=sys.stdin.buffer, mode="r|*") as incoming:
                with tarfile.open(fileobj=sys.stdout.buffer, mode="w|") as outgoing:
                    for member in incoming:
                        old_name = member.name.lstrip("./")
                        if old_name != source and not old_name.startswith(source_prefix):
                            raise RuntimeError("备份中出现非源实例路径")
                        member.name = renamed(old_name)
                        if member.linkname:
                            member.linkname = renamed(member.linkname)
                        payload = incoming.extractfile(member) if member.isfile() else None
                        outgoing.addfile(member, payload)
        """.trimIndent()

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)

        fun requireValidName(name: String) {
            require(isValidName(name)) {
                "实例名必须以字母或数字开头，只能包含字母、数字、点、下划线和短横线，最长 32 位"
            }
        }

        fun requireValidPort(port: Int) {
            require(port in 1024..65535) { "SSH 端口必须在 1024～65535 之间" }
        }

        internal fun parseStructuredSessions(raw: String): List<ProotSession> =
            raw.lineSequence().mapNotNull { line ->
                val columns = line.split('\t', limit = 6)
                if (columns.size != 6) return@mapNotNull null
                val pid = columns[0].toIntOrNull() ?: return@mapNotNull null
                val container = decodeSessionField(columns[1]) ?: return@mapNotNull null
                if (!isValidName(container)) return@mapNotNull null
                ProotSession(
                    pid = pid,
                    container = container,
                    type = decodeSessionField(columns[2]).orEmpty(),
                    user = decodeSessionField(columns[3]).orEmpty(),
                    uptime = decodeSessionField(columns[4]).orEmpty(),
                    command = decodeSessionField(columns[5]).orEmpty(),
                )
            }.toList()

        internal fun parseSessionTable(raw: String): List<ProotSession> =
            raw.lineSequence().mapNotNull { original ->
                val line = ANSI_ESCAPE_REGEX.replace(original, "").trim()
                val match = SESSION_TABLE_REGEX.matchEntire(line) ?: return@mapNotNull null
                val pid = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val container = match.groupValues[2]
                if (!isValidName(container)) return@mapNotNull null
                ProotSession(
                    pid = pid,
                    container = container,
                    type = match.groupValues[3],
                    user = match.groupValues[4],
                    uptime = match.groupValues[5],
                    command = match.groupValues.getOrElse(6) { "" },
                )
            }.toList()

        private fun decodeSessionField(value: String): String? = runCatching {
            val padding = "=".repeat((4 - value.length % 4) % 4)
            String(
                Base64.getUrlDecoder().decode(value + padding),
                StandardCharsets.UTF_8,
            )
        }.getOrNull()

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

internal object AtomicBackupScript {
    fun build(name: String, directory: String, path: String): String {
        val partialPath = "$path.partial"
        val checksumPath = "$path.sha256"
        val partialChecksumPath = "$checksumPath.partial"
        return """
            set -euo pipefail
            directory=${shellQuote(directory)}
            archive=${shellQuote(path)}
            partial=${shellQuote(partialPath)}
            checksum=${shellQuote(checksumPath)}
            checksum_partial=${shellQuote(partialChecksumPath)}
            cleanup() {
              ${TermuxContract.RM} -f -- "${'$'}partial" "${'$'}checksum_partial"
            }
            trap cleanup EXIT INT TERM HUP
            ${TermuxContract.MKDIR} -p "${'$'}directory"
            ${TermuxContract.PROOT_DISTRO} backup --compress xz \
              ${shellQuote(name)} --output "${'$'}partial"
            hash="$(${TermuxContract.SHA256SUM} "${'$'}partial" | \
              ${TermuxContract.PREFIX}/bin/cut -d ' ' -f 1)"
            ${TermuxContract.PREFIX}/bin/printf '%s  %s\n' \
              "${'$'}hash" "${'$'}archive" > "${'$'}checksum_partial"
            ${TermuxContract.PREFIX}/bin/mv -f -- \
              "${'$'}checksum_partial" "${'$'}checksum"
            ${TermuxContract.PREFIX}/bin/mv -f -- "${'$'}partial" "${'$'}archive"
            trap - EXIT INT TERM HUP
            ${TermuxContract.PREFIX}/bin/printf '%s\n' "${'$'}archive"
        """.trimIndent()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
