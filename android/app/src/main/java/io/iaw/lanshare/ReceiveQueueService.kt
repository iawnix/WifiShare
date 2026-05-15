package io.iaw.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import java.util.concurrent.Executors

class ReceiveQueueService : Service() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = getString(R.string.receive_notification_title),
                text = getString(R.string.receive_notification_body),
                ongoing = true,
            ),
        )

        worker.execute {
            val message = receiveQueuedFiles()
            runOnMain {
                stopForeground(STOP_FOREGROUND_REMOVE)
                showResultNotification(message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                WifiShareWidgetProvider.updateAllWidgets(this)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun receiveQueuedFiles(): String {
        val config = SettingsStore(this).loadActive()
            ?: return getString(R.string.invalid_config)
        return try {
            val count = DownloadClient(this, config).fetchAllPending()
            if (count > 0) {
                getString(R.string.download_complete, count)
            } else {
                getString(R.string.no_phone_queue)
            }
        } catch (exc: Exception) {
            exc.message ?: getString(R.string.receive_failed)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.receive_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun showResultNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            RESULT_NOTIFICATION_ID,
            buildNotification(
                title = getString(R.string.receive_notification_done),
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
        return PendingIntent.getActivity(this, 10, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun runOnMain(block: () -> Unit) {
        mainExecutor.execute(block)
    }

    private companion object {
        private const val CHANNEL_ID = "wifishare_receive"
        private const val NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
    }
}
