package cn.termux.ubuntumanager.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import cn.termux.ubuntumanager.termux.TermuxContract
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uses the installed Magisk Alpha build only for the one-time permission/bootstrap operation.
 *
 * Ubuntu and PRoot operations never run through this class.
 */
class RootPermissionSetup(context: Context) {
    private val appContext = context.applicationContext

    suspend fun configure(): String = withContext(Dispatchers.IO) {
        val alphaVersion = requireSupportedAlphaManager()
        requireSupportedAlphaSu()

        @Suppress("DEPRECATION")
        val termuxInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getApplicationInfo(
                TermuxContract.PACKAGE_NAME,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            appContext.packageManager.getApplicationInfo(TermuxContract.PACKAGE_NAME, 0)
        }
        val script = RootSetupScript.build(
            managerPackage = appContext.packageName,
            termuxDataDir = termuxInfo.dataDir,
            termuxUid = termuxInfo.uid,
            termuxUserId = termuxInfo.uid / PER_USER_RANGE,
        )
        val process = try {
            ProcessBuilder(AlphaSuContract.rootCommand(script))
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException(
                "Magisk Alpha Root 启动失败：${error.message ?: "无法执行 su"}",
                error,
            )
        } catch (error: SecurityException) {
            throw IllegalStateException("系统阻止 Ubuntu 管理器启动 Magisk Alpha Root", error)
        }

        if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("等待 Magisk Alpha 授权超时，请在 Alpha 的“超级用户”中允许后重试")
        }

        val output = process.readOutput()
        val exitCode = process.exitValue()
        check(exitCode == 0) {
            AlphaSuContract.rootFailureMessage(exitCode, output)
        }
        if (output.lineSequence().any { it.trim() == PROOT_RUNNING_MARKER }) {
            error(
                "配置文件已写入，但检测到正在运行的 PRoot。为保护 Ubuntu，管理器没有重启 " +
                    "Termux；请先在 Termux 中正常停止实例，再次执行 Magisk Alpha 配置",
            )
        }
        check(output.lineSequence().any { it.trim() == ROOT_SUCCESS_MARKER }) {
            "Root 命令退出成功，但没有返回配置完成标记"
        }
        check(
            ContextCompat.checkSelfPermission(
                appContext,
                TermuxContract.RUN_COMMAND_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED,
        ) {
            "Root 命令已完成，但 RUN_COMMAND 权限校验未通过"
        }

        "Magisk Alpha $alphaVersion 配置完成：权限已授予、外部调用已启用，Termux 已安全重启"
    }

