package io.iaw.lanshare

internal enum class WidgetSizeClass {
    COMPACT,
    EXPANDED,
}

internal enum class WidgetThemePolicy {
    FOLLOW_SYSTEM,
    FORCE_LIGHT,
    FORCE_DARK,
}

internal object WidgetThemeResolver {
    fun resolve(mode: ThemeModeSetting): WidgetThemePolicy {
        return when (mode) {
            ThemeModeSetting.SYSTEM -> WidgetThemePolicy.FOLLOW_SYSTEM
            ThemeModeSetting.LIGHT -> WidgetThemePolicy.FORCE_LIGHT
            ThemeModeSetting.DARK -> WidgetThemePolicy.FORCE_DARK
        }
    }
}

internal object WidgetSizeClassResolver {
    fun resolve(minWidthDp: Int): WidgetSizeClass {
        return if (minWidthDp < 250) WidgetSizeClass.COMPACT else WidgetSizeClass.EXPANDED
    }
}

internal enum class WidgetPendingIntentKind(val code: Int, val path: String) {
    SERVER_PICKER(1, "server"),
    RECEIVE(2, "receive"),
    STATUS_REFRESH(3, "status-refresh"),
}

internal object WidgetPendingIntentIdentity {
    fun requestCode(appWidgetId: Int, kind: WidgetPendingIntentKind): Int {
        return appWidgetId * 37 + kind.code
    }

    fun dataUri(
        appWidgetId: Int,
        kind: WidgetPendingIntentKind,
    ): String {
        return "wifishare://widget/$appWidgetId/${kind.path}"
    }
}
