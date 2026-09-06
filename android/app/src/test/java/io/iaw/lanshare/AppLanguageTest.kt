package io.iaw.lanshare

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun recognizesSupportedLanguageTags() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-Hans-CN"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US"))
    }

    @Test
    fun emptyAndUnsupportedTagsFollowTheSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags("fr-FR"))
    }
}
