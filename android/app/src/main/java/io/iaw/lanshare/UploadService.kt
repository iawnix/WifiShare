package io.iaw.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import java.util.concurrent.Executors

class UploadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = getString(R.string.upload_notification_title),
                text = getString(R.string.upload_notification_body),
                ongoing = true,
            ),
        )

        worker.execute {
            val message = uploadSharedFiles(intent)
            runOnMain {
                stopForeground(STOP_FOREGROUND_REMOVE)
                showResultNotification(message)
                sendBroadcast(
                    Intent(ACTION_UPLOAD_FINISHED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_RESULT_MESSAGE, message),
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun uploadSharedFiles(intent: Intent?): String {
        val config = configFromIntent(intent)
            ?: return getString(R.string.invalid_config)
        val uris = urisFromIntent(intent)
        if (uris.isEmpty()) {
            return getString(R.string.no_pending_share)
        }

        val items = ShareIntentParser.fromUris(this, uris)
        val client = UploadClient(this, config)
        val failures = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            try {
                updateProgress(index + 1, items.size, item.displayName)
                client.upload(item)
            } catch (exc: Exception) {
                failures += "${item.displayName}: ${exc.message}"
            }
        }

        return if (failures.isEmpty()) {
            getString(R.string.upload_complete, items.size)
        } else {
            failures.joinToString(separator = "\n")
        }
    }

    private fun configFromIntent(intent: Intent?): TransferConfig? {
        val config = TransferConfig(
            serverName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty(),
            baseUrl = intent?.getStringExtra(EXTRA_BASE_URL).orEmpty(),
            authToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty(),
            certificateSha256 = intent?.getStringExtra(EXTRA_CERT_SHA256).orEmpty(),
        ).normalized()
        return if (config.isComplete()) config else null
    }

    private fun urisFromIntent(intent: Intent?): List<Uri> {
        if (intent == null) {
            return emptyList()
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS).orEmpty()
        }
    }

    private fun updateProgress(index: Int, total: Int, filename: String) {
        val notification = buildNotification(
            title = getString(R.string.upload_notification_title),
            text = getString(R.string.upload_progress, index, total, filename),
            ongoing = true,
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.upload_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun showResultNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            buildNotification(
                title = getString(R.string.upload_notification_done),
                text = message,
                ongoing = false,
            ),
        )
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openMainIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun openMainIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun runOnMain(block: () -> Unit) {
        mainExecutor.execute(block)
    }

    companion object {
        const val ACTION_UPLOAD_FINISHED = "io.iaw.lanshare.action.UPLOAD_FINISHED"
        const val EXTRA_RESULT_MESSAGE = "io.iaw.lanshare.extra.RESULT_MESSAGE"

        private const val CHANNEL_ID = "wifishare_upload"
        private const val NOTIFICATION_ID = 3001
        private const val RESULT_NOTIFICATION_ID = 3002
        private const val EXTRA_URIS = "io.iaw.lanshare.extra.URIS"
        private const val EXTRA_SERVER_NAME = "io.iaw.lanshare.extra.SERVER_NAME"
        private const val EXTRA_BASE_URL = "io.iaw.lanshare.extra.BASE_URL"
        private const val EXTRA_AUTH_TOKEN = "io.iaw.lanshare.extra.AUTH_TOKEN"
        private const val EXTRA_CERT_SHA256 = "io.iaw.lanshare.extra.CERT_SHA256"

        fun createIntent(context: Context, config: TransferConfig, items: List<SharedItem>): Intent {
            val normalized = config.normalized()
            return Intent(context, UploadService::class.java).apply {
                action = "io.iaw.lanshare.action.UPLOAD"
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri }))
                putExtra(EXTRA_SERVER_NAME, normalized.serverName)
                putExtra(EXTRA_BASE_URL, normalized.baseUrl)
                putExtra(EXTRA_AUTH_TOKEN, normalized.authToken)
                putExtra(EXTRA_CERT_SHA256, normalized.certificateSha256)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
