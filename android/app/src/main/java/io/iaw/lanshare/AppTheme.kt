package io.iaw.lanshare

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

enum class ThemeModeSetting {
    LIGHT,
    DARK;

    fun toggled(): ThemeModeSetting = if (this == LIGHT) DARK else LIGHT
}

data class ThemePalette(
    val mode: ThemeModeSetting,
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val card: Int,
    val cardStroke: Int,
    val input: Int,
    val iconButton: Int,
    val iconButtonPressed: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val accentPressed: Int,
    val secondary: Int,
    val secondaryPressed: Int,
    val danger: Int,
    val success: Int,
    val pill: Int,
    val pillStroke: Int,
    val shadow: Int,
) {
    val isDark: Boolean get() = mode == ThemeModeSetting.DARK
}

object AppTheme {
    fun palette(mode: ThemeModeSetting): ThemePalette {
        return when (mode) {
            ThemeModeSetting.LIGHT -> ThemePalette(
                mode = mode,
                backgroundTop = Color.rgb(236, 240, 245),
                backgroundBottom = Color.rgb(247, 248, 250),
                card = Color.rgb(255, 255, 255),
                cardStroke = Color.rgb(205, 213, 224),
                input = Color.rgb(245, 247, 250),
                iconButton = Color.rgb(255, 255, 255),
                iconButtonPressed = Color.rgb(235, 241, 250),
                text = Color.rgb(28, 28, 30),
                muted = Color.rgb(99, 99, 105),
                accent = Color.rgb(0, 122, 255),
                accentPressed = Color.rgb(0, 92, 210),
                secondary = Color.rgb(44, 44, 46),
                secondaryPressed = Color.rgb(24, 24, 26),
                danger = Color.rgb(255, 69, 58),
                success = Color.rgb(52, 199, 89),
                pill = Color.rgb(232, 242, 255),
                pillStroke = Color.rgb(185, 213, 245),
                shadow = Color.argb(32, 0, 0, 0),
            )
            ThemeModeSetting.DARK -> ThemePalette(
                mode = mode,
                backgroundTop = Color.rgb(18, 19, 22),
                backgroundBottom = Color.rgb(30, 32, 36),
                card = Color.rgb(38, 39, 43),
                cardStroke = Color.rgb(73, 75, 82),
                input = Color.rgb(28, 29, 33),
                iconButton = Color.rgb(44, 45, 50),
                iconButtonPressed = Color.rgb(60, 63, 70),
                text = Color.rgb(245, 245, 247),
                muted = Color.rgb(174, 174, 184),
                accent = Color.rgb(10, 132, 255),
                accentPressed = Color.rgb(64, 156, 255),
                secondary = Color.rgb(88, 90, 96),
                secondaryPressed = Color.rgb(112, 114, 121),
                danger = Color.rgb(255, 69, 58),
                success = Color.rgb(48, 209, 88),
                pill = Color.rgb(29, 55, 86),
                pillStroke = Color.rgb(48, 92, 142),
                shadow = Color.argb(70, 0, 0, 0),
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
        view.elevation = dp(view.context, if (palette.isDark) 1 else 4).toFloat()
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
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
    }

    fun applyAccentIconButton(button: ImageButton, palette: ThemePalette, tint: Int = Color.WHITE) {
        button.background = GradientDrawableFactory.filledButton(palette.accent, palette.accentPressed)
        button.imageTintList = ColorStateList.valueOf(tint)
    }

    fun applyPrimaryButton(button: Button, palette: ThemePalette) {
        button.background = GradientDrawableFactory.filledButton(palette.accent, palette.accentPressed)
        button.setTextColor(Color.WHITE)
        button.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
    }

    fun applySecondaryButton(button: Button, palette: ThemePalette) {
        button.background = GradientDrawableFactory.filledButton(palette.secondary, palette.secondaryPressed)
        button.setTextColor(Color.WHITE)
        button.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
    }

    fun applyOutlineButton(button: Button, palette: ThemePalette, tint: Int = palette.accent) {
        button.background = GradientDrawableFactory.outlineButton(palette)
        button.setTextColor(tint)
        button.compoundDrawableTintList = ColorStateList.valueOf(tint)
    }

    fun applyPill(view: TextView, palette: ThemePalette) {
        view.background = GradientDrawableFactory.pill(palette)
        view.setTextColor(if (palette.isDark) Color.rgb(207, 229, 255) else palette.accentPressed)
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
