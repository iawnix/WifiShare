package io.iaw.lanshare

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.TextViewCompat

enum class ThemeModeSetting {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun appCompatNightMode(): Int = when (this) {
        SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }
}

enum class ResolvedTheme {
    LIGHT,
    DARK,
}

data class ThemePalette(
    val mode: ResolvedTheme,
    val backgroundTop: Int,
    val backgroundMiddle: Int,
    val backgroundAccent: Int,
    val backgroundBottom: Int,
    val cardTop: Int,
    val card: Int,
    val cardBottom: Int,
    val cardStroke: Int,
    val glassHighlight: Int,
    val input: Int,
    val iconButton: Int,
    val iconButtonPressed: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val accentSecondary: Int,
    val accentPressed: Int,
    val secondary: Int,
    val secondaryPressed: Int,
    val danger: Int,
    val success: Int,
    val pill: Int,
    val pillStroke: Int,
    val shadow: Int,
) {
    val isDark: Boolean get() = mode == ResolvedTheme.DARK
}

object AppTheme {
    fun applyMode(mode: ThemeModeSetting) {
        val nightMode = mode.appCompatNightMode()
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    fun resolve(mode: ThemeModeSetting, uiMode: Int): ResolvedTheme {
        return when (mode) {
            ThemeModeSetting.LIGHT -> ResolvedTheme.LIGHT
            ThemeModeSetting.DARK -> ResolvedTheme.DARK
            ThemeModeSetting.SYSTEM -> when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> ResolvedTheme.DARK
                else -> ResolvedTheme.LIGHT
            }
        }
    }

    fun palette(context: Context, mode: ThemeModeSetting): ThemePalette {
        return palette(resolve(mode, context.resources.configuration.uiMode))
    }

    private fun palette(mode: ResolvedTheme): ThemePalette {
        return when (mode) {
            ResolvedTheme.LIGHT -> ThemePalette(
                mode = mode,
                backgroundTop = Color.rgb(247, 247, 249),
                backgroundMiddle = Color.rgb(243, 243, 246),
                backgroundAccent = Color.rgb(238, 239, 243),
                backgroundBottom = Color.rgb(247, 247, 249),
                cardTop = Color.argb(242, 255, 255, 255),
                card = Color.argb(228, 252, 252, 253),
                cardBottom = Color.argb(218, 244, 244, 247),
                cardStroke = Color.argb(30, 60, 60, 67),
                glassHighlight = Color.argb(248, 255, 255, 255),
                input = Color.argb(232, 255, 255, 255),
                iconButton = Color.argb(224, 245, 245, 248),
                iconButtonPressed = Color.argb(232, 220, 221, 226),
                text = Color.rgb(29, 29, 31),
                muted = Color.rgb(110, 110, 115),
                accent = Color.rgb(0, 122, 255),
                accentSecondary = Color.rgb(0, 102, 219),
                accentPressed = Color.rgb(0, 86, 185),
                secondary = Color.rgb(72, 72, 78),
                secondaryPressed = Color.rgb(58, 58, 64),
                danger = Color.rgb(255, 59, 48),
                success = Color.rgb(48, 209, 88),
                pill = Color.argb(170, 226, 239, 255),
                pillStroke = Color.argb(42, 0, 122, 255),
                shadow = Color.argb(34, 28, 28, 30),
            )
            ResolvedTheme.DARK -> ThemePalette(
                mode = mode,
                backgroundTop = Color.rgb(9, 9, 11),
                backgroundMiddle = Color.rgb(12, 12, 15),
                backgroundAccent = Color.rgb(17, 17, 20),
                backgroundBottom = Color.rgb(8, 8, 10),
                cardTop = Color.argb(236, 48, 48, 53),
                card = Color.argb(222, 35, 35, 39),
                cardBottom = Color.argb(214, 24, 24, 28),
                cardStroke = Color.argb(34, 255, 255, 255),
                glassHighlight = Color.argb(64, 255, 255, 255),
                input = Color.argb(228, 31, 31, 35),
                iconButton = Color.argb(214, 42, 42, 47),
                iconButtonPressed = Color.argb(232, 58, 58, 64),
                text = Color.rgb(245, 245, 247),
                muted = Color.rgb(161, 161, 166),
                accent = Color.rgb(10, 132, 255),
                accentSecondary = Color.rgb(0, 100, 216),
                accentPressed = Color.rgb(0, 84, 184),
                secondary = Color.rgb(58, 58, 60),
                secondaryPressed = Color.rgb(72, 72, 74),
                danger = Color.rgb(255, 105, 97),
                success = Color.rgb(48, 209, 88),
                pill = Color.argb(150, 18, 55, 92),
                pillStroke = Color.argb(42, 255, 255, 255),
                shadow = Color.argb(118, 0, 0, 0),
            )
        }
    }