    suspend fun repairTermuxStoragePermissions(): String = withContext(Dispatchers.IO) {
        val alphaVersion = requireSupportedAlphaManager()
        requireSupportedAlphaSu()
        @Suppress("DEPRECATION")
        val termuxInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getApplicationInfo(
                TermuxContract.PACKAGE_NAME,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            appContext.packageManager.getApplicationInfo(TermuxContract.PACKAGE_NAME, 0)
        }
        val userId = termuxInfo.uid / PER_USER_RANGE
        val script = RootStorageScript.build(userId)
        val process = try {
            ProcessBuilder(AlphaSuContract.rootCommand(script))
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException(
                "Magisk Alpha Root 启动失败：${error.message ?: "无法执行 su"}",
                error,
            )
        } catch (error: SecurityException) {
            throw IllegalStateException("系统阻止 Ubuntu 管理器启动 Magisk Alpha Root", error)
        }

        if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("等待 Magisk Alpha 授权超时，请在 Alpha 的“超级用户”中允许后重试")
        }
        val output = process.readOutput()
        val exitCode = process.exitValue()
        check(exitCode == 0) {
            AlphaSuContract.rootFailureMessage(exitCode, output)
        }
        check(output.lineSequence().any { it.trim() == STORAGE_ROOT_SUCCESS_MARKER }) {
            "Root 命令退出成功，但没有返回存储权限修复标记"
        }
        check(
            appContext.packageManager.checkPermission(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                TermuxContract.PACKAGE_NAME,
            ) == PackageManager.PERMISSION_GRANTED,
        ) { "Root 命令完成，但 Termux 读取存储权限校验未通过" }
        check(
            appContext.packageManager.checkPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                TermuxContract.PACKAGE_NAME,
            ) == PackageManager.PERMISSION_GRANTED,
        ) { "Root 命令完成，但 Termux 写入存储权限校验未通过" }

        "Magisk Alpha $alphaVersion 已修复 Termux 系统存储权限"
    }

    suspend fun createTermuxStorageLinks(rebuild: Boolean): String =
        withContext(Dispatchers.IO) {
            val alphaVersion = requireSupportedAlphaManager()
            requireSupportedAlphaSu()
            @Suppress("DEPRECATION")
            val termuxInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getApplicationInfo(
                    TermuxContract.PACKAGE_NAME,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                appContext.packageManager.getApplicationInfo(TermuxContract.PACKAGE_NAME, 0)
            }
            val userId = termuxInfo.uid / PER_USER_RANGE
            val script = RootStorageLinkScript.build(
                managerPackage = appContext.packageName,
                termuxDataDir = termuxInfo.dataDir,
                userId = userId,
                rebuild = rebuild,
            )
            val process = try {
                ProcessBuilder(AlphaSuContract.rootCommand(script))
                    .redirectErrorStream(true)
                    .start()
            } catch (error: IOException) {
                throw IllegalStateException(
                    "Magisk Alpha Root 启动失败：${error.message ?: "无法执行 su"}",
                    error,
                )
            } catch (error: SecurityException) {
                throw IllegalStateException(
                    "系统阻止 Ubuntu 管理器启动 Magisk Alpha Root",
                    error,
                )
            }

            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("等待 Termux 创建目录链接超时")
            }
            val output = process.readOutput()
            when {
                output.hasMarker(STORAGE_LINK_EXISTS_MARKER) ->
                    error("~/storage 已存在；如需修复，请使用“重建目录链接”")
                output.hasMarker(STORAGE_LINK_UNSAFE_MARKER) ->
                    error(
                        "~/storage 中存在普通文件或目录。为避免误删，管理器已拒绝重建；" +
                            "请先在 Termux 中移走这些内容",
                    )
                output.hasMarker(STORAGE_LINK_TIMEOUT_MARKER) ->
                    error(
                        "Termux 已打开，但官方目录链接创建超时；没有使用 Root 直接修改目录",
                    )
            }
            val exitCode = process.exitValue()
            check(exitCode == 0) {
                AlphaSuContract.rootFailureMessage(exitCode, output)
            }
            check(output.hasMarker(STORAGE_LINK_SUCCESS_MARKER)) {
                "Root 命令退出成功，但没有返回目录链接创建标记"
            }

            "Magisk Alpha $alphaVersion 已调用 Termux 官方流程创建目录链接"
        }

    suspend fun protectBackgroundApps(): String = withContext(Dispatchers.IO) {
        val alphaVersion = requireSupportedAlphaManager()
        requireSupportedAlphaSu()
        val userId = android.os.Process.myUid() / PER_USER_RANGE
        val script = RootBackgroundProtectionScript.build(
            managerPackage = appContext.packageName,
            userId = userId,
        )
        val process = try {
            ProcessBuilder(AlphaSuContract.rootCommand(script))
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException(
                "Magisk Alpha Root 启动失败：${error.message ?: "无法执行 su"}",
                error,
            )
        } catch (error: SecurityException) {
            throw IllegalStateException("系统阻止 Ubuntu 管理器启动 Magisk Alpha Root", error)
        }

        if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("等待 Magisk Alpha 后台保护配置超时")
        }
        val output = process.readOutput()
        val exitCode = process.exitValue()
        check(exitCode == 0) {
            AlphaSuContract.rootFailureMessage(exitCode, output)
        }
        check(output.hasMarker(BACKGROUND_PROTECTION_SUCCESS_MARKER)) {
            "Root 命令退出成功，但没有返回后台保护配置标记"
        }
        "Magisk Alpha $alphaVersion 已将 Termux 和 Ubuntu 管理器加入 Android 后台白名单"
    }

    private fun requireSupportedAlphaManager(): String {
        @Suppress("DEPRECATION")
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    AlphaSuContract.MANAGER_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                appContext.packageManager.getPackageInfo(AlphaSuContract.MANAGER_PACKAGE, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            error(
                "未安装指定的 Magisk Alpha（${AlphaSuContract.MANAGER_PACKAGE}）；" +
                    "此入口不兼容普通 Magisk",
            )
        }
        val version = packageInfo.versionName.orEmpty()
        check(AlphaSuContract.isSupportedManagerVersion(version)) {
            "检测到的管理器不是支持的 Magisk Alpha（版本：${version.ifBlank { "未知" }}）；" +
                "此入口不兼容普通 Magisk"
        }
        return version
    }

    private fun requireSupportedAlphaSu() {
        val process = try {
            ProcessBuilder(AlphaSuContract.SU_PATH, "-v")
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException(AlphaSuContract.hiddenRootMessage(), error)
        } catch (error: SecurityException) {
            throw IllegalStateException("系统阻止 Ubuntu 管理器检测 Magisk Alpha Root", error)
        }

        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("检测 Magisk Alpha Root 超时")
        }
        val output = process.readOutput()
        check(process.exitValue() == 0) {
            "Magisk Alpha Root 检测失败（退出码 ${process.exitValue()}）：" +
                output.ifBlank { "无输出" }
        }
        check(AlphaSuContract.isSupportedSuVersion(output)) {
            "检测到的 su 不是 Magisk Alpha（${output.ifBlank { "无版本信息" }}）；" +
                "此入口不兼容普通 Magisk"
        }
    }

    private fun Process.readOutput(): String = inputStream
        .bufferedReader()
        .use { it.readText() }
        .trim()
        .takeLast(MAX_OUTPUT_LENGTH)

    private fun String.hasMarker(marker: String): Boolean =
        lineSequence().any { it.trim() == marker }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 5L
        const val ROOT_TIMEOUT_SECONDS = 90L
        const val MAX_OUTPUT_LENGTH = 4_000
        const val PER_USER_RANGE = 100_000
        const val ROOT_SUCCESS_MARKER = "ROOT_SETUP_OK"
        const val STORAGE_ROOT_SUCCESS_MARKER = "ROOT_STORAGE_OK"
        const val STORAGE_LINK_SUCCESS_MARKER = "ROOT_STORAGE_LINKS_OK"
        const val STORAGE_LINK_EXISTS_MARKER = "ROOT_STORAGE_LINKS_EXIST"
        const val STORAGE_LINK_UNSAFE_MARKER = "ROOT_STORAGE_LINKS_UNSAFE"
        const val STORAGE_LINK_TIMEOUT_MARKER = "ROOT_STORAGE_LINKS_TIMEOUT"
        const val BACKGROUND_PROTECTION_SUCCESS_MARKER = "ROOT_BACKGROUND_PROTECTION_OK"
        const val PROOT_RUNNING_MARKER = "ROOT_SETUP_PROOT_RUNNING"
    }
}

