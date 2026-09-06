package io.iaw.lanshare

import android.annotation.TargetApi
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

internal object WifiShareWidgetRenderer {
    private const val PROGRESS_MAX = 1_000

    fun render(
        context: Context,
        appWidgetId: Int,
        options: Bundle,
        config: TransferConfig?,
        globalStatus: TransferStatus,
        themePolicy: WidgetThemePolicy,
    ): RemoteViews {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            responsiveViews(context, appWidgetId, config, globalStatus, themePolicy)
        } else {
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            buildViews(
                context = context,
                layout = layoutFor(WidgetSizeClassResolver.resolve(minWidth)),
                appWidgetId = appWidgetId,
                config = config,
                globalStatus = globalStatus,
                themePolicy = themePolicy,
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun responsiveViews(
        context: Context,
        appWidgetId: Int,
        config: TransferConfig?,
        globalStatus: TransferStatus,
        themePolicy: WidgetThemePolicy,
    ): RemoteViews {
        return RemoteViews(
            linkedMapOf(
                SizeF(110f, 56f) to buildViews(
                    context,
                    R.layout.widget_wifishare_compact,
                    appWidgetId,
                    config,
                    globalStatus,
                    themePolicy,
                ),
                SizeF(250f, 56f) to buildViews(
                    context,
                    R.layout.widget_wifishare_expanded,
                    appWidgetId,
                    config,
                    globalStatus,
                    themePolicy,
                ),
            ),
        )
    }

    private fun buildViews(
        context: Context,
        layout: Int,
        appWidgetId: Int,
        config: TransferConfig?,
        globalStatus: TransferStatus,
        themePolicy: WidgetThemePolicy,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val status = if (config == null) {
            TransferStatus(phase = TransferPhase.UNCONFIGURED)
        } else {
            TransferStatusMachine.forServer(globalStatus, config.id)
        }
        val presentation = presentation(context, config, status)
        val expanded = layout == R.layout.widget_wifishare_expanded

        views.setTextViewText(R.id.widgetServerName, presentation.serverName)
        views.setTextViewText(R.id.widgetStatusText, presentation.subtitle)
        views.setViewVisibility(R.id.widgetStatusText, View.VISIBLE)
        views.setViewVisibility(R.id.widgetDetailText, View.GONE)
        views.setImageViewResource(R.id.widgetReceiveIcon, presentation.actionIcon)
        views.setTextViewText(R.id.widgetActionLabel, presentation.actionLabel)
        views.setViewVisibility(
            R.id.widgetActionLabel,
            if (expanded && presentation.actionLabel.isNotBlank()) View.VISIBLE else View.GONE,
        )
        views.setInt(R.id.widgetStatusDot, "setBackgroundResource", presentation.statusDot)
        views.setContentDescription(R.id.widgetReceiveButton, presentation.actionDescription)
        views.setContentDescription(
            android.R.id.background,
            context.getString(R.string.open_server_picker),
        )

        val showProgress = status.phase == TransferPhase.CHECKING || status.phase == TransferPhase.RECEIVING
        val determinate = status.phase == TransferPhase.RECEIVING && status.totalBytes > 0L
        views.setViewVisibility(R.id.widgetReceiveProgress, if (showProgress) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widgetProgressText, View.GONE)
        if (showProgress) {
            views.setProgressBar(
                R.id.widgetReceiveProgress,
                PROGRESS_MAX,
                if (determinate) progressValue(status) else 0,
                !determinate,
            )
        }
        if (determinate) {
            views.setTextViewText(
                R.id.widgetProgressText,
                String.format(Locale.getDefault(), "%d%%", progressPercent(status)),
            )
        }

        views.setOnClickPendingIntent(
            android.R.id.background,
            serverPickerIntent(context, appWidgetId),
        )
        val actionIntent = when {
            config == null -> serverPickerIntent(context, appWidgetId)
            status.isActive() || status.phase == TransferPhase.BUSY -> null
            else -> receiveIntent(context, appWidgetId)
        }
        views.setOnClickPendingIntent(R.id.widgetReceiveButton, actionIntent)
        views.setBoolean(R.id.widgetReceiveButton, "setEnabled", actionIntent != null)
        views.setFloat(R.id.widgetReceiveButton, "setAlpha", if (actionIntent == null) 0.52f else 1f)
        applyExplicitTheme(context, views, themePolicy, expanded)
        return views
    }

    private fun applyExplicitTheme(
        context: Context,
        views: RemoteViews,
        policy: WidgetThemePolicy,
        expanded: Boolean,
    ) {
        val style = when (policy) {
            WidgetThemePolicy.FOLLOW_SYSTEM -> return
            WidgetThemePolicy.FORCE_LIGHT -> WidgetThemeStyle(
                surface = R.drawable.widget_surface_forced_light,
                actionSurface = R.drawable.widget_action_surface_forced_light,
                primaryText = context.getColor(R.color.widget_forced_light_text_primary),
                secondaryText = context.getColor(R.color.widget_forced_light_text_secondary),
                accent = context.getColor(R.color.widget_forced_light_accent),
                actionTint = context.getColor(R.color.widget_forced_light_on_accent),
                progressTrack = context.getColor(R.color.widget_forced_light_progress_track),
            )
            WidgetThemePolicy.FORCE_DARK -> WidgetThemeStyle(
                surface = R.drawable.widget_surface_forced_dark,
                actionSurface = R.drawable.widget_action_surface_forced_dark,
                primaryText = context.getColor(R.color.widget_forced_dark_text_primary),
                secondaryText = context.getColor(R.color.widget_forced_dark_text_secondary),
                accent = context.getColor(R.color.widget_forced_dark_accent),
                actionTint = context.getColor(R.color.widget_forced_dark_on_accent),
                progressTrack = context.getColor(R.color.widget_forced_dark_progress_track),
            )
        }
        views.setInt(android.R.id.background, "setBackgroundResource", style.surface)
        views.setInt(R.id.widgetReceiveButton, "setBackgroundResource", style.actionSurface)
        views.setTextColor(R.id.widgetServerName, style.primaryText)
        views.setTextColor(R.id.widgetStatusText, style.secondaryText)
        views.setTextColor(R.id.widgetActionLabel, style.actionTint)
        views.setTextColor(R.id.widgetProgressText, style.primaryText)
        views.setInt(R.id.widgetReceiveIcon, "setColorFilter", style.actionTint)
        if (expanded) {
            views.setInt(R.id.widgetDeviceIcon, "setColorFilter", style.accent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyProgressTheme(views, style)
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun applyProgressTheme(views: RemoteViews, style: WidgetThemeStyle) {
        views.setColorStateList(
            R.id.widgetReceiveProgress,
            "setProgressTintList",
            ColorStateList.valueOf(style.accent),
        )
        views.setColorStateList(
            R.id.widgetReceiveProgress,
            "setIndeterminateTintList",
            ColorStateList.valueOf(style.accent),
        )
        views.setColorStateList(
            R.id.widgetReceiveProgress,
            "setProgressBackgroundTintList",
            ColorStateList.valueOf(style.progressTrack),
        )
    }

    private fun presentation(
        context: Context,
        config: TransferConfig?,
        status: TransferStatus,
    ): WidgetPresentation {
        if (config == null) {
            return WidgetPresentation(
                serverName = context.getString(R.string.receiver_missing),
                subtitle = context.getString(R.string.widget_choose_server),
                actionDescription = context.getString(R.string.widget_configure_description),
                actionLabel = context.getString(R.string.widget_setup_short),
                actionIcon = R.drawable.ic_settings,
                statusDot = R.drawable.widget_dot_muted,
            )
        }

        val subtitle = when (status.phase) {
            TransferPhase.IDLE -> endpointLabel(config.baseUrl)
            TransferPhase.CHECKING -> context.getString(R.string.widget_checking)
            TransferPhase.RECEIVING -> {
                val percent = if (status.totalBytes > 0L) "${progressPercent(status)}%" else ""
                listOf(percent, status.currentItemName.ifBlank { context.getString(R.string.widget_receiving) })
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            }
            TransferPhase.SUCCESS -> context.resources.getQuantityString(
                R.plurals.widget_received_count,
                status.completedItems,
                status.completedItems,
            )
            TransferPhase.EMPTY -> context.getString(R.string.widget_queue_empty)
            TransferPhase.BUSY -> context.getString(R.string.widget_busy)
            TransferPhase.INTERRUPTED -> context.getString(R.string.widget_interrupted)
            TransferPhase.ERROR -> errorText(context, status.errorCode, status.completedItems)
            else -> endpointLabel(config.baseUrl)
        }
        val actionDescription = when (status.phase) {
            TransferPhase.CHECKING,
            TransferPhase.RECEIVING,
            -> context.getString(R.string.widget_receiving)
            TransferPhase.BUSY -> context.getString(R.string.widget_busy_action)
            TransferPhase.ERROR,
            TransferPhase.INTERRUPTED,
            -> context.getString(R.string.widget_retry)
            else -> context.getString(R.string.widget_receive)
        }
        val actionLabel = when (status.phase) {
            TransferPhase.CHECKING,
            TransferPhase.RECEIVING,
            TransferPhase.BUSY,
            -> ""
            TransferPhase.ERROR,
            TransferPhase.INTERRUPTED,
            -> context.getString(R.string.widget_retry)
            else -> context.getString(R.string.widget_receive_short)
        }
        val statusDot = when (status.phase) {
            TransferPhase.SUCCESS -> R.drawable.widget_dot_success
            TransferPhase.ERROR,
            TransferPhase.INTERRUPTED,
            -> R.drawable.widget_dot_error
            TransferPhase.EMPTY,
            TransferPhase.BUSY,
            -> R.drawable.widget_dot_muted
            else -> R.drawable.widget_dot_accent
        }
        return WidgetPresentation(
            serverName = config.serverName,
            subtitle = subtitle,
            actionDescription = actionDescription,
            actionLabel = actionLabel,
            actionIcon = R.drawable.ic_download,
            statusDot = statusDot,
        )
    }

    private fun errorText(context: Context, code: TransferErrorCode, completedItems: Int): String {
        return when (code) {
            TransferErrorCode.NO_CONFIG -> context.getString(R.string.widget_reconfigure)
            TransferErrorCode.NETWORK_UNREACHABLE -> context.getString(R.string.widget_error_network)
            TransferErrorCode.TLS_MISMATCH -> context.getString(R.string.widget_error_tls)
            TransferErrorCode.AUTH_FAILED -> context.getString(R.string.widget_error_auth)
            TransferErrorCode.STORAGE_FAILED -> context.getString(R.string.widget_error_storage)
            TransferErrorCode.PARTIAL_FAILURE -> context.resources.getQuantityString(
                R.plurals.widget_error_partial,
                completedItems,
                completedItems,
            )
            else -> context.getString(R.string.widget_error_unknown)
        }
    }

    private fun serverPickerIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, ServerPickerActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            data = Uri.parse(
                WidgetPendingIntentIdentity.dataUri(appWidgetId, WidgetPendingIntentKind.SERVER_PICKER),
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            context,
            WidgetPendingIntentIdentity.requestCode(appWidgetId, WidgetPendingIntentKind.SERVER_PICKER),
            intent,
            pendingIntentFlags(),
        )
    }

    private fun receiveIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = ReceiveQueueService.createIntent(context, appWidgetId)
        return PendingIntent.getForegroundService(
            context,
            WidgetPendingIntentIdentity.requestCode(appWidgetId, WidgetPendingIntentKind.RECEIVE),
            intent,
            pendingIntentFlags(),
        )
    }

    private fun endpointLabel(baseUrl: String): String {
        val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
        val host = uri?.host ?: return baseUrl
        val port = uri.port
        return if (port > 0 && port != 443) "$host:$port" else host
    }

    private fun progressValue(status: TransferStatus): Int {
        return ((status.bytesReceived.coerceIn(0L, status.totalBytes) * PROGRESS_MAX) / status.totalBytes)
            .toInt()
    }

    private fun progressPercent(status: TransferStatus): Int {
        if (status.totalBytes <= 0L) {
            return 0
        }
        return ((status.bytesReceived.coerceIn(0L, status.totalBytes) * 100L) / status.totalBytes).toInt()
    }

    private fun layoutFor(sizeClass: WidgetSizeClass): Int {
        return when (sizeClass) {
            WidgetSizeClass.COMPACT -> R.layout.widget_wifishare_compact
            WidgetSizeClass.EXPANDED -> R.layout.widget_wifishare_expanded
        }
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private data class WidgetPresentation(
        val serverName: CharSequence,
        val subtitle: CharSequence,
        val actionDescription: CharSequence,
        val actionLabel: CharSequence,
        val actionIcon: Int,
        val statusDot: Int,
    )

    private data class WidgetThemeStyle(
        val surface: Int,
        val actionSurface: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val accent: Int,
        val actionTint: Int,
        val progressTrack: Int,
    )
}