    @Suppress("DEPRECATION")
    fun applyToActivity(activity: Activity, palette: ThemePalette) {
        activity.window.statusBarColor = palette.backgroundTop
        activity.window.navigationBarColor = palette.backgroundBottom
        activity.window.decorView.systemUiVisibility = if (palette.isDark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    fun applyBackground(view: View, palette: ThemePalette) {
        view.background = GradientDrawableFactory.appBackground(palette)
    }

    fun applyCard(view: View, palette: ThemePalette) {
        view.background = GradientDrawableFactory.card(palette)
        view.elevation = dp(view.context, if (palette.isDark) 1 else 2).toFloat()
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    fun applyInput(input: EditText, palette: ThemePalette) {
        input.background = GradientDrawableFactory.input(palette)
        input.setTextColor(palette.text)
        input.setHintTextColor(palette.muted)
        input.backgroundTintList = null
    }

    fun applyIconButton(button: ImageButton, palette: ThemePalette, tint: Int = palette.accent) {
        button.background = GradientDrawableFactory.iconButton(palette)
        button.imageTintList = ColorStateList.valueOf(tint)
        button.stateListAnimator = null
        button.elevation = 0f
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applyBareIconButton(button: ImageButton, palette: ThemePalette, tint: Int = palette.muted) {
        button.background = GradientDrawableFactory.dockAction(palette)
        button.imageTintList = ColorStateList.valueOf(tint)
        button.stateListAnimator = null
        button.elevation = 0f
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applyAccentIconButton(button: ImageButton, palette: ThemePalette, tint: Int = Color.WHITE) {
        button.background = GradientDrawableFactory.filledButton(
            palette.accent,
            palette.accentSecondary,
            palette.accentPressed,
            radiusDp = 24,
        )
        button.imageTintList = ColorStateList.valueOf(tint)
        button.stateListAnimator = null
        button.elevation = dp(button.context, 5).toFloat()
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applyPrimaryButton(button: Button, palette: ThemePalette) {
        button.background = GradientDrawableFactory.filledButton(
            palette.accent,
            palette.accentSecondary,
            palette.accentPressed,
            radiusDp = 26,
        )
        button.setTextColor(Color.WHITE)
        TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(Color.WHITE))
        button.stateListAnimator = null
        button.elevation = dp(button.context, 5).toFloat()
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applyGlassDock(view: View, palette: ThemePalette) {
        view.background = GradientDrawableFactory.glassAction(palette)
        view.elevation = dp(view.context, if (palette.isDark) 3 else 5).toFloat()
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    fun applyDockActionButton(button: Button, palette: ThemePalette) {
        button.background = GradientDrawableFactory.dockAction(palette)
        button.setTextColor(palette.text)
        TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(palette.accent))
        button.stateListAnimator = null
        button.elevation = 0f
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applySecondaryButton(button: Button, palette: ThemePalette) {
        button.background = GradientDrawableFactory.filledButton(
            palette.secondary,
            palette.accent,
            palette.secondaryPressed,
            radiusDp = 18,
        )
        button.setTextColor(Color.WHITE)
        TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(Color.WHITE))
        button.stateListAnimator = null
        button.elevation = dp(button.context, 3).toFloat()
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applyOutlineButton(button: Button, palette: ThemePalette, tint: Int = palette.accent) {
        button.background = GradientDrawableFactory.outlineButton(palette)
        button.setTextColor(tint)
        TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(tint))
        button.stateListAnimator = null
        button.elevation = dp(button.context, if (palette.isDark) 2 else 3).toFloat()
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    fun applyPill(view: TextView, palette: ThemePalette) {
        view.background = GradientDrawableFactory.pill(palette)
        view.setTextColor(if (palette.isDark) Color.rgb(207, 229, 255) else palette.accentPressed)
    }

    fun applyAccentTile(view: View, palette: ThemePalette) {
        view.background = GradientDrawableFactory.accentTile(palette)
        view.elevation = dp(view.context, 4).toFloat()
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    fun applyProgress(progress: ProgressBar, palette: ThemePalette) {
        progress.progressTintList = ColorStateList.valueOf(palette.accentSecondary)
        progress.indeterminateTintList = ColorStateList.valueOf(palette.accentSecondary)
        progress.progressBackgroundTintList = ColorStateList.valueOf(
            if (palette.isDark) Color.argb(54, 255, 255, 255) else Color.argb(38, 19, 34, 56),
        )
    }

    fun applyText(root: View, palette: ThemePalette) {
        walk(root) { view ->
            if (view is TextView && view !is EditText && view !is Button) {
                val tag = view.tag?.toString().orEmpty()
                view.setTextColor(if (tag == "muted") palette.muted else palette.text)
            }
        }
    }

    fun applySectionLabel(label: TextView, palette: ThemePalette) {
        label.setTextColor(palette.muted)
        label.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private fun walk(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), action)
            }
        }
    }

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