internal object RootBackgroundProtectionScript {
    private val packagePattern =
        Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")

    fun build(managerPackage: String, userId: Int): String {
        require(packagePattern.matches(managerPackage)) { "管理器包名不安全" }
        require(userId >= 0) { "Android 用户 ID 无效" }
        return """
            set -e
            /system/bin/cmd deviceidle whitelist +$managerPackage
            /system/bin/cmd deviceidle whitelist +com.termux
            /system/bin/am set-inactive --user $userId $managerPackage false || true
            /system/bin/am set-inactive --user $userId com.termux false || true
            /system/bin/printf 'ROOT_BACKGROUND_PROTECTION_OK\n'
        """.trimIndent()
    }
}

internal object RootStorageScript {
    fun build(userId: Int): String {
        require(userId >= 0) { "Termux 用户 ID 无效" }
        return """
            set -e
            /system/bin/pm grant --user $userId com.termux \
                android.permission.READ_EXTERNAL_STORAGE
            /system/bin/pm grant --user $userId com.termux \
                android.permission.WRITE_EXTERNAL_STORAGE
            /system/bin/cmd appops set --user $userId com.termux \
                READ_EXTERNAL_STORAGE allow
            /system/bin/cmd appops set --user $userId com.termux \
                WRITE_EXTERNAL_STORAGE allow
            /system/bin/cmd appops set --user $userId com.termux \
                MANAGE_EXTERNAL_STORAGE allow
            /system/bin/printf 'ROOT_STORAGE_OK\n'
        """.trimIndent()
    }
}

