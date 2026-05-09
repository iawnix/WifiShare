package io.iaw.lanshare

import android.os.Build
import android.view.View
import android.view.WindowInsets

object SystemBars {
    @Suppress("DEPRECATION")
    fun applyInsetPadding(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(
                    initialLeft + bars.left,
                    initialTop + bars.top,
                    initialRight + bars.right,
                    initialBottom + bars.bottom,
                )
            } else {
                view.setPadding(
                    initialLeft + insets.systemWindowInsetLeft,
                    initialTop + insets.systemWindowInsetTop,
                    initialRight + insets.systemWindowInsetRight,
                    initialBottom + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }
}
