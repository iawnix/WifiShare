package io.iaw.lanshare

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    @Test
    fun systemModeResolvesFromUiModeNightMask() {
        assertEquals(
            ResolvedTheme.DARK,
            AppTheme.resolve(ThemeModeSetting.SYSTEM, Configuration.UI_MODE_NIGHT_YES),
        )
        assertEquals(
            ResolvedTheme.LIGHT,
            AppTheme.resolve(ThemeModeSetting.SYSTEM, Configuration.UI_MODE_NIGHT_NO),
        )
    }

    @Test
    fun explicitThemeIgnoresSystemNightMask() {
        assertEquals(
            ResolvedTheme.LIGHT,
            AppTheme.resolve(ThemeModeSetting.LIGHT, Configuration.UI_MODE_NIGHT_YES),
        )
        assertEquals(
            ResolvedTheme.DARK,
            AppTheme.resolve(ThemeModeSetting.DARK, Configuration.UI_MODE_NIGHT_NO),
        )
    }
}