internal object RootStorageLinkScript {
    private val packagePattern =
        Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")
    private val termuxDataDirPattern =
        Regex("""^/data/(?:data|user/[0-9]+|user_de/[0-9]+)/com\.termux$""")

    fun build(
        managerPackage: String,
        termuxDataDir: String,
        userId: Int,
        rebuild: Boolean,
    ): String {
        require(packagePattern.matches(managerPackage)) { "管理器包名不安全" }
        require(termuxDataDirPattern.matches(termuxDataDir)) { "Termux 数据目录不安全" }
        require(userId >= 0) { "Termux 用户 ID 无效" }

        val existingDirectoryHandling = if (rebuild) {
            """
                unsafe_entry="$(
                    /system/bin/find "${'$'}storage_dir" -mindepth 1 -maxdepth 1 \
                        ! -type l -print 2>/dev/null | /system/bin/head -n 1
                )"
                if [ -n "${'$'}unsafe_entry" ]; then
                    /system/bin/printf 'ROOT_STORAGE_LINKS_UNSAFE\n'
                    exit 22
                fi
            """.trimIndent()
        } else {
            """
                /system/bin/printf 'ROOT_STORAGE_LINKS_EXIST\n'
                exit 21
            """.trimIndent()
        }

        return """
            set -eu
            storage_dir='$termuxDataDir/files/home/storage'
            return_to_manager() {
                /system/bin/am start --user $userId -f 0x24000000 \
                    -n $managerPackage/.MainActivity \
                    --ez $managerPackage.OPEN_STORAGE_SETTINGS true \
                    >/dev/null 2>&1 || true
            }
            trap return_to_manager EXIT

            if [ -e "${'$'}storage_dir" ] || [ -L "${'$'}storage_dir" ]; then
                $existingDirectoryHandling
            fi

            /system/bin/am start --user $userId -W \
                -n com.termux/.app.TermuxActivity >/dev/null
            /system/bin/sleep 1
            /system/bin/am broadcast --user $userId \
                -a com.termux.app.request_storage_permissions \
                -p com.termux >/dev/null

            attempt=0
            while [ "${'$'}attempt" -lt 40 ]; do
                if [ -L "${'$'}storage_dir/shared" ] &&
                    [ -L "${'$'}storage_dir/downloads" ] &&
                    [ -d "${'$'}storage_dir/downloads" ]; then
                    /system/bin/printf 'ROOT_STORAGE_LINKS_OK\n'
                    exit 0
                fi
                attempt=$((attempt + 1))
                /system/bin/sleep 0.25
            done

            /system/bin/printf 'ROOT_STORAGE_LINKS_TIMEOUT\n'
            exit 23
        """.trimIndent()
    }
}

internal object AlphaSuContract {
    const val MANAGER_PACKAGE = "io.github.vvb2060.magisk"
    const val SU_PATH = "/product/bin/su"

    fun rootCommand(script: String): List<String> =
        listOf(SU_PATH, "-t", "0", "-c", script)

    fun isSupportedManagerVersion(version: String): Boolean =
        version.contains("alpha", ignoreCase = true)

    fun isSupportedSuVersion(output: String): Boolean =
        output.contains("-alpha:MAGISKSU", ignoreCase = true)

