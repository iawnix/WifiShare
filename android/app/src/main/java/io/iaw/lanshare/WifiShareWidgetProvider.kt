package io.iaw.lanshare

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class WifiShareWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_STATUS) {
            updateAllWidgets(context)
        }
    }

    companion object {
        private const val ACTION_REFRESH_STATUS = "io.iaw.lanshare.action.REFRESH_WIDGET_STATUS"
        private const val STATUS_REFRESH_REQUEST_CODE = 7001

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WifiShareWidgetProvider::class.java))
            updateWidgets(context, manager, ids)
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            updateWidgets(
                context,
                AppWidgetManager.getInstance(context),
                intArrayOf(appWidgetId),
            )
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            val renderingContext = AppLanguageController.localizedContext(context)
            val settingsStore = SettingsStore(context)
            val themePolicy = WidgetThemeResolver.resolve(settingsStore.loadThemeMode())
            val activeConfig = settingsStore.loadActive()
            val status = TransferStatusStore(context).load()

            appWidgetIds.forEach { appWidgetId ->
                val options = manager.getAppWidgetOptions(appWidgetId)
                val views = WifiShareWidgetRenderer.render(
                    context = renderingContext,
                    appWidgetId = appWidgetId,
                    options = options,
                    config = activeConfig,
                    globalStatus = status,
                    themePolicy = themePolicy,
                )
                manager.updateAppWidget(appWidgetId, views)
            }
            scheduleStatusRefresh(context, status)
        }

        private fun scheduleStatusRefresh(context: Context, status: TransferStatus) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val pendingIntent = statusRefreshIntent(context)
            alarmManager.cancel(pendingIntent)
            val now = System.currentTimeMillis()
            val delay = TransferStatusMachine.nextRefreshDelayMillis(status, now) ?: return
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + delay, pendingIntent)
        }

        private fun statusRefreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, WifiShareWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_STATUS
                data = Uri.parse(
                    WidgetPendingIntentIdentity.dataUri(
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                        WidgetPendingIntentKind.STATUS_REFRESH,
                    ),
                )
            }
            return PendingIntent.getBroadcast(
                context,
                STATUS_REFRESH_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
