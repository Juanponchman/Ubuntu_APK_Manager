package cn.termux.ubuntumanager.operation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.termux.ubuntumanager.MainActivity
import cn.termux.ubuntumanager.UbuntuManagerApplication
import cn.termux.ubuntumanager.model.BackgroundOperationRecord
import cn.termux.ubuntumanager.model.BackgroundOperationStatus
import cn.termux.ubuntumanager.model.BackgroundOperationType
import cn.termux.ubuntumanager.model.OperationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OperationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operationJob: Job? = null
    private var currentRequest: BackgroundOperationRequest? = null

    private val app: UbuntuManagerApplication
        get() = application as UbuntuManagerApplication

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ubuntu 后台维护",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示备份、恢复、新建和复制等不能被界面退出打断的操作"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = BackgroundOperationRequest.fromIntent(intent)
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (operationJob?.isActive == true || activeOperationId != null) {
            app.repository.showMessage("已有后台维护任务正在运行，请等待任务完成")
            return START_NOT_STICKY
        }

        currentRequest = request
        activeOperationId = request.id
        startForeground(NOTIFICATION_ID, buildNotification(request.label))
        operationJob = serviceScope.launch { runOperation(request) }
        return START_NOT_STICKY
    }

    private suspend fun runOperation(request: BackgroundOperationRequest) {
        val started = System.currentTimeMillis()
        val runningRecord = BackgroundOperationRecord(
            id = request.id,
            type = request.type,
            label = request.label,
            instanceName = request.instanceName,
            status = BackgroundOperationStatus.RUNNING,
            startedEpochMillis = started,
            updatedEpochMillis = started,
            message = "后台维护任务正在运行；关闭界面不会取消任务",
        )

        try {
            app.preferences.setBackgroundOperation(runningRecord)
            val outcome = execute(request)
            app.preferences.setBackgroundOperation(
                runningRecord.copy(
                    status = if (outcome.succeeded) {
                        BackgroundOperationStatus.SUCCEEDED
                    } else {
                        BackgroundOperationStatus.FAILED
                    },
                    updatedEpochMillis = System.currentTimeMillis(),
                    message = outcome.message,
                ),
            )
        } catch (cancelled: CancellationException) {
            app.preferences.setBackgroundOperation(
                runningRecord.copy(
                    status = BackgroundOperationStatus.INTERRUPTED,
                    updatedEpochMillis = System.currentTimeMillis(),
                    message = "后台服务被结束，任务结果需要重新检查，管理器不会自动重复执行",
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: error::class.java.simpleName
            app.preferences.setBackgroundOperation(
                runningRecord.copy(
                    status = BackgroundOperationStatus.FAILED,
                    updatedEpochMillis = System.currentTimeMillis(),
                    message = "操作失败：$message",
                ),
            )
            app.repository.showMessage("操作失败：$message")
        } finally {
            currentRequest = null
            activeOperationId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun execute(request: BackgroundOperationRequest): OperationOutcome {
        val repository = app.repository
        fun instanceName(): String = requireNotNull(request.instanceName) {
            "后台任务缺少实例名称"
        }
        return when (request.type) {
            BackgroundOperationType.CREATE -> repository.create(
                name = instanceName(),
                port = request.port,
                initializeSsh = request.flag1,
                startAfterCreate = request.flag2,
                protectAfterCreate = request.flag3,
            )
            BackgroundOperationType.RENAME -> repository.rename(
                instanceName(),
                requireNotNull(request.secondaryName) { "重命名任务缺少新名称" },
            )
            BackgroundOperationType.INITIALIZE_SSH ->
                repository.initializeSsh(instanceName())
            BackgroundOperationType.DELETE_INSTANCE -> repository.delete(instanceName())
            BackgroundOperationType.BACKUP -> repository.backup(
                instanceName(),
                requireNotNull(request.secondaryName) { "备份任务缺少备份名称" },
            )
            BackgroundOperationType.RESTORE -> repository.restore(
                requireNotNull(request.backup) { "恢复任务缺少备份信息" },
            )
            BackgroundOperationType.EXECUTE_COMMAND -> repository.executeLibraryCommand(
                instanceName(),
                requireNotNull(request.commandId) { "指令任务缺少指令 ID" },
            )
            BackgroundOperationType.CLONE -> repository.cloneInstance(
                sourceName = instanceName(),
                targetName = requireNotNull(request.secondaryName) { "复制任务缺少目标名称" },
                targetPort = request.port,
                protectTarget = request.flag1,
            )
        }
    }

    private fun buildNotification(label: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Ubuntu 管理器")
        .setContentText(label)
        .setSubText("Root Chroot 维护任务运行中")
        .setContentIntent(contentIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setProgress(0, 0, true)
        .build()

    private fun contentIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
    }

    override fun onDestroy() {
        val request = currentRequest
        if (request != null && operationJob?.isActive == true) {
            app.applicationScope.launch {
                val current = app.preferences.snapshot().backgroundOperation
                if (
                    current?.id == request.id &&
                    current.status == BackgroundOperationStatus.RUNNING
                ) {
                    app.preferences.setBackgroundOperation(
                        current.copy(
                            status = BackgroundOperationStatus.INTERRUPTED,
                            updatedEpochMillis = System.currentTimeMillis(),
                            message = "后台服务被系统结束，重新操作前请检查实例状态",
                        ),
                    )
                }
            }
        }
        currentRequest = null
        activeOperationId = null
        operationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ubuntu_manager_operations_v2"
        private const val NOTIFICATION_ID = 2201

        @Volatile
        private var activeOperationId: String? = null

        fun isOperationActive(): Boolean = activeOperationId != null

        fun enqueue(context: Context, request: BackgroundOperationRequest) {
            val intent = Intent(context, OperationForegroundService::class.java)
            request.putInto(intent)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
