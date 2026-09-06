package io.iaw.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors

class UploadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private lateinit var statusStore: UploadStatusStore

    @Volatile
    private var activeUpload: ActiveUpload? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            val active = activeUpload ?: return
            statusStore.heartbeat(active.operationId, System.currentTimeMillis())
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        statusStore = UploadStatusStore(this)
        statusStore.interruptActive()?.let { status ->
            TransferHistoryStore(this).recordUpload(status)
            sendStatusBroadcast(status)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CANCEL_UPLOAD -> {
                cancelUpload(intent, startId)
                START_NOT_STICKY
            }

            ACTION_UPLOAD -> startUpload(intent, startId)
            else -> {
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeat)
        val active = activeUpload
        activeUpload = null
        if (active != null && OPERATION_GATE.release(active.operationId)) {
            active.cancellation.cancel()
            statusStore.interruptActive()?.let { status ->
                TransferHistoryStore(this).recordUpload(status)
                sendStatusBroadcast(status)
            }
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startUpload(intent: Intent, startId: Int): Int {
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val config = configFromIntent(intent)
        val uris = urisFromIntent(intent)

        if (!OPERATION_GATE.tryAcquire(operationId)) {
            reportBusy(operationId, config, uris.size)
            return START_NOT_STICKY
        }

        val cancellation = UploadCancellationToken()
        activeUpload = ActiveUpload(operationId, cancellation)
        val initialStatus = statusStore.begin(
            operationId = operationId,
            serverId = config?.id.orEmpty(),
            serverName = config?.serverName.orEmpty(),
            totalItems = uris.size,
            now = System.currentTimeMillis(),
        )
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MILLIS)

        try {
            ensureNotificationChannel()
            startForeground(NOTIFICATION_ID, buildProgressNotification(initialStatus))
            sendStatusBroadcast(initialStatus)
            worker.execute {
                val terminalStatus = uploadSharedFiles(operationId, config, uris, cancellation)
                runOnMain {
                    finishOperation(operationId, terminalStatus)
                }
            }
        } catch (_: Exception) {
            abortBeforeWorker(operationId, startId)
        }
        return START_NOT_STICKY
    }

    private fun cancelUpload(intent: Intent, startId: Int) {
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
        val active = activeUpload
        if (operationId.isBlank() || active?.operationId != operationId) {
            if (active == null) {
                stopSelf(startId)
            }
            return
        }

        val status = statusStore.requestCancel(operationId, System.currentTimeMillis())
        active.cancellation.cancel()
        if (status != null) {
            publishProgress(status)
        }
    }

    private fun uploadSharedFiles(
        operationId: String,
        config: TransferConfig?,
        uris: List<Uri>,
        cancellation: UploadCancellationToken,
    ): UploadStatus {
        if (config == null) {
            return fail(operationId, UploadErrorCode.INVALID_CONFIG)
        }
        if (uris.isEmpty()) {
            return fail(operationId, UploadErrorCode.NO_ITEMS)
        }

        var completedItems = 0
        return try {
            val items = ShareIntentParser.fromUris(this, uris)
            val client = UploadClient(this, config)
            items.forEachIndexed { index, item ->
                cancellation.throwIfCancelled()
                statusStore.updatePreflight(
                    operationId = operationId,
                    itemName = item.displayName,
                    itemIndex = index + 1,
                    completedItems = completedItems,
                    now = System.currentTimeMillis(),
                )?.let(::publishProgress)

                var lastProgressUpdateAt = 0L
                var lastProgressBucket = -1
                client.upload(
                    item = item,
                    cancellation = cancellation,
                    onPrepared = { totalBytes ->
                        statusStore.updateUploading(
                            operationId = operationId,
                            itemName = item.displayName,
                            itemIndex = index + 1,
                            completedItems = completedItems,
                            bytesSent = 0L,
                            totalBytes = totalBytes,
                            now = System.currentTimeMillis(),
                        )?.let(::publishProgress)
                    },
                    onProgress = { bytesSent, totalBytes ->
                        val now = System.currentTimeMillis()
                        val bucket = progressBucket(bytesSent, totalBytes)
                        val shouldPublish = now - lastProgressUpdateAt >= PROGRESS_UPDATE_INTERVAL_MILLIS ||
                            bucket != lastProgressBucket ||
                            bytesSent >= totalBytes
                        if (shouldPublish) {
                            lastProgressUpdateAt = now
                            lastProgressBucket = bucket
                            statusStore.updateUploading(
                                operationId = operationId,
                                itemName = item.displayName,
                                itemIndex = index + 1,
                                completedItems = completedItems,
                                bytesSent = bytesSent,
                                totalBytes = totalBytes,
                                now = now,
                            )?.let(::publishProgress)
                        }
                    },
                )
                completedItems += 1
                cancellation.throwIfCancelled()
            }

            statusStore.complete(operationId, System.currentTimeMillis())
                ?: terminalAfterRejectedCompletion(operationId, completedItems, cancellation)
        } catch (_: UploadCancelledException) {
            statusStore.cancel(operationId, completedItems, System.currentTimeMillis())
                ?: statusStore.load()
        } catch (error: Exception) {
            if (cancellation.isCancelled) {
                statusStore.cancel(operationId, completedItems, System.currentTimeMillis())
                    ?: statusStore.load()
            } else {
                fail(operationId, UploadFailureClassifier.classify(error))
            }
        }
    }

    private fun terminalAfterRejectedCompletion(
        operationId: String,
        completedItems: Int,
        cancellation: UploadCancellationToken,
    ): UploadStatus {
        val current = statusStore.load()
        return if (cancellation.isCancelled || current.phase == UploadPhase.CANCEL_REQUESTED) {
            statusStore.cancel(operationId, completedItems, System.currentTimeMillis()) ?: current
        } else {
            current
        }
    }

    private fun fail(operationId: String, errorCode: UploadErrorCode): UploadStatus {
        return statusStore.fail(operationId, errorCode, System.currentTimeMillis())
            ?: statusStore.load()
    }

    private fun publishProgress(status: UploadStatus) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildProgressNotification(status),
            )
        }
        sendStatusBroadcast(status)
    }

    private fun finishOperation(operationId: String, status: UploadStatus) {
        val active = activeUpload
        if (active?.operationId == operationId) {
            activeUpload = null
            heartbeatHandler.removeCallbacks(heartbeat)
        }
        OPERATION_GATE.release(operationId)
        try {
            TransferHistoryStore(this).recordUpload(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            val message = messageFor(status)
            runCatching { showResultNotification(status, message) }
            sendStatusBroadcast(status)
            runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        } finally {
            stopSelf()
        }
    }

    private fun abortBeforeWorker(operationId: String, startId: Int) {
        val status = statusStore.fail(
            operationId,
            UploadErrorCode.UNKNOWN,
            System.currentTimeMillis(),
        ) ?: UploadStatus(
            operationId = operationId,
            phase = UploadPhase.ERROR,
            errorCode = UploadErrorCode.UNKNOWN,
        )
        activeUpload = null
        heartbeatHandler.removeCallbacks(heartbeat)
        OPERATION_GATE.release(operationId)
        TransferHistoryStore(this).recordUpload(status)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        sendStatusBroadcast(status)
        runCatching { Toast.makeText(this, messageFor(status), Toast.LENGTH_LONG).show() }
        stopSelf(startId)
    }

    private fun reportBusy(operationId: String, config: TransferConfig?, totalItems: Int) {
        val status = UploadStatusMachine.busy(
            operationId = operationId,
            serverId = config?.id.orEmpty(),
            serverName = config?.serverName.orEmpty(),
            totalItems = totalItems,
        )
        sendStatusBroadcast(status)
        runCatching {
            Toast.makeText(this, messageFor(status), Toast.LENGTH_SHORT).show()
        }
    }

    private fun configFromIntent(intent: Intent): TransferConfig? {
        val config = TransferConfig(
            serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty(),
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
            authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty(),
            certificateSha256 = intent.getStringExtra(EXTRA_CERT_SHA256).orEmpty(),
            id = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty(),
        ).normalized()
        return if (config.isComplete()) config else null
    }

    private fun urisFromIntent(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS).orEmpty()
        }
    }

    private fun ensureNotificationChannel() {
        val textContext = AppLanguageController.localizedContext(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                textContext.getString(R.string.upload_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildProgressNotification(status: UploadStatus): Notification {
        val textContext = AppLanguageController.localizedContext(this)
        val text = when (status.phase) {
            UploadPhase.PREFLIGHT -> textContext.getString(R.string.upload_preparing)
            UploadPhase.UPLOADING -> textContext.getString(
                R.string.upload_progress,
                status.currentItemIndex,
                status.totalItems,
                status.currentItemName,
            )
            UploadPhase.CANCEL_REQUESTED -> textContext.getString(R.string.upload_notification_stopping)
            else -> messageFor(status)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(textContext.getString(R.string.upload_notification_title))
            .setContentText(text)
            .setContentIntent(openMainIntent())
            .setOngoing(status.isActive())
            .setOnlyAlertOnce(true)

        if (status.phase == UploadPhase.UPLOADING && status.totalBytes > 0L) {
            builder.setProgress(PROGRESS_MAX, progressValue(status), false)
        } else if (status.phase == UploadPhase.PREFLIGHT) {
            builder.setProgress(0, 0, true)
        }

        if (status.phase == UploadPhase.PREFLIGHT || status.phase == UploadPhase.UPLOADING) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop),
                    textContext.getString(R.string.upload_notification_cancel),
                    cancelPendingIntent(status.operationId),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun showResultNotification(status: UploadStatus, message: String) {
        val textContext = AppLanguageController.localizedContext(this)
        val title = when (status.phase) {
            UploadPhase.SUCCESS -> textContext.getString(R.string.upload_notification_done)
            UploadPhase.CANCELLED -> textContext.getString(R.string.upload_notification_cancelled)
            else -> textContext.getString(R.string.upload_failed_title)
        }
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(openMainIntent())
                .setAutoCancel(true)
                .build(),
        )
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

    private fun cancelPendingIntent(operationId: String): PendingIntent {
        return PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            createCancelIntent(this, operationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun messageFor(status: UploadStatus): String {
        val textContext = AppLanguageController.localizedContext(this)
        return when (status.phase) {
            UploadPhase.SUCCESS -> textContext.resources.getQuantityString(
                R.plurals.upload_complete,
                status.completedItems,
                status.completedItems,
            )
            UploadPhase.CANCELLED -> textContext.resources.getQuantityString(
                R.plurals.upload_cancelled,
                status.totalItems,
                status.completedItems,
                status.totalItems,
            )
            UploadPhase.BUSY -> textContext.getString(R.string.upload_busy)
            UploadPhase.INTERRUPTED -> textContext.getString(R.string.upload_interrupted)
            UploadPhase.ERROR -> {
                if (status.completedItems > 0) {
                    textContext.resources.getQuantityString(
                        R.plurals.upload_partial_failure,
                        status.totalItems,
                        status.completedItems,
                        status.totalItems,
                    )
                } else {
                    when (status.errorCode) {
                        UploadErrorCode.INVALID_CONFIG -> textContext.getString(R.string.invalid_config)
                        UploadErrorCode.NO_ITEMS -> textContext.getString(R.string.no_pending_share)
                        UploadErrorCode.FILE_UNAVAILABLE -> textContext.getString(R.string.upload_file_unavailable)
                        UploadErrorCode.NETWORK_UNREACHABLE ->
                            textContext.getString(R.string.upload_network_unreachable)
                        UploadErrorCode.TLS_MISMATCH -> textContext.getString(R.string.upload_tls_mismatch)
                        UploadErrorCode.AUTH_FAILED -> textContext.getString(R.string.upload_auth_failed)
                        UploadErrorCode.FILE_TOO_LARGE -> textContext.getString(R.string.upload_file_too_large)
                        UploadErrorCode.SERVER_STORAGE_FULL ->
                            textContext.getString(R.string.upload_server_storage_full)
                        UploadErrorCode.SERVER_REJECTED -> textContext.getString(R.string.upload_server_rejected)
                        else -> textContext.getString(R.string.upload_failed)
                    }
                }
            }
            else -> textContext.getString(R.string.uploading)
        }
    }

    private fun sendStatusBroadcast(status: UploadStatus) {
        sendBroadcast(
            Intent(ACTION_UPLOAD_STATUS_CHANGED)
                .setPackage(packageName)
                .putUploadStatus(status),
            InternalBroadcasts.PERMISSION,
        )
    }

    private fun progressBucket(bytesSent: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) {
            return -1
        }
        return ((bytesSent.coerceIn(0L, totalBytes) * 20L) / totalBytes).toInt()
    }

    private fun progressValue(status: UploadStatus): Int {
        if (status.totalBytes <= 0L) {
            return 0
        }
        return ((status.bytesSent.coerceIn(0L, status.totalBytes) * PROGRESS_MAX) / status.totalBytes)
            .toInt()
    }

    private fun runOnMain(block: () -> Unit) {
        mainExecutor.execute(block)
    }

    private data class ActiveUpload(
        val operationId: String,
        val cancellation: UploadCancellationToken,
    )

    companion object {
        const val ACTION_UPLOAD_STATUS_CHANGED = "io.iaw.lanshare.action.UPLOAD_STATUS_CHANGED"

        private const val ACTION_UPLOAD = "io.iaw.lanshare.action.UPLOAD"
        private const val ACTION_CANCEL_UPLOAD = "io.iaw.lanshare.action.CANCEL_UPLOAD"
        private const val EXTRA_URIS = "io.iaw.lanshare.extra.UPLOAD_URIS"
        private const val EXTRA_OPERATION_ID = "io.iaw.lanshare.extra.UPLOAD_OPERATION_ID"
        private const val EXTRA_SERVER_ID = "io.iaw.lanshare.extra.UPLOAD_SERVER_ID"
        private const val EXTRA_SERVER_NAME = "io.iaw.lanshare.extra.UPLOAD_SERVER_NAME"
        private const val EXTRA_BASE_URL = "io.iaw.lanshare.extra.UPLOAD_BASE_URL"
        private const val EXTRA_AUTH_TOKEN = "io.iaw.lanshare.extra.UPLOAD_AUTH_TOKEN"
        private const val EXTRA_CERT_SHA256 = "io.iaw.lanshare.extra.UPLOAD_CERT_SHA256"
        private const val EXTRA_PHASE = "io.iaw.lanshare.extra.UPLOAD_PHASE"
        private const val EXTRA_CURRENT_ITEM_NAME = "io.iaw.lanshare.extra.UPLOAD_CURRENT_ITEM_NAME"
        private const val EXTRA_CURRENT_ITEM_INDEX = "io.iaw.lanshare.extra.UPLOAD_CURRENT_ITEM_INDEX"
        private const val EXTRA_COMPLETED_ITEMS = "io.iaw.lanshare.extra.UPLOAD_COMPLETED_ITEMS"
        private const val EXTRA_TOTAL_ITEMS = "io.iaw.lanshare.extra.UPLOAD_TOTAL_ITEMS"
        private const val EXTRA_BYTES_SENT = "io.iaw.lanshare.extra.UPLOAD_BYTES_SENT"
        private const val EXTRA_TOTAL_BYTES = "io.iaw.lanshare.extra.UPLOAD_TOTAL_BYTES"
        private const val EXTRA_STARTED_AT = "io.iaw.lanshare.extra.UPLOAD_STARTED_AT"
        private const val EXTRA_COMPLETED_AT = "io.iaw.lanshare.extra.UPLOAD_COMPLETED_AT"
        private const val EXTRA_HEARTBEAT_AT = "io.iaw.lanshare.extra.UPLOAD_HEARTBEAT_AT"
        private const val EXTRA_ERROR_CODE = "io.iaw.lanshare.extra.UPLOAD_ERROR_CODE"
        private const val CHANNEL_ID = "wifishare_upload"
        private const val NOTIFICATION_ID = 3001
        private const val RESULT_NOTIFICATION_ID = 3002
        private const val OPEN_MAIN_REQUEST_CODE = 20
        private const val CANCEL_REQUEST_CODE = 21
        private const val PROGRESS_MAX = 1_000
        private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 500L
        private const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        private val OPERATION_GATE = UploadOperationGate()

        fun createIntent(
            context: Context,
            operationId: String,
            config: TransferConfig,
            items: List<SharedItem>,
        ): Intent {
            val normalized = config.normalized()
            val uris = items.map { it.uri }
            return Intent(context, UploadService::class.java).apply {
                action = ACTION_UPLOAD
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                putExtra(EXTRA_OPERATION_ID, operationId)
                putExtra(EXTRA_SERVER_ID, normalized.id)
                putExtra(EXTRA_SERVER_NAME, normalized.serverName)
                putExtra(EXTRA_BASE_URL, normalized.baseUrl)
                putExtra(EXTRA_AUTH_TOKEN, normalized.authToken)
                putExtra(EXTRA_CERT_SHA256, normalized.certificateSha256)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.isNotEmpty()) {
                    clipData = ClipData.newRawUri("WifiShare upload", uris.first()).also { clip ->
                        uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                    }
                }
            }
        }

        fun createCancelIntent(context: Context, operationId: String): Intent {
            return Intent(context, UploadService::class.java).apply {
                action = ACTION_CANCEL_UPLOAD
                putExtra(EXTRA_OPERATION_ID, operationId)
                data = Uri.parse("wifishare://upload/$operationId/cancel")
            }
        }

        fun statusFromIntent(intent: Intent): UploadStatus? {
            val operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
            val phase = intent.getStringExtra(EXTRA_PHASE)
                ?.let { raw -> UploadPhase.entries.firstOrNull { it.name == raw } }
                ?: return null
            if (operationId.isBlank()) {
                return null
            }
            return UploadStatus(
                operationId = operationId,
                serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty(),
                serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty(),
                phase = phase,
                currentItemName = intent.getStringExtra(EXTRA_CURRENT_ITEM_NAME).orEmpty(),
                currentItemIndex = intent.getIntExtra(EXTRA_CURRENT_ITEM_INDEX, 0),
                completedItems = intent.getIntExtra(EXTRA_COMPLETED_ITEMS, 0),
                totalItems = intent.getIntExtra(EXTRA_TOTAL_ITEMS, 0),
                bytesSent = intent.getLongExtra(EXTRA_BYTES_SENT, 0L),
                totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L),
                startedAtMillis = intent.getLongExtra(EXTRA_STARTED_AT, 0L),
                completedAtMillis = intent.getLongExtra(EXTRA_COMPLETED_AT, 0L),
                heartbeatAtMillis = intent.getLongExtra(EXTRA_HEARTBEAT_AT, 0L),
                errorCode = intent.getStringExtra(EXTRA_ERROR_CODE)
                    ?.let { raw -> UploadErrorCode.entries.firstOrNull { it.name == raw } }
                    ?: UploadErrorCode.NONE,
            )
        }

        private fun Intent.putUploadStatus(status: UploadStatus): Intent {
            return putExtra(EXTRA_OPERATION_ID, status.operationId)
                .putExtra(EXTRA_SERVER_ID, status.serverId)
                .putExtra(EXTRA_SERVER_NAME, status.serverName)
                .putExtra(EXTRA_PHASE, status.phase.name)
                .putExtra(EXTRA_CURRENT_ITEM_NAME, status.currentItemName)
                .putExtra(EXTRA_CURRENT_ITEM_INDEX, status.currentItemIndex)
                .putExtra(EXTRA_COMPLETED_ITEMS, status.completedItems)
                .putExtra(EXTRA_TOTAL_ITEMS, status.totalItems)
                .putExtra(EXTRA_BYTES_SENT, status.bytesSent)
                .putExtra(EXTRA_TOTAL_BYTES, status.totalBytes)
                .putExtra(EXTRA_STARTED_AT, status.startedAtMillis)
                .putExtra(EXTRA_COMPLETED_AT, status.completedAtMillis)
                .putExtra(EXTRA_HEARTBEAT_AT, status.heartbeatAtMillis)
                .putExtra(EXTRA_ERROR_CODE, status.errorCode.name)
        }
    }
}
