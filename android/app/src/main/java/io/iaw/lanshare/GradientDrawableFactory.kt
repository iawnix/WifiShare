package io.iaw.lanshare

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

object GradientDrawableFactory {
    fun appBackground(palette: ThemePalette): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.backgroundTop, palette.backgroundBottom),
        )
    }

    fun card(palette: ThemePalette): GradientDrawable {
        return rounded(palette.card, 8, palette.cardStroke)
    }

    fun input(palette: ThemePalette): GradientDrawable {
        return rounded(palette.input, 8, palette.cardStroke).apply {
            setPadding(14.dp(), 11.dp(), 14.dp(), 11.dp())
        }
    }

    fun iconButton(palette: ThemePalette): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            rounded(palette.iconButton, 8, palette.cardStroke),
            null,
        )
    }

    fun filledButton(color: Int, pressed: Int): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(pressed),
            rounded(color, 8, null),
            null,
        )
    }

    fun outlineButton(palette: ThemePalette): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            rounded(palette.iconButton, 8, palette.cardStroke),
            null,
        )
    }

    fun pill(palette: ThemePalette): GradientDrawable {
        return rounded(palette.pill, 8, palette.pillStroke).apply {
            setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
        }
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int?): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dp().toFloat()
            setColor(color)
            if (strokeColor != null) {
                setStroke(1.dp(), strokeColor)
            }
        }
    }

    private fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}

