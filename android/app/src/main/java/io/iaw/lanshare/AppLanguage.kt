package io.iaw.lanshare

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat

enum class AppLanguage(
    val languageTags: String,
    @StringRes val labelResource: Int,
) {
    SYSTEM("", R.string.language_follow_system),
    SIMPLIFIED_CHINESE("zh-CN", R.string.language_simplified_chinese),
    ENGLISH("en", R.string.language_english),
    ;

    companion object {
        fun fromLanguageTags(tags: String?): AppLanguage {
            val primary = tags.orEmpty().substringBefore(',').trim().lowercase()
            return when {
                primary == "zh" || primary.startsWith("zh-") -> SIMPLIFIED_CHINESE
                primary == "en" || primary.startsWith("en-") -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}

object AppLanguageController {
    fun current(context: Context): AppLanguage {
        val locales = LocaleManagerCompat.getApplicationLocales(context)
        return AppLanguage.fromLanguageTags(locales.toLanguageTags())
    }

    fun apply(language: AppLanguage) {
        val locales = LocaleListCompat.forLanguageTags(language.languageTags)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            return context
        }
        val locales = LocaleManagerCompat.getApplicationLocales(context)
        if (locales.isEmpty) {
            return context
        }
        val configuration = Configuration(context.resources.configuration)
        ConfigurationCompat.setLocales(configuration, locales)
        return context.createConfigurationContext(configuration)
    }
}
