package io.iaw.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.UUID
import java.util.concurrent.Executors

class ReceiveQueueService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var statusStore: TransferStatusStore

    @Volatile
    private var currentOperationId: String? = null

    override fun onCreate() {
        super.onCreate()
        statusStore = TransferStatusStore(this)
        val interrupted = statusStore.interruptActive()
        if (interrupted != null) {
            recordHistory(interrupted)
            runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val operationId = UUID.randomUUID().toString()
        if (!OPERATION_GATE.tryAcquire(operationId)) {
            reportBusy()
            return START_NOT_STICKY
        }
        currentOperationId = operationId

        val serverId = SettingsStore(this).loadActive()?.id.orEmpty()
        val sourceWidgetId = intent?.getIntExtra(
            EXTRA_APP_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val startedAt = System.currentTimeMillis()
        val initialStatus = statusStore.begin(operationId, serverId, sourceWidgetId, startedAt)

        try {
            ensureNotificationChannel()
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    title = text(R.string.receive_notification_title),
                    text = text(R.string.checking_queue),
                    ongoing = true,
                    status = initialStatus,
                ),
            )
        } catch (_: Exception) {
            abortBeforeWorker(operationId, startId)
            return START_NOT_STICKY
        }
        runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }

        try {
            worker.execute {
                val outcome = receiveQueuedFiles(operationId, serverId)
                runOnMain {
                    finishOperation(operationId, outcome)
                }
            }
        } catch (_: Exception) {
            abortBeforeWorker(operationId, startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val operationId = currentOperationId
        currentOperationId = null
        if (operationId != null && OPERATION_GATE.release(operationId)) {
            statusStore.interruptActive()
            runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun receiveQueuedFiles(operationId: String, serverId: String): ReceiveOutcome {
        val config = SettingsStore(this).findById(serverId)
        if (config == null) {
            val status = statusStore.fail(
                operationId,
                TransferErrorCode.NO_CONFIG,
                System.currentTimeMillis(),
            ) ?: TransferStatus(serverId = serverId, phase = TransferPhase.ERROR)
            return ReceiveOutcome(status, messageFor(status))
        }

        var completedItems = 0
        var lastProgressUpdateAt = 0L
        var lastProgressBucket = -1
        return try {
            val count = DownloadClient(this, config).fetchAllPending { progress ->
                completedItems = progress.completedItems
                val now = System.currentTimeMillis()
                val status = when (progress.phase) {
                    DownloadProgressPhase.CHECKING,
                    DownloadProgressPhase.ITEM_COMPLETED,
                    -> statusStore.updateChecking(operationId, progress.completedItems, now)

                    DownloadProgressPhase.ITEM_STARTED -> statusStore.updateReceiving(
                        operationId = operationId,
                        itemName = progress.itemName,
                        itemIndex = progress.itemIndex,
                        completedItems = progress.completedItems,
                        bytesReceived = 0L,
                        totalBytes = progress.totalBytes,
                        now = now,
                    )

                    DownloadProgressPhase.BYTES_RECEIVED -> {
                        val bucket = progressBucket(progress.bytesReceived, progress.totalBytes)
                        val shouldPublish = now - lastProgressUpdateAt >= PROGRESS_UPDATE_INTERVAL_MILLIS ||
                            bucket != lastProgressBucket ||
                            progress.bytesReceived >= progress.totalBytes
                        if (!shouldPublish) {
                            null
                        } else {
                            lastProgressUpdateAt = now
                            lastProgressBucket = bucket
                            statusStore.updateReceiving(
                                operationId = operationId,
                                itemName = progress.itemName,
                                itemIndex = progress.itemIndex,
                                completedItems = progress.completedItems,
                                bytesReceived = progress.bytesReceived,
                                totalBytes = progress.totalBytes,
                                now = now,
                            )
                        }
                    }
                }
                status?.let(::publishProgress)
            }
            val status = statusStore.complete(operationId, count, System.currentTimeMillis())
                ?: statusStore.load()
            ReceiveOutcome(status, messageFor(status))
        } catch (error: Exception) {
            val code = TransferFailureClassifier.classify(error, completedItems)
            val status = statusStore.fail(operationId, code, System.currentTimeMillis())
                ?: statusStore.load()
            ReceiveOutcome(status, messageFor(status))
        }
    }

    private fun publishProgress(status: TransferStatus) {
        val text = when (status.phase) {
            TransferPhase.RECEIVING -> text(
                R.string.receive_notification_progress,
                status.currentItemIndex,
                status.currentItemName,
            )
            else -> text(R.string.checking_queue)
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(
                    title = text(R.string.receive_notification_title),
                    text = text,
                    ongoing = true,
                    status = status,
                ),
            )
        }
        runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }
    }

    private fun finishOperation(operationId: String, outcome: ReceiveOutcome) {
        currentOperationId = null
        OPERATION_GATE.release(operationId)
        try {
            recordHistory(outcome.status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            runCatching { showResultNotification(outcome.message) }
            runCatching { sendFinishedBroadcast(outcome.message, outcome.status) }
            runCatching { Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show() }
            runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }
        } finally {
            stopSelf()
        }
    }

    private fun reportBusy() {
        val message = text(R.string.receive_busy)
        runCatching { sendFinishedBroadcast(message, TransferStatus(phase = TransferPhase.BUSY)) }
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }
    }

    private fun abortBeforeWorker(operationId: String, startId: Int) {
        val status = statusStore.fail(
            operationId,
            TransferErrorCode.UNKNOWN,
            System.currentTimeMillis(),
        ) ?: TransferStatus(phase = TransferPhase.ERROR, errorCode = TransferErrorCode.UNKNOWN)
        currentOperationId = null
        OPERATION_GATE.release(operationId)
        recordHistory(status)
        val message = messageFor(status)
        runCatching { sendFinishedBroadcast(message, status) }
        runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        runCatching { WifiShareWidgetProvider.updateAllWidgets(this) }
        stopSelf(startId)
    }

    private fun messageFor(status: TransferStatus): String {
        return when (status.phase) {
            TransferPhase.SUCCESS -> quantity(
                R.plurals.download_complete,
                status.completedItems,
                status.completedItems,
            )
            TransferPhase.EMPTY -> text(R.string.no_phone_queue)
            TransferPhase.INTERRUPTED -> text(R.string.receive_interrupted)
            TransferPhase.ERROR -> when (status.errorCode) {
                TransferErrorCode.NO_CONFIG -> text(R.string.invalid_config)
                TransferErrorCode.NETWORK_UNREACHABLE -> text(R.string.receive_network_unreachable)
                TransferErrorCode.TLS_MISMATCH -> text(R.string.receive_tls_mismatch)
                TransferErrorCode.AUTH_FAILED -> text(R.string.receive_auth_failed)
                TransferErrorCode.STORAGE_FAILED -> text(R.string.receive_storage_failed)
                TransferErrorCode.PARTIAL_FAILURE -> quantity(
                    R.plurals.receive_partial_failure,
                    status.completedItems,
                    status.completedItems,
                )
                else -> text(R.string.receive_failed)
            }
            TransferPhase.BUSY -> text(R.string.receive_busy)
            else -> text(R.string.receive_failed)
        }
    }

    private fun recordHistory(status: TransferStatus) {
        val serverName = SettingsStore(this).findById(status.serverId)?.serverName.orEmpty()
        TransferHistoryStore(this).recordReceive(status, serverName)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                text(R.string.receive_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun showResultNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            buildNotification(
                title = text(R.string.receive_notification_done),
                text = message,
                ongoing = false,
            ),
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean,
        status: TransferStatus? = null,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openMainIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing && status != null) {
            val hasByteProgress = status.phase == TransferPhase.RECEIVING && status.totalBytes > 0L
            if (hasByteProgress) {
                builder.setProgress(PROGRESS_MAX, progressValue(status), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun openMainIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            OPEN_MAIN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun sendFinishedBroadcast(message: String, status: TransferStatus) {
        sendBroadcast(
            Intent(ACTION_RECEIVE_FINISHED)
                .setPackage(packageName)
                .putExtra(EXTRA_RESULT_MESSAGE, message)
                .putExtra(EXTRA_RESULT_PHASE, status.phase.name)
                .putExtra(EXTRA_RESULT_ERROR, status.errorCode.name),
            InternalBroadcasts.PERMISSION,
        )
    }

    private fun progressBucket(bytesReceived: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) {
            return -1
        }
        return ((bytesReceived.coerceIn(0L, totalBytes) * 20L) / totalBytes).toInt()
    }

    private fun progressValue(status: TransferStatus): Int {
        if (status.totalBytes <= 0L) {
            return 0
        }
        return ((status.bytesReceived.coerceIn(0L, status.totalBytes) * PROGRESS_MAX) / status.totalBytes)
            .toInt()
    }

    private fun runOnMain(block: () -> Unit) {
        mainExecutor.execute(block)
    }

    private fun text(@StringRes resource: Int, vararg arguments: Any): String {
        return AppLanguageController.localizedContext(this).getString(resource, *arguments)
    }

    private fun quantity(@PluralsRes resource: Int, count: Int, vararg arguments: Any): String {
        return AppLanguageController.localizedContext(this).resources.getQuantityString(
            resource,
            count,
            *arguments,
        )
    }

    private data class ReceiveOutcome(
        val status: TransferStatus,
        val message: String,
    )

    companion object {
        const val ACTION_RECEIVE_FINISHED = "io.iaw.lanshare.action.RECEIVE_FINISHED"
        const val EXTRA_RESULT_MESSAGE = "io.iaw.lanshare.extra.RECEIVE_RESULT_MESSAGE"
        const val EXTRA_RESULT_PHASE = "io.iaw.lanshare.extra.RECEIVE_RESULT_PHASE"
        const val EXTRA_RESULT_ERROR = "io.iaw.lanshare.extra.RECEIVE_RESULT_ERROR"

        private const val ACTION_RECEIVE = "io.iaw.lanshare.action.RECEIVE"
        private const val EXTRA_APP_WIDGET_ID = "io.iaw.lanshare.extra.APP_WIDGET_ID"
        private const val CHANNEL_ID = "wifishare_receive"
        private const val NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        private const val OPEN_MAIN_REQUEST_CODE = 10
        private const val PROGRESS_MAX = 1_000
        private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 1_000L
        private val OPERATION_GATE = ReceiveOperationGate()

        fun createIntent(
            context: Context,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        ): Intent {
            return Intent(context, ReceiveQueueService::class.java).apply {
                action = ACTION_RECEIVE
                data = Uri.parse(
                    WidgetPendingIntentIdentity.dataUri(
                        appWidgetId,
                        WidgetPendingIntentKind.RECEIVE,
                    ),
                )
                putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            }
        }
    }
}
