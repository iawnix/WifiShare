package io.iaw.lanshare

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast

class WifiShareWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_NEXT_SERVER) {
            val next = activateNextServer(context)
            if (next != null) {
                Toast.makeText(context, context.getString(R.string.server_switched, next.serverName), Toast.LENGTH_SHORT).show()
            }
            updateAllWidgets(context)
        }
        if (intent.action == ACTION_RECEIVE_QUEUE) {
            try {
                val serviceIntent = Intent(context, ReceiveQueueService::class.java).apply {
                    action = ACTION_RECEIVE_QUEUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (exc: Exception) {
                val message = exc.message ?: context.getString(R.string.receive_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val ACTION_NEXT_SERVER = "io.iaw.lanshare.action.NEXT_SERVER"
        private const val ACTION_RECEIVE_QUEUE = "io.iaw.lanshare.action.WIDGET_RECEIVE_QUEUE"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WifiShareWidgetProvider::class.java))
            ids.forEach { appWidgetId ->
                updateWidget(context, manager, appWidgetId)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val store = SettingsStore(context)
            val profiles = store.loadAll()
            val active = store.loadActive()
            val receiving = store.isWidgetReceiving()
            val views = RemoteViews(context.packageName, R.layout.widget_wifishare)

            views.setTextViewText(R.id.widgetServerName, active?.serverName ?: context.getString(R.string.receiver_missing))
            views.setTextViewText(R.id.widgetServerUrl, active?.baseUrl ?: context.getString(R.string.receiver_url_empty))
            views.setTextViewText(
                R.id.widgetModeLabel,
                if (receiving) context.getString(R.string.widget_receiving) else context.getString(R.string.widget_receive_idle),
            )
            views.setViewVisibility(R.id.widgetReceiveProgress, if (receiving) View.VISIBLE else View.GONE)
            views.setTextViewText(
                R.id.widgetSwitchButton,
                when {
                    profiles.isEmpty() -> context.getString(R.string.widget_open_settings)
                    profiles.size == 1 -> context.getString(R.string.widget_single_server)
                    else -> context.getString(R.string.widget_switch_server)
                },
            )

            views.setOnClickPendingIntent(R.id.widgetRoot, openMainIntent(context))
            views.setOnClickPendingIntent(
                R.id.widgetSwitchButton,
                if (profiles.size > 1) switchServerIntent(context) else openSettingsIntent(context),
            )
            views.setOnClickPendingIntent(
                R.id.widgetReceiveButton,
                when {
                    receiving -> openMainIntent(context)
                    active != null -> receiveIntent(context)
                    else -> openSettingsIntent(context)
                },
            )
            views.setTextViewText(
                R.id.widgetReceiveButton,
                if (receiving) context.getString(R.string.widget_receiving) else context.getString(R.string.widget_receive),
            )

            manager.updateAppWidget(widgetId, views)
        }

        private fun activateNextServer(context: Context): TransferConfig? {
            val store = SettingsStore(context)
            val profiles = store.loadAll()
            if (profiles.isEmpty()) {
                return null
            }
            val activeKey = store.loadActive()?.profileKey()
            val activeIndex = profiles.indexOfFirst { it.profileKey() == activeKey }.takeIf { it >= 0 } ?: 0
            val next = profiles[(activeIndex + 1) % profiles.size]
            return if (store.setActive(next)) next else null
        }

        private fun switchServerIntent(context: Context): PendingIntent {
            val intent = Intent(context, WifiShareWidgetProvider::class.java).apply {
                action = ACTION_NEXT_SERVER
            }
            return PendingIntent.getBroadcast(context, 1, intent, pendingIntentFlags())
        }

        private fun receiveIntent(context: Context): PendingIntent {
            val intent = Intent(context, WifiShareWidgetProvider::class.java).apply {
                action = ACTION_RECEIVE_QUEUE
            }
            return PendingIntent.getBroadcast(context, 2, intent, pendingIntentFlags())
        }

        private fun openMainIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, 3, intent, pendingIntentFlags())
        }

        private fun openSettingsIntent(context: Context): PendingIntent {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, 4, intent, pendingIntentFlags())
        }

        private fun pendingIntentFlags(): Int {
            return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
    }
}
