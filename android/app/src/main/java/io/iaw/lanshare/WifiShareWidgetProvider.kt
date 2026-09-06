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
import java.util.concurrent.Executors

class WifiShareWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateFromBroadcast(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateFromBroadcast(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_STATUS) {
            val manager = AppWidgetManager.getInstance(context)
            updateFromBroadcast(context, manager, widgetIds(context, manager))
        }
    }

    private fun updateFromBroadcast(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val result = goAsync()
        WIDGET_WORKER.execute {
            try {
                safelyUpdate(context.applicationContext, manager, ids)
            } finally {
                result?.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH_STATUS = "io.iaw.lanshare.action.REFRESH_WIDGET_STATUS"
        private const val STATUS_REFRESH_REQUEST_CODE = 7001
        private val WIDGET_WORKER = Executors.newSingleThreadExecutor()

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val appContext = context.applicationContext
            WIDGET_WORKER.execute {
                try {
                    safelyUpdate(appContext, manager, widgetIds(appContext, manager))
                } catch (error: RuntimeException) {
                    CrashDiagnostics.record(appContext, error)
                }
            }
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            val appContext = context.applicationContext
            WIDGET_WORKER.execute {
                safelyUpdate(appContext, AppWidgetManager.getInstance(appContext), intArrayOf(appWidgetId))
            }
        }

        private fun widgetIds(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, WifiShareWidgetProvider::class.java))

        private fun safelyUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
            try {
                if (ids.isNotEmpty()) updateWidgets(context, manager, ids)
            } catch (error: RuntimeException) {
                // A launcher/binder failure must not terminate an active transfer in this process.
                CrashDiagnostics.record(context, error)
            }
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
