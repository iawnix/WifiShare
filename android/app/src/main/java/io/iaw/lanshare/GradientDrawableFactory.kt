package io.iaw.lanshare

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

object GradientDrawableFactory {
    fun appBackground(palette: ThemePalette): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                palette.backgroundTop,
                palette.backgroundMiddle,
                palette.backgroundAccent,
                palette.backgroundBottom,
            ),
        )
    }

    fun card(palette: ThemePalette): GradientDrawable {
        return roundedGradient(
            intArrayOf(palette.cardTop, palette.card, palette.cardBottom),
            radiusDp = 8,
            strokeColor = palette.cardStroke,
        )
    }

    fun sheet(palette: ThemePalette): GradientDrawable {
        return roundedGradient(
            intArrayOf(palette.cardTop, palette.card, palette.backgroundBottom),
            radiusDp = 16,
            strokeColor = palette.cardStroke,
        )
    }

    fun selectionSurface(palette: ThemePalette): RippleDrawable {
        val fill = ColorStateList.valueOf(palette.accent)
            .withAlpha(if (palette.isDark) 44 else 22)
            .defaultColor
        val stroke = ColorStateList.valueOf(palette.accent)
            .withAlpha(if (palette.isDark) 88 else 54)
            .defaultColor
        val content = rounded(fill, 8, stroke)
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            content,
            rounded(android.graphics.Color.WHITE, 8, null),
        )
    }

    fun statusDot(palette: ThemePalette, active: Boolean): GradientDrawable {
        return rounded(if (active) palette.accent else palette.muted, 5, null)
    }

    fun selectedRow(palette: ThemePalette): GradientDrawable {
        return roundedGradient(
            intArrayOf(
                ColorStateList.valueOf(palette.accent).withAlpha(if (palette.isDark) 34 else 22).defaultColor,
                ColorStateList.valueOf(palette.accentSecondary).withAlpha(if (palette.isDark) 18 else 12).defaultColor,
            ),
            radiusDp = 0,
            strokeColor = null,
        )
    }

    fun listRow(palette: ThemePalette, selected: Boolean): RippleDrawable {
        val content = if (selected) {
            selectedRow(palette)
        } else {
            rounded(android.graphics.Color.TRANSPARENT, 0, null)
        }
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            content,
            rounded(android.graphics.Color.WHITE, 0, null),
        )
    }

    fun input(palette: ThemePalette): GradientDrawable {
        return rounded(palette.input, 8, palette.cardStroke).apply {
            setPadding(14.dp(), 11.dp(), 14.dp(), 11.dp())
        }
    }

    fun iconButton(palette: ThemePalette): RippleDrawable {
        val content = roundedGradient(
            intArrayOf(palette.cardTop, palette.iconButton, palette.cardBottom),
            radiusDp = 8,
            strokeColor = palette.cardStroke,
        )
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            content,
            rounded(android.graphics.Color.WHITE, 8, null),
        )
    }

    fun glassAction(palette: ThemePalette): RippleDrawable {
        val content = roundedGradient(
            intArrayOf(palette.glassHighlight, palette.cardTop, palette.cardBottom),
            radiusDp = 18,
            strokeColor = palette.cardStroke,
        )
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            content,
            rounded(android.graphics.Color.WHITE, 18, null),
        )
    }

    fun serverTile(palette: ThemePalette, selected: Boolean): RippleDrawable {
        val selectedTint = ColorStateList.valueOf(palette.accent)
            .withAlpha(if (palette.isDark) 46 else 24)
            .defaultColor
        val selectedStroke = ColorStateList.valueOf(palette.accent)
            .withAlpha(if (palette.isDark) 196 else 154)
            .defaultColor
        val content = roundedGradient(
            colors = if (selected) {
                intArrayOf(selectedTint, palette.cardTop, palette.cardBottom)
            } else {
                intArrayOf(palette.cardTop, palette.card, palette.cardBottom)
            },
            radiusDp = 8,
            strokeColor = if (selected) selectedStroke else palette.cardStroke,
        )
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            content,
            rounded(android.graphics.Color.WHITE, 8, null),
        )
    }

    fun selectedCapsule(palette: ThemePalette): GradientDrawable {
        return roundedGradient(
            intArrayOf(palette.accent, palette.accentSecondary),
            radiusDp = 12,
            strokeColor = null,
        )
    }

    fun dockAction(palette: ThemePalette): RippleDrawable {
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            rounded(android.graphics.Color.TRANSPARENT, 6, null),
            rounded(android.graphics.Color.WHITE, 6, null),
        )
    }

    fun filledButton(
        startColor: Int,
        endColor: Int,
        pressed: Int,
        radiusDp: Int = 8,
    ): RippleDrawable {
        val content = roundedGradient(
            intArrayOf(startColor, endColor),
            radiusDp = radiusDp,
            strokeColor = android.graphics.Color.argb(118, 255, 255, 255),
        )
        return RippleDrawable(
            ColorStateList.valueOf(pressed),
            content,
            rounded(android.graphics.Color.WHITE, radiusDp, null),
        )
    }

    fun outlineButton(palette: ThemePalette): RippleDrawable {
        val content = roundedGradient(
            intArrayOf(palette.cardTop, palette.iconButton, palette.cardBottom),
            radiusDp = 24,
            strokeColor = palette.cardStroke,
        )
        return RippleDrawable(
            ColorStateList.valueOf(palette.iconButtonPressed),
            content,
            rounded(android.graphics.Color.WHITE, 24, null),
        )
    }

    fun pill(palette: ThemePalette): GradientDrawable {
        return roundedGradient(
            intArrayOf(palette.glassHighlight, palette.pill),
            radiusDp = 12,
            strokeColor = palette.pillStroke,
        ).apply {
            setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
        }
    }

    fun accentTile(palette: ThemePalette): GradientDrawable {
        return roundedGradient(
            intArrayOf(palette.accent, palette.accentSecondary),
            radiusDp = 12,
            strokeColor = android.graphics.Color.argb(118, 255, 255, 255),
        )
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

    private fun roundedGradient(
        colors: IntArray,
        radiusDp: Int,
        strokeColor: Int?,
    ): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dp().toFloat()
            if (strokeColor != null) {
                setStroke(1.dp(), strokeColor)
            }
        }
    }

    private fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
