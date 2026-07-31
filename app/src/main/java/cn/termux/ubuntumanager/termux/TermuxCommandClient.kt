package cn.termux.ubuntumanager.termux

import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import cn.termux.ubuntumanager.model.CommandResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class TermuxCommandTimeoutException(
    label: String,
    timeoutMillis: Long,
) : IllegalStateException(
    "Termux 在 ${timeoutMillis / 1_000} 秒内没有响应（$label），请打开 Termux 后重新连接",
)

class TermuxCommandClient(context: Context) {
    private val appContext = context.applicationContext
    private val executionMutex = Mutex()

    fun isTermuxInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(TermuxContract.PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun termuxVersion(): String? = try {
        appContext.packageManager
            .getPackageInfo(TermuxContract.PACKAGE_NAME, 0)
            .versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun isTermuxStopped(): Boolean = try {
        val applicationInfo = appContext.packageManager
            .getApplicationInfo(TermuxContract.PACKAGE_NAME, 0)
        applicationInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            TermuxContract.RUN_COMMAND_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    fun termuxPermissionGranted(permission: String): Boolean =
        appContext.packageManager.checkPermission(
            permission,
            TermuxContract.PACKAGE_NAME,
        ) == PackageManager.PERMISSION_GRANTED

    fun termuxAllFilesAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val applicationInfo = try {
            appContext.packageManager.getApplicationInfo(TermuxContract.PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return runCatching {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.unsafeCheckOpNoThrow(
                MANAGE_EXTERNAL_STORAGE_APP_OP,
                applicationInfo.uid,
                TermuxContract.PACKAGE_NAME,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    suspend fun execute(
        commandPath: String,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        label: String,
        description: String = "",
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): CommandResult = executionMutex.withLock {
        check(isTermuxInstalled()) { "没有检测到 Termux" }
        check(hasRunCommandPermission()) { "尚未授予“在 Termux 中运行命令”权限" }

        val executionId = ResultRegistry.nextId()
        val deferred = ResultRegistry.register(executionId)
        val resultIntent = Intent(appContext, CommandResultService::class.java)
            .putExtra(CommandResultService.EXTRA_EXECUTION_ID, executionId)

        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val pendingIntent = PendingIntent.getService(
            appContext,
            executionId,
            resultIntent,
            flags,
        )

        val commandIntent = Intent()
            .setClassName(TermuxContract.PACKAGE_NAME, TermuxContract.RUN_COMMAND_SERVICE)
            .setAction(TermuxContract.ACTION_RUN_COMMAND)
            .putExtra(TermuxContract.EXTRA_COMMAND_PATH, commandPath)
            .putExtra(TermuxContract.EXTRA_ARGUMENTS, arguments.toTypedArray())
            .putExtra(TermuxContract.EXTRA_WORKDIR, TermuxContract.HOME)
            .putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            .putExtra(TermuxContract.EXTRA_COMMAND_LABEL, label)
            .putExtra(TermuxContract.EXTRA_COMMAND_DESCRIPTION, description)
            .putExtra(TermuxContract.EXTRA_PENDING_INTENT, pendingIntent)

        if (stdin != null) {
            commandIntent.putExtra(TermuxContract.EXTRA_STDIN, stdin)
        }

        try {
            appContext.startService(commandIntent)
        } catch (error: Exception) {
            ResultRegistry.cancel(executionId, error)
            throw IllegalStateException(
                "无法调用 Termux，请检查 RUN_COMMAND 权限和 allow-external-apps=true",
                error,
            )
        }

        try {
            try {
                withTimeout(timeoutMillis) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                throw TermuxCommandTimeoutException(label, timeoutMillis)
            }
        } finally {
            ResultRegistry.remove(executionId)
            pendingIntent.cancel()
        }
    }

    fun openTermux(): Boolean {
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(TermuxContract.PACKAGE_NAME)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        appContext.startActivity(intent)
        return true
    }

    internal object ResultRegistry {
        private val idGenerator = AtomicInteger(1000)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<CommandResult>>()

        fun nextId(): Int = idGenerator.incrementAndGet()

        fun register(id: Int): CompletableDeferred<CommandResult> =
            CompletableDeferred<CommandResult>().also { pending[id] = it }

        fun complete(id: Int, result: CommandResult): Boolean =
            pending[id]?.complete(result) == true

        fun cancel(id: Int, error: Throwable) {
            pending.remove(id)?.completeExceptionally(error)
        }

        fun remove(id: Int) {
            pending.remove(id)
        }
    }

    companion object {
        private const val MANAGE_EXTERNAL_STORAGE_APP_OP = "android:manage_external_storage"
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val LONG_TIMEOUT_MILLIS = 45 * 60_000L
    }
}