    fun hiddenRootMessage(): String =
        "Magisk Alpha 已安装，但 Ubuntu 管理器看不到 $SU_PATH。" +
            "请打开 Alpha → 设置 → 配置排除列表，取消勾选 Ubuntu 管理器及其全部进程；" +
            "再强制停止并重新打开 Ubuntu 管理器"

    fun rootFailureMessage(exitCode: Int, output: String): String {
        val wasDenied = output.contains("denied", ignoreCase = true) ||
            output.contains("not allowed", ignoreCase = true)
        return if (wasDenied) {
            "Magisk Alpha 已拒绝 Ubuntu 管理器的超级用户权限；" +
                "请在 Alpha 的“超级用户”页面改为允许后重试"
        } else {
            "Magisk Alpha 配置失败（退出码 $exitCode）：" +
                output.ifBlank {
                    "无输出。请在 Alpha 的“超级用户”页面确认 Ubuntu 管理器已被允许"
                }
        }
    }
}

internal object RootSetupScript {
    private val packagePattern =
        Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")
    private val termuxDataDirPattern =
        Regex("""^/data/(?:data|user/[0-9]+|user_de/[0-9]+)/com\.termux$""")

    fun build(
        managerPackage: String,
        termuxDataDir: String,
        termuxUid: Int,
        termuxUserId: Int,
    ): String {
        require(packagePattern.matches(managerPackage)) { "管理器包名不安全" }
        require(termuxDataDirPattern.matches(termuxDataDir)) { "Termux 数据目录不安全" }
        require(termuxUid > 0) { "Termux UID 无效" }
        require(termuxUserId >= 0) { "Termux 用户 ID 无效" }

        return """
            set -e
            /system/bin/pm grant $managerPackage com.termux.permission.RUN_COMMAND

            termux_dir='$termuxDataDir/files/home/.termux'
            termux_file="${'$'}termux_dir/termux.properties"
            termux_backup="${'$'}termux_file.ubuntu-manager.bak"
            termux_tmp="${'$'}termux_file.ubuntu-manager.tmp"

            /system/bin/mkdir -p "${'$'}termux_dir"
            if [ -f "${'$'}termux_file" ]; then
                /system/bin/cp -p "${'$'}termux_file" "${'$'}termux_backup"
                /system/bin/sed \
                    -e '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' \
                    -e '/^[[:space:]]*#[[:space:]]*allow-external-apps[[:space:]]*=/d' \
                    "${'$'}termux_file" > "${'$'}termux_tmp"
            else
                : > "${'$'}termux_tmp"
            fi
            /system/bin/printf '\nallow-external-apps=true\n' >> "${'$'}termux_tmp"

            /system/bin/chown $termuxUid:$termuxUid "${'$'}termux_dir" "${'$'}termux_tmp"
            /system/bin/chmod 700 "${'$'}termux_dir"
            /system/bin/chmod 600 "${'$'}termux_tmp"
            /system/bin/mv -f "${'$'}termux_tmp" "${'$'}termux_file"
            /system/bin/chown $termuxUid:$termuxUid "${'$'}termux_file"
            /system/bin/restorecon -RF "${'$'}termux_dir" >/dev/null 2>&1 || true

            /system/bin/grep -q \
                '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}' \
                "${'$'}termux_file"
            active_count="${'$'}(/system/bin/grep -c \
                '^[[:space:]]*allow-external-apps[[:space:]]*=' \
                "${'$'}termux_file")"
            [ "${'$'}active_count" = "1" ]

            if /system/bin/pidof proot >/dev/null 2>&1 ||
                /system/bin/pidof proot-loader >/dev/null 2>&1; then
                /system/bin/printf 'ROOT_SETUP_PROOT_RUNNING\n'
                exit 0
            fi

            /system/bin/am force-stop --user $termuxUserId com.termux
            /system/bin/am start --user $termuxUserId \
                -n com.termux/.app.TermuxActivity >/dev/null
            /system/bin/sleep 2
            /system/bin/am start --user $termuxUserId -f 0x34000000 \
                -n $managerPackage/.MainActivity >/dev/null
            /system/bin/printf 'ROOT_SETUP_OK\n'
        """.trimIndent()
    }
}
