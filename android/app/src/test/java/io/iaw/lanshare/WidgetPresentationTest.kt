package io.iaw.lanshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetPresentationTest {
    @Test
    fun widgetThemePolicyPreservesSystemAndExplicitOverrides() {
        assertEquals(WidgetThemePolicy.FOLLOW_SYSTEM, WidgetThemeResolver.resolve(ThemeModeSetting.SYSTEM))
        assertEquals(WidgetThemePolicy.FORCE_LIGHT, WidgetThemeResolver.resolve(ThemeModeSetting.LIGHT))
        assertEquals(WidgetThemePolicy.FORCE_DARK, WidgetThemeResolver.resolve(ThemeModeSetting.DARK))
    }

    @Test
    fun sizeResolverSelectsTwoByOneAndFourByOneLayoutsAtBoundary() {
        assertEquals(WidgetSizeClass.COMPACT, WidgetSizeClassResolver.resolve(249))
        assertEquals(WidgetSizeClass.EXPANDED, WidgetSizeClassResolver.resolve(250))
    }

    @Test
    fun pendingIntentIdentityIsUniquePerWidgetAndAction() {
        val firstReceive = WidgetPendingIntentIdentity.requestCode(10, WidgetPendingIntentKind.RECEIVE)
        val secondReceive = WidgetPendingIntentIdentity.requestCode(11, WidgetPendingIntentKind.RECEIVE)
        val firstPicker = WidgetPendingIntentIdentity.requestCode(10, WidgetPendingIntentKind.SERVER_PICKER)

        assertNotEquals(firstReceive, secondReceive)
        assertNotEquals(firstReceive, firstPicker)
        assertNotEquals(
            WidgetPendingIntentIdentity.dataUri(10, WidgetPendingIntentKind.RECEIVE),
            WidgetPendingIntentIdentity.dataUri(11, WidgetPendingIntentKind.RECEIVE),
        )
    }

    @Test
    fun widgetActionsNeverEncodeTheActiveServer() {
        assertEquals(
            "wifishare://widget/10/receive",
            WidgetPendingIntentIdentity.dataUri(10, WidgetPendingIntentKind.RECEIVE),
        )
        assertEquals(
            "wifishare://widget/10/server",
            WidgetPendingIntentIdentity.dataUri(10, WidgetPendingIntentKind.SERVER_PICKER),
        )
    }
}
