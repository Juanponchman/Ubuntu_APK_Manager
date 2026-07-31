package cn.termux.ubuntumanager.termux

import android.app.Service
import android.content.Intent
import android.os.IBinder
import cn.termux.ubuntumanager.UbuntuManagerApplication
import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.CommandResult
import kotlinx.coroutines.launch

class CommandResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(TermuxContract.RESULT_BUNDLE)
        if (executionId >= 0 && bundle != null) {
            val result = CommandResult(
                stdout = bundle.getString(TermuxContract.RESULT_STDOUT).orEmpty(),
                stderr = bundle.getString(TermuxContract.RESULT_STDERR).orEmpty(),
                exitCode = bundle.getInt(TermuxContract.RESULT_EXIT_CODE, -1),
                internalErrorCode = bundle.getInt(TermuxContract.RESULT_ERROR, -1),
                internalErrorMessage =
                    bundle.getString(TermuxContract.RESULT_ERROR_MESSAGE).orEmpty(),
                stdoutOriginalLength =
                    bundle.getString(TermuxContract.RESULT_STDOUT_ORIGINAL_LENGTH)
                        ?.toIntOrNull() ?: 0,
                stderrOriginalLength =
                    bundle.getString(TermuxContract.RESULT_STDERR_ORIGINAL_LENGTH)
                        ?.toIntOrNull() ?: 0,
            )
            val delivered = TermuxCommandClient.ResultRegistry.complete(executionId, result)
            if (!delivered) {
                persistOrphanedBackgroundResult(result)
            }
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun persistOrphanedBackgroundResult(result: CommandResult) {
        val app = application as UbuntuManagerApplication
        app.applicationScope.launch {
            val current = app.preferences.snapshot().backgroundOperation
            if (current?.status != BackgroundOperationStatus.RUNNING) return@launch
            val resultSummary = if (result.isSuccess) {
                "Termux 命令已经结束，但管理进程曾重启，后续收尾状态无法确认"
            } else {
                "Termux 命令在管理进程重启后返回失败：${result.bestError.take(300)}"
            }
            app.preferences.setBackgroundOperation(
                current.copy(
                    status = BackgroundOperationStatus.INTERRUPTED,
                    updatedEpochMillis = System.currentTimeMillis(),
                    message = "$resultSummary；请刷新并检查实例，管理器不会自动重复执行",
                ),
            )
            app.repository.refreshAll(allowConnectionProbe = true, showChecking = false)
        }
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "execution_id"
    }
}
