package cn.termux.ubuntumanager.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import cn.termux.ubuntumanager.model.CommandResult
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Executes the manager's fixed maintenance scripts through Magisk Alpha. */
class RootCommandExecutor(context: Context) {
    private val appContext = context.applicationContext
    private val executeMutex = Mutex()
    private var rootSession: RootShellSession? = null

    fun alphaVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                AlphaSuContract.MANAGER_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            appContext.packageManager.getPackageInfo(AlphaSuContract.MANAGER_PACKAGE, 0)
        }
        info.versionName?.takeIf(AlphaSuContract::isSupportedManagerVersion)
    }.getOrNull()

    suspend fun probe(timeoutMillis: Long = 8_000): CommandResult {
        if (alphaVersion() == null) {
            return CommandResult(
                internalErrorCode = 1,
                internalErrorMessage = "未检测到受支持的 Magisk Alpha",
            )
        }
        return execute("id -u; /system/bin/printf 'CNTERMUX_ROOT_OK\\n'", timeoutMillis)
    }

    suspend fun execute(
        script: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        input: InputStream? = null,
        maxCaptureLength: Int = DEFAULT_MAX_CAPTURE_LENGTH,
    ): CommandResult {
        require(maxCaptureLength > 0) { "输出抓取上限必须大于 0" }
        return executeMutex.withLock {
            executeUnlocked(script, timeoutMillis, input, maxCaptureLength)
        }
    }

    private suspend fun executeUnlocked(
        script: String,
        timeoutMillis: Long,
        input: InputStream?,
        maxCaptureLength: Int,
    ): CommandResult = withContext(Dispatchers.IO) {
        val stagedInput = input?.let(::stageInput)
        try {
            val session = rootSession?.takeIf { it.isAlive } ?: startRootSession().also {
                rootSession = it
            }
            val result = session.execute(
                script,
                timeoutMillis,
                stagedInput,
                maxCaptureLength,
            )
            if (!session.isAlive || result.internalErrorCode != -1) {
                session.close()
                if (rootSession === session) rootSession = null
            }
            result
        } catch (error: Exception) {
            rootSession?.close()
            rootSession = null
            CommandResult(
                internalErrorCode = 1,
                internalErrorMessage = when (error) {
                    is java.io.IOException -> AlphaSuContract.hiddenRootMessage()
                    else -> error.message ?: error::class.java.simpleName
                },
            )
        } finally {
            stagedInput?.delete()
        }
    }

    private fun stageInput(input: InputStream): File =
        File.createTempFile("root-input-", ".bin", appContext.cacheDir).also { target ->
            input.use { source -> target.outputStream().use(source::copyTo) }
            target.setReadable(true, true)
        }

    private fun startRootSession(): RootShellSession {
        val process = ProcessBuilder(AlphaSuContract.rootShellCommand())
            .redirectErrorStream(true)
            .start()
        return RootShellSession(process)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val DEFAULT_MAX_CAPTURE_LENGTH = 64_000
    }
}

internal class RootShellSession(private val process: Process) {
    private val writer = process.outputStream.bufferedWriter()
    private val reader = process.inputStream.bufferedReader()
    private val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cntermux-root-output").apply { isDaemon = true }
    }

    val isAlive: Boolean
        get() = process.isAlive

    fun execute(
        script: String,
        timeoutMillis: Long,
        input: File?,
        maxCaptureLength: Int = RootCommandExecutor.DEFAULT_MAX_CAPTURE_LENGTH,
    ): CommandResult {
        if (!process.isAlive) return sessionEnded()
        val marker = "__CNTERMUX_DONE_${UUID.randomUUID().toString().replace("-", "")}__"
        return try {
            writer.append("(\n")
            writer.append(script)
            writer.append("\n)")
            input?.let { writer.append(" < '").append(it.absolutePath).append("'") }
            writer.append("\n_cntermux_result=$?\n")
            writer.append("printf '\\n")
                .append(marker)
                .append("%s\\n' \"${'$'}_cntermux_result\"\n")
            writer.flush()

            val pending = readerExecutor.submit<CommandResult> {
                readUntil(marker, maxCaptureLength)
            }
            try {
                pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                pending.cancel(true)
                close()
                CommandResult(
                    internalErrorCode = 2,
                    internalErrorMessage = "Root 操作超时（${timeoutMillis / 1_000} 秒）",
                )
            }
        } catch (error: Exception) {
            close()
            CommandResult(
                internalErrorCode = 1,
                internalErrorMessage = error.message ?: "Root 会话已经结束",
            )
        }
    }

    private fun readUntil(marker: String, maxCaptureLength: Int): CommandResult {
        val output = StringBuilder()
        var originalLength = 0
        while (true) {
            val line = reader.readLine() ?: return sessionEnded(output.toString())
            if (line.startsWith(marker)) {
                val exitCode = line.removePrefix(marker).trim().toIntOrNull()
                    ?: return CommandResult(
                        stdout = output.toString(),
                        internalErrorCode = 1,
                        internalErrorMessage = "Root 会话返回了无效退出码",
                    )
                return CommandResult(
                    stdout = output.toString().trimEnd(),
                    exitCode = exitCode,
                    stdoutOriginalLength = originalLength,
                )
            }
            originalLength += line.length + 1
            output.append(line).append('\n')
            if (output.length > maxCaptureLength) {
                output.delete(0, output.length - maxCaptureLength)
            }
        }
    }

    fun close() {
        runCatching { writer.close() }
        runCatching { process.destroyForcibly() }
        readerExecutor.shutdownNow()
    }

    private fun sessionEnded(output: String = ""): CommandResult = CommandResult(
        stdout = output.trimEnd(),
        internalErrorCode = 1,
        internalErrorMessage = output.trim().ifBlank { "Root 会话已经结束" },
    )
}

internal object AlphaSuContract {
    const val MANAGER_PACKAGE = "io.github.vvb2060.magisk"
    const val SU_PATH = "/product/bin/su"

    fun rootShellCommand(): List<String> =
        listOf(SU_PATH, "-t", "0", "-c", "/system/bin/sh")
    fun isSupportedManagerVersion(version: String): Boolean =
        version.contains("alpha", ignoreCase = true)
    fun hiddenRootMessage(): String =
        "Magisk Alpha 已安装，但 Ubuntu 管理器看不到 $SU_PATH。" +
            "请在 Alpha 的配置排除列表中取消勾选 Ubuntu 管理器及其全部进程，然后强制停止并重新打开管理器"
}
